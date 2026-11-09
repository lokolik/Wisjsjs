package dev.relay.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import dev.relay.agent.agent.AgentListener
import dev.relay.agent.agent.AgentLoop
import dev.relay.agent.agent.ApprovalMode
import dev.relay.agent.agent.ApprovalPolicy
import dev.relay.agent.agent.ChatTarget
import dev.relay.agent.agent.Decision
import dev.relay.agent.agent.ToolProtocol
import dev.relay.agent.mcp.McpHandler
import dev.relay.agent.mcp.McpServer
import dev.relay.agent.nativeapp.NativeAppTarget
import dev.relay.agent.nativeapp.RelayAccessibilityService
import dev.relay.agent.site.SiteSession
import dev.relay.agent.tools.Risk
import dev.relay.agent.tools.Shell
import dev.relay.agent.tools.ToolCall
import dev.relay.agent.tools.ToolExecutor
import dev.relay.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/** Connects the HTML UI with AI sites/apps, the agent loop, device tools and the MCP server. */
class RelayController(
    private val activity: MainActivity,
    private val ui: WebView,
    private val siteFrame: FrameLayout,
) {
    private val scope = MainScope()
    private val prefs = Prefs(activity)
    private val shell = Shell()
    private val platform = AndroidPlatform(activity.applicationContext)
    private val executor = ToolExecutor(platform, shell) { prefs.rootMode != "off" }
    private val approvals = ApprovalPolicy()
    private val coreJs: String by lazy { asset("inject/relay-core.js") }
    private val builtins: List<JSONObject> by lazy { JSONObject(asset("adapters.json")).getJSONArray("adapters").objects() }

    private class PendingApproval(val source: String, val decision: CompletableDeferred<Decision>)

    private val sessions = LinkedHashMap<String, SiteSession>()
    private var current: JSONObject? = null
    private var runJob: Job? = null
    private var chatsJob: Job? = null
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()
    private var mcp: McpServer? = null
    private var picking: String? = null
    private var siteShown = false

    /** Key of an open "new chat" page that already got the preamble; kept in memory only (see [ChatKeys.isFresh]). */
    private var preambleOnPage: String? = null
    private var preambleAt = 0L

    fun start() {
        if (prefs.mcpEnabled) setMcp(true)
    }

    fun onUiMessage(raw: String) {
        scope.launch {
            try {
                handle(JSONObject(raw))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "command failed", e)
                emitError(e.message ?: e.toString())
            }
        }
    }

    private suspend fun handle(m: JSONObject) {
        when (m.optString("cmd")) {
            "init" -> emit(initJson())
            "selectAdapter" -> selectAdapter(m.getString("id"))
            "refreshChats" -> currentSession()?.let { emitChats(it) } ?: current?.let { emitAppChats(it) }
            "openChat" -> openChat(m.getString("href"))
            "newChat" -> newChat()
            "send" -> send(m.getString("text"), m.optBoolean("agent", true))
            "stop" -> stop()
            "approve" -> pendingApprovals[m.getString("callId")]?.decision?.complete(
                when (m.optString("decision")) {
                    "always" -> Decision.ALWAYS
                    "once" -> Decision.ONCE
                    else -> Decision.DENY
                },
            )
            "showSite" -> showSite(true)
            "hideSite" -> closeSite()
            "reloadSite" -> reloadSite()
            "pick" -> pick(m.getString("kind"))
            "diagnose" -> diagnose(m.optJSONObject("selectors"))
            "saveAdapter" -> saveAdapter(m.getJSONObject("adapter"))
            "deleteAdapter" -> deleteAdapter(m.getString("id"))
            "listApps" -> listApps()
            "setSetting" -> setSetting(m.getString("key"), m.get("value"))
            "perm" -> requestPermission(m.getString("which"))
            "checkRoot" -> checkRoot()
            "mcp" -> setMcp(m.getBoolean("enabled"))
            "rotateToken" -> {
                prefs.rotateMcpToken()
                if (mcp != null) setMcp(true) else emit(mcpJson())
            }
            "resendPreamble" -> {
                prefs.forgetPreamble(chatKey())
                preambleOnPage = null
                toast("Инструкция для ИИ будет добавлена к следующему сообщению")
            }
            "clearSites" -> clearSiteData()
            "copy" -> copy(m.getString("text"))
            "openExternal" -> runCatching { platform.openUrl(m.getString("url")) }
            "exit" -> activity.moveTaskToBack(true)
        }
    }

    // region adapters & sessions

    private fun asset(path: String) = activity.assets.open(path).bufferedReader().use { it.readText() }

    private fun allAdapters(): JSONArray {
        val byId = LinkedHashMap<String, JSONObject>()
        builtins.forEach { byId[it.optString("id")] = it.copy().put("builtin", true) }
        prefs.customAdapters.objects().forEach { c ->
            val id = c.optString("id")
            val base = byId[id]
            byId[id] = if (base != null) base.mergedWith(c).put("overridden", true) else c.copy().put("custom", true)
        }
        val pm = activity.packageManager
        byId.values.filter { it.optString("kind") == "app" }.forEach {
            it.put("installed", pm.getLaunchIntentForPackage(it.optString("package")) != null)
        }
        return JSONArray(byId.values.toList())
    }

    private fun findAdapter(id: String): JSONObject? = allAdapters().objects().firstOrNull { it.optString("id") == id }

    private fun currentSession(): SiteSession? = current?.let { sessions[it.optString("id")] }

    private fun isApp(a: JSONObject?) = a?.optString("kind") == "app"

    private fun selectAdapter(id: String) {
        val a = findAdapter(id) ?: return emitError("Сервис «$id» не найден")
        current = a
        prefs.lastAdapter = id
        if (isApp(a)) {
            val state = when {
                !a.optBoolean("installed") -> "notInstalled"
                !accessibilityEnabled() -> "needsAccessibility"
                else -> "ready"
            }
            emit(sessionJson(id, state, "app:" + a.optString("package"), a.optString("name")))
            emitAppChats(a)
            return
        }
        val s = sessions.getOrPut(id) {
            SiteSession(activity, a, coreJs, scope, ::onSiteEvent).also {
                siteFrame.addView(it.webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
        // Inactive sites stay laid out (INVISIBLE, not GONE) so their pages keep real sizes.
        sessions.values.forEach { it.webView.visibility = if (it === s) View.VISIBLE else View.INVISIBLE }
        s.webView.bringToFront()
        emit(sessionJson(id, "loading", s.currentUrl, a.optString("name")))
        scope.launch { refreshStatus(s) }
        scheduleChats(s)
    }

    private fun sessionJson(id: String, state: String, url: String, title: String) = JSONObject()
        .put("type", "session").put("adapterId", id).put("state", state).put("url", url).put("title", title)

    private suspend fun refreshStatus(s: SiteSession) {
        val st = runCatching { s.status() }.getOrNull() ?: return
        if (s !== currentSession()) return
        if (st.optString("readyState") != "complete" && !st.optBoolean("input")) return
        emit(sessionJson(s.id, if (st.optBoolean("input")) "ready" else "login", st.optString("url"), st.optString("title")))
    }

    private fun onSiteEvent(s: SiteSession, ev: JSONObject) {
        when (ev.optString("type")) {
            "pageLoaded" -> {
                onPageUrl(s, ev.optString("url"), fullLoad = true)
                if (s === currentSession()) {
                    scope.launch {
                        refreshStatus(s)
                        emit(JSONObject().put("type", "url").put("adapterId", s.id).put("url", ev.optString("url")))
                    }
                    scheduleChats(s)
                }
            }
            "urlChanged" -> {
                onPageUrl(s, ev.optString("url"), fullLoad = false)
                if (s === currentSession()) {
                    emit(JSONObject().put("type", "url").put("adapterId", s.id).put("url", ev.optString("url")))
                    scheduleChats(s)
                }
            }
            "crashed" -> onSiteCrashed(s)
            "picked", "pickCancelled" -> {
                picking = null
                showSite(false)
                emit(ev)
            }
            "log" -> Log.d(TAG, "site ${s.id}: ${ev.optString("message")}")
        }
    }

    /** Moves the in-memory preamble mark when a fresh conversation gets its own address, or drops it. */
    private fun onPageUrl(s: SiteSession, url: String, fullLoad: Boolean) {
        val mark = preambleOnPage ?: return
        if (!mark.startsWith(s.id + "|")) return
        val key = ChatKeys.forUrl(s.adapter, url)
        if (key == mark) {
            // Reloading the new-chat page starts a new conversation.
            if (fullLoad) preambleOnPage = null
            return
        }
        if (!ChatKeys.isFresh(s.adapter, key) && System.currentTimeMillis() - preambleAt < PREAMBLE_CARRY_MS) prefs.markPreamble(key)
        preambleOnPage = null
    }

    /** Without handling, a dead renderer kills the whole app; the site is rebuilt instead. */
    private fun onSiteCrashed(s: SiteSession) {
        if (sessions[s.id] !== s) return
        sessions.remove(s.id)
        siteFrame.removeView(s.webView)
        s.destroy()
        if (current?.optString("id") != s.id) return
        picking = null
        if (siteShown) showSite(false)
        toast("Страница ${s.adapter.optString("name")} аварийно закрылась — открываю заново")
        selectAdapter(s.id)
    }

    private fun scheduleChats(s: SiteSession) {
        chatsJob?.cancel()
        chatsJob = scope.launch {
            // Sites render their history lazily, so keep polling a bit longer while the list is still empty.
            for ((i, d) in CHAT_POLL_MS.withIndex()) {
                delay(d)
                if (s !== currentSession()) return@launch
                val found = emitChats(s)
                refreshStatus(s)
                if (found > 0 && i >= 2) return@launch
            }
        }
    }

    /** @return how many chats were found on the page. */
    private suspend fun emitChats(s: SiteSession): Int {
        val items = runCatching { s.listChats() }.getOrDefault(JSONArray())
        val a = s.adapter
        if (a.optBoolean("singleThread")) {
            val all = JSONArray().put(
                JSONObject().put("title", a.optString("singleThreadTitle", "Основной тред")).put("href", a.optString("url")).put("kind", "chat"),
            )
            items.objects().forEach { all.put(it) }
            emit(JSONObject().put("type", "chats").put("adapterId", s.id).put("items", all))
        } else {
            emit(JSONObject().put("type", "chats").put("adapterId", s.id).put("items", items))
        }
        return items.length()
    }

    private fun emitAppChats(a: JSONObject) {
        val items = JSONArray().put(
            JSONObject().put("title", "Текущий чат в приложении").put("href", "app:" + a.optString("package")).put("kind", "chat"),
        )
        emit(JSONObject().put("type", "chats").put("adapterId", a.optString("id")).put("items", items))
    }

    /** The site WebView is shared by all chats of a service, so navigating mid-turn would abandon the reply. */
    private fun busyNavigating(): Boolean {
        if (runJob?.isActive != true) return false
        toast(BUSY_TEXT)
        return true
    }

    private fun openChat(href: String) {
        val s = currentSession() ?: return
        if (busyNavigating()) return
        preambleOnPage = null
        s.navigate(href)
        emit(JSONObject().put("type", "url").put("adapterId", s.id).put("url", href))
    }

    private fun newChat() {
        val a = current ?: return
        val s = currentSession() ?: return
        if (busyNavigating()) return
        preambleOnPage = null
        val url = a.optString("newChatUrl").ifBlank { a.optString("url") }
        s.navigate(url)
        emit(JSONObject().put("type", "url").put("adapterId", s.id).put("url", url))
    }

    private suspend fun diagnose(selectors: JSONObject?) {
        val s = currentSession() ?: return emitError("Сначала выберите этот сайт")
        s.ensureCore()
        val arg = selectors?.toString() ?: "null"
        val raw = s.eval("JSON.stringify(window.__relay.diagnose($arg))")
        val str = runCatching { org.json.JSONTokener(raw).nextValue() as String }.getOrNull() ?: "{}"
        emit(JSONObject(str).put("type", "diagnose"))
    }

    private fun saveAdapter(adapter: JSONObject) {
        val id = adapter.optString("id")
        if (id.isBlank()) return emitError("У сервиса нет id")
        listOf("installed", "overridden", "custom", "builtin").forEach { adapter.remove(it) }
        val base = builtins.firstOrNull { it.optString("id") == id }
        // Overrides of built-ins keep only what the user changed, so app updates still reach everything else.
        val stored = if (base != null) adapter.diffFrom(base).put("id", id) else adapter
        val list = prefs.customAdapters.objects().filter { it.optString("id") != id }.toMutableList()
        if (base == null || stored.length() > 1) list += stored
        prefs.customAdapters = JSONArray(list)
        val merged = findAdapter(id) ?: return
        sessions[id]?.updateAdapter(merged)
        if (current?.optString("id") == id) current = merged
        emit(JSONObject().put("type", "adapters").put("adapters", allAdapters()))
        toast("Сохранено")
    }

    private fun deleteAdapter(id: String) {
        prefs.customAdapters = JSONArray(prefs.customAdapters.objects().filter { it.optString("id") != id })
        val isBuiltin = builtins.any { it.optString("id") == id }
        if (isBuiltin) {
            findAdapter(id)?.let { a ->
                sessions[id]?.updateAdapter(a)
                if (current?.optString("id") == id) current = a
            }
        } else {
            sessions.remove(id)?.let {
                siteFrame.removeView(it.webView)
                it.destroy()
            }
            if (current?.optString("id") == id) current = null
        }
        emit(JSONObject().put("type", "adapters").put("adapters", allAdapters()))
        toast(if (isBuiltin) "Настройки сброшены" else "Удалено")
    }

    private suspend fun listApps() {
        val apps = withContext(Dispatchers.IO) { AndroidPlatform.launcherApps(activity) }
        val arr = JSONArray()
        apps.forEach { (pkg, label) -> arr.put(JSONObject().put("package", pkg).put("label", label)) }
        emit(JSONObject().put("type", "apps").put("items", arr))
    }

    // endregion

    // region site visibility & picker

    private fun showSite(visible: Boolean, hint: String? = null) {
        val a = current
        if (visible && isApp(a)) {
            runCatching { platform.launchApp(a!!.optString("package")) }.onFailure { emitError(it.message ?: "Не удалось открыть приложение") }
            return
        }
        if (visible && currentSession() == null) return emitError("Сначала выберите сайт")
        siteShown = visible
        activity.setSiteVisible(visible, hint ?: a?.optString("name").orEmpty(), hint != null)
    }

    fun closeSite() {
        if (picking != null) {
            picking = null
            currentSession()?.let { scope.launch { it.cancelPick() } }
            emit(JSONObject().put("type", "pickCancelled"))
        }
        showSite(false)
    }

    fun reloadSite() {
        currentSession()?.webView?.reload()
    }

    private suspend fun pick(kind: String) {
        val s = currentSession() ?: return emitError("Сначала выберите этот сайт")
        picking = kind
        showSite(true, PICK_HINTS[kind] ?: "Нажмите на нужный элемент")
        s.pick(kind)
    }

    /** @return true when the back press was consumed. */
    fun onBack(): Boolean {
        if (picking != null || siteShown) {
            val wv = currentSession()?.webView
            if (picking == null && wv != null && wv.canGoBack()) wv.goBack() else closeSite()
            return true
        }
        emit(JSONObject().put("type", "back"))
        return true
    }

    // endregion

    // region agent

    private fun chatKey(a: JSONObject? = current): String {
        a ?: return "none"
        return ChatKeys.forUrl(a, sessions[a.optString("id")]?.currentUrl ?: a.optString("url"))
    }

    private fun preambleContext() = ToolProtocol.Context(
        storageRoot = platform.storageRoot.path,
        rootAvailable = prefs.rootMode != "off" && (shell.cachedRoot ?: shell.suPresent()),
        device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
    )

    private fun send(text: String, agent: Boolean) {
        if (runJob?.isActive == true) return emitError("Агент уже работает — дождитесь ответа или нажмите «Стоп».")
        val a = current ?: return emitError("Сначала выберите ИИ-сервис")
        val target: ChatTarget = if (isApp(a)) {
            NativeAppTarget(activity, a.optString("package"), MainActivity::class.java)
        } else {
            sessions[a.optString("id")] ?: return emitError("Сайт ещё не загружен")
        }
        val keyBefore = chatKey(a)
        val alreadySent = if (ChatKeys.isFresh(a, keyBefore)) preambleOnPage == keyBefore else prefs.preambleSent(keyBefore)
        val withPreamble = agent && prefs.autoPreamble && !alreadySent
        val first = if (withPreamble) ToolProtocol.withPreamble(preambleContext(), text) else text
        approvals.mode = approvalMode()
        var delivered = false

        val listener = object : AgentListener {
            override fun onPartial(text: String) {
                emit(JSONObject().put("type", "partial").put("text", ToolProtocol.stripCalls(text)))
            }

            override fun onAssistant(text: String, step: Int) {
                delivered = true
                emit(
                    JSONObject().put("type", "assistant").put("step", step).put("text", text)
                        .put("display", ToolProtocol.stripCalls(text)),
                )
            }

            override fun onToolStatus(call: ToolCall, status: String, result: ToolResult?) {
                emit(toolJson(call, status, result, "chat"))
            }

            override fun onInfo(text: String) = emitInfo(text)

            override suspend fun requestApproval(call: ToolCall, risk: Risk): Decision = askApproval(call, risk, SOURCE_CHAT)
        }

        runJob = scope.launch {
            emit(JSONObject().put("type", "turn").put("state", "start").put("preamble", withPreamble))
            try {
                AgentLoop(
                    target = target,
                    execute = { c -> withContext(Dispatchers.IO) { executor.execute(c) } },
                    riskOf = ToolExecutor::riskOf,
                    listener = listener,
                    approvals = approvals,
                    maxSteps = prefs.maxSteps,
                ).run(first, agent)
            } catch (e: CancellationException) {
                emitInfo("Остановлено")
            } catch (e: Exception) {
                Log.w(TAG, "agent failed", e)
                emitError(e.message ?: e.toString())
            } finally {
                if (withPreamble && delivered) rememberPreamble(a, keyBefore)
                denyPending(SOURCE_CHAT)
                emit(JSONObject().put("type", "turn").put("state", "end"))
                // A new conversation usually shows up in the site's list only after the reply.
                (target as? SiteSession)?.let { if (it === currentSession()) scheduleChats(it) }
            }
        }
    }

    private fun rememberPreamble(a: JSONObject, keyBefore: String) {
        val keyAfter = chatKey(a)
        setOf(keyBefore, keyAfter).filterNot { ChatKeys.isFresh(a, it) }.forEach(prefs::markPreamble)
        if (ChatKeys.isFresh(a, keyAfter)) {
            preambleOnPage = keyAfter
            preambleAt = System.currentTimeMillis()
        }
    }

    private fun stop() {
        denyPending(SOURCE_CHAT)
        runJob?.cancel()
    }

    /** Only approvals of [source] are denied: ending a chat turn must not reject a waiting MCP call. */
    private fun denyPending(source: String) {
        pendingApprovals.values.filter { it.source == source }.forEach { it.decision.complete(Decision.DENY) }
    }

    private fun approvalMode() = when (prefs.approvalMode) {
        "all" -> ApprovalMode.AUTO_ALL
        "read" -> ApprovalMode.AUTO_READ
        else -> ApprovalMode.ASK
    }

    private suspend fun askApproval(call: ToolCall, risk: Risk, source: String): Decision {
        val d = CompletableDeferred<Decision>()
        pendingApprovals[call.id] = PendingApproval(source, d)
        emit(
            JSONObject().put("type", "approval").put("source", source).put("risk", risk.name)
                .put("call", callJson(call)),
        )
        return try {
            // An MCP client holds a connection thread open for this call, so it must not wait forever.
            if (source == SOURCE_MCP) withTimeoutOrNull(MCP_APPROVAL_TIMEOUT_MS) { d.await() } ?: Decision.DENY else d.await()
        } finally {
            pendingApprovals.remove(call.id)
        }
    }

    private fun callJson(call: ToolCall) = JSONObject().put("id", call.id).put("tool", call.tool).put("args", call.args)

    private fun toolJson(call: ToolCall, status: String, result: ToolResult?, source: String): JSONObject {
        val o = JSONObject().put("type", "tool").put("source", source).put("status", status)
            .put("risk", ToolExecutor.riskOf(call).name).put("call", callJson(call))
        if (result != null) o.put("result", JSONObject().put("ok", result.ok).put("output", result.output))
        return o
    }

    // endregion

    // region MCP

    private fun setMcp(enabled: Boolean) {
        denyPending(SOURCE_MCP)
        mcp?.stop()
        mcp = null
        prefs.mcpEnabled = enabled
        if (enabled) {
            try {
                mcp = McpServer(prefs.mcpPort, prefs.mcpToken, McpHandler(::mcpCall)).also { it.start() }
            } catch (e: Exception) {
                prefs.mcpEnabled = false
                emitError("MCP-сервер не запустился: ${e.message}")
            }
        }
        emit(mcpJson())
    }

    /** Runs on an MCP connection thread; approvals go through the same UI as chat tool calls. */
    private fun mcpCall(call: ToolCall): ToolResult = runBlocking {
        val risk = ToolExecutor.riskOf(call)
        approvals.mode = approvalMode()
        val allowed = if (approvals.isAutoApproved(call.tool, risk)) {
            true
        } else {
            emit(toolJson(call, "pending", null, "mcp"))
            when (askApproval(call, risk, SOURCE_MCP)) {
                Decision.ONCE -> true
                Decision.ALWAYS -> {
                    approvals.allowAlways(call.tool, risk)
                    true
                }
                Decision.DENY -> false
            }
        }
        if (!allowed) {
            val r = ToolResult(false, "Пользователь отклонил это действие на телефоне.")
            emit(toolJson(call, "denied", r, "mcp"))
            return@runBlocking r
        }
        emit(toolJson(call, "running", null, "mcp"))
        val r = executor.execute(call)
        emit(toolJson(call, if (r.ok) "done" else "error", r, "mcp"))
        r
    }

    private fun mcpJson(): JSONObject = JSONObject()
        .put("type", "mcp")
        .put("running", mcp?.isRunning == true)
        .put("url", "http://${localIp()}:${prefs.mcpPort}/mcp")
        .put("port", prefs.mcpPort)
        .put("token", prefs.mcpToken)

    private fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: "127.0.0.1"
    } catch (e: Exception) {
        "127.0.0.1"
    }

    // endregion

    // region device, permissions, settings

    private fun initJson(): JSONObject = JSONObject()
        .put("type", "init")
        .put("adapters", allAdapters())
        .put("settings", prefs.settingsJson())
        .put("device", deviceJson())
        .put("mcp", mcpJson())
        .put("lastAdapter", prefs.lastAdapter ?: JSONObject.NULL)
        .put("version", runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "")

    private fun deviceJson(): JSONObject = JSONObject()
        .put("type", "device")
        .put("suPresent", shell.suPresent())
        .put("root", shell.cachedRoot ?: JSONObject.NULL)
        .put("storage", hasStorage())
        .put("accessibility", accessibilityEnabled())
        .put("sdk", Build.VERSION.SDK_INT)
        .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("storageRoot", platform.storageRoot.path)

    fun refreshDevice() {
        emit(deviceJson())
        if (isApp(current)) current?.let { selectAdapter(it.optString("id")) }
    }

    private fun hasStorage(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun accessibilityEnabled(): Boolean {
        if (RelayAccessibilityService.instance != null) return true
        val enabled = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val comp = ComponentName(activity, RelayAccessibilityService::class.java)
        return enabled.split(':').any { it.equals(comp.flattenToString(), true) || it.equals(comp.flattenToShortString(), true) }
    }

    private suspend fun checkRoot() {
        if (!shell.suPresent()) {
            emit(deviceJson())
            return toast("su не найден — root недоступен")
        }
        val has = withContext(Dispatchers.IO) { shell.hasRoot(refresh = true) }
        emit(deviceJson())
        toast(if (has) "Root-доступ получен" else "Root не выдан. Разрешите Relay в Magisk/KernelSU")
    }

    private suspend fun rootRun(cmd: String): Boolean {
        if (prefs.rootMode == "off" || !shell.suPresent()) return false
        return withContext(Dispatchers.IO) { shell.hasRoot() && shell.run(cmd, root = true).ok }
    }

    /** With root the permission is granted silently; otherwise the system settings screen opens. */
    private suspend fun requestPermission(which: String) {
        val pkg = activity.packageName
        when (which) {
            "storage" -> {
                if (hasStorage()) return toast("Доступ к файлам уже есть")
                val cmd = if (Build.VERSION.SDK_INT >= 30) {
                    "appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow"
                } else {
                    "pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE; pm grant $pkg android.permission.READ_EXTERNAL_STORAGE"
                }
                if (rootRun(cmd) && hasStorage()) {
                    toast("Доступ к файлам выдан через root")
                } else if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))
                    runCatching { activity.startActivity(intent) }
                        .onFailure { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                } else {
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
                        1,
                    )
                }
            }
            "accessibility" -> {
                if (accessibilityEnabled()) return toast("Служба уже включена")
                val comp = ComponentName(activity, RelayAccessibilityService::class.java).flattenToString()
                val existing = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val merged = (existing.split(':').filter { it.isNotBlank() && !it.equals(comp, true) } + comp).joinToString(":")
                val cmd = "settings put secure enabled_accessibility_services ${Shell.quote(merged)} && settings put secure accessibility_enabled 1"
                if (rootRun(cmd)) {
                    toast("Служба включена через root")
                    delay(800)
                } else {
                    toast("Найдите «Relay» в списке и включите. На Android 13+ сначала: О приложении → ⋮ → Разрешить ограниченные настройки")
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }
        refreshDevice()
    }

    private fun setSetting(key: String, value: Any) {
        when (key) {
            "approvalMode" -> prefs.approvalMode = value.toString()
            "rootMode" -> prefs.rootMode = value.toString()
            "autoPreamble" -> prefs.autoPreamble = value as? Boolean ?: value.toString().toBoolean()
            "maxSteps" -> prefs.maxSteps = value.toString().toIntOrNull() ?: 12
        }
        approvals.reset()
        emit(JSONObject().put("type", "settings").put("settings", prefs.settingsJson()))
    }

    private fun clearSiteData() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        sessions.values.forEach { it.webView.clearCache(true); it.webView.reload() }
        toast("Вы вышли со всех сайтов")
    }

    private fun copy(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Relay", text))
        toast("Скопировано")
    }

    // endregion

    // region UI channel

    fun emit(obj: JSONObject) {
        val js = "window.relayUI&&window.relayUI.receive($obj)"
        if (Looper.myLooper() == Looper.getMainLooper()) ui.evaluateJavascript(js, null) else ui.post { ui.evaluateJavascript(js, null) }
    }

    private fun emitError(message: String) = emit(JSONObject().put("type", "error").put("message", message))
    private fun emitInfo(text: String) = emit(JSONObject().put("type", "info").put("text", text))
    private fun toast(text: String) = emit(JSONObject().put("type", "toast").put("text", text))

    // endregion

    fun destroy() {
        pendingApprovals.values.forEach { it.decision.complete(Decision.DENY) }
        runJob?.cancel()
        mcp?.stop()
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        scope.cancel()
    }

    companion object {
        private const val TAG = "Relay"
        private const val SOURCE_CHAT = "chat"
        private const val SOURCE_MCP = "mcp"
        private const val BUSY_TEXT = "Агент работает — дождитесь ответа или нажмите «Стоп»"
        private const val MCP_APPROVAL_TIMEOUT_MS = 5 * 60_000L
        private const val PREAMBLE_CARRY_MS = 30_000L
        private val CHAT_POLL_MS = longArrayOf(900, 2500, 6000, 10_000)
        private val PICK_HINTS = mapOf(
            "input" to "🎯 Нажмите на поле ввода сообщения",
            "send" to "🎯 Нажмите на кнопку «Отправить»",
            "response" to "🎯 Нажмите на любой ответ ИИ",
            "chatLink" to "🎯 Нажмите на любой чат в списке (откройте меню, если нужно)",
            "projectLink" to "🎯 Нажмите на любой проект",
            "busy" to "🎯 Нажмите на кнопку «Стоп» во время ответа",
        )
    }
}
