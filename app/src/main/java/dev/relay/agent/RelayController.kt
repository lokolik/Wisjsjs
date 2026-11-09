package dev.relay.agent

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import dev.relay.agent.agent.AgentListener
import dev.relay.agent.agent.AgentLoop
import dev.relay.agent.agent.ApprovalMode
import dev.relay.agent.agent.ApprovalPolicy
import dev.relay.agent.agent.Attachment
import dev.relay.agent.agent.ChatTarget
import dev.relay.agent.agent.Decision
import dev.relay.agent.agent.Mime
import dev.relay.agent.agent.ToolProtocol
import dev.relay.agent.apps.AiApps
import dev.relay.agent.files.FileTracker
import dev.relay.agent.mcp.McpHandler
import dev.relay.agent.mcp.McpServer
import dev.relay.agent.nativeapp.AppDriver
import dev.relay.agent.nativeapp.AppHints
import dev.relay.agent.nativeapp.NativeAppTarget
import dev.relay.agent.nativeapp.NativeHost
import dev.relay.agent.nativeapp.RelayAccessibilityService
import dev.relay.agent.site.SiteHost
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Connects the HTML UI with AI apps/sites, the agent loop, device tools, files and the MCP server. */
class RelayController(
    private val activity: MainActivity,
    private val ui: WebView,
    private val siteFrame: FrameLayout,
) : NativeHost, SiteHost {
    private val scope = MainScope()
    private val prefs = Prefs(activity)
    private val shell = Shell()
    private val platform = AndroidPlatform(activity.applicationContext)
    private val executor = ToolExecutor(platform, shell) { prefs.rootMode != "off" }
    private val approvals = ApprovalPolicy()
    private val coreJs: String by lazy { asset("inject/relay-core.js") }
    private val builtins: List<JSONObject> by lazy { JSONObject(asset("adapters.json")).getJSONArray("adapters").objects() }
    private val clipboard by lazy { activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val tracker = FileTracker({
        val r = platform.storageRoot
        listOf("Download", "Documents", "Pictures", "Movies", "Music").map { File(r, it) }
    })

    private val sessions = LinkedHashMap<String, SiteSession>()
    private var current: JSONObject? = null
    private var runJob: Job? = null
    private var appJob: Job? = null
    private var chatsJob: Job? = null
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Decision>>()
    private var mcp: McpServer? = null
    private var picking: String? = null
    private var siteShown = false

    /** Files in the composer, not yet sent. */
    private val staged = LinkedHashMap<String, Attachment>()

    /** Files for the next message of the running task (user attachments, then attach_file). */
    private val runQueue = ArrayList<Attachment>()

    /** App chat to open (or new chat) before the next message to an app. */
    private var appNav: AppDriver.Nav? = null
    private var appChatHref: String? = null

    @Volatile
    private var focused = false
    private val focusWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private val downloads = ConcurrentHashMap<Long, String>()
    private val carded = HashSet<String>()
    private var downloadReceiver: BroadcastReceiver? = null

    fun start() {
        if (prefs.mcpEnabled) setMcp(true)
        platform.fileSink = { f, source -> emitFiles(listOf(f), source) }
        platform.attachSink = { f ->
            if (runJob?.isActive != true) throw IllegalStateException("Прикреплять файлы можно только во время задачи в чате Relay")
            synchronized(runQueue) { runQueue += Attachment(f) }
            "Файл ${f.name} будет прикреплён к следующему сообщению в этот чат"
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloads.remove(id) != null) onDownloadDone(id)
            }
        }
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        downloadReceiver = receiver
        scope.launch(Dispatchers.IO) {
            // Old outgoing attachment copies.
            File(activity.cacheDir, "outbox").listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.deleteRecursively() }
        }
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
            "refreshChats" -> refreshChats()
            "openChat" -> openChat(m.getString("href"))
            "newChat" -> newChat()
            "send" -> send(m.getString("text"), m.optBoolean("agent", true), m.optJSONArray("attachments"))
            "stop" -> stop()
            "approve" -> pendingApprovals[m.getString("callId")]?.complete(
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
            "scanApps" -> emit(adaptersJson())
            "installApp" -> installApp(m.getString("package"))
            "calibrate" -> calibrate(m.getString("id"), m.optBoolean("test", true))
            "resetSetup" -> {
                prefs.resetProfile(m.getString("id"))
                emit(adaptersJson())
                current?.let { if (it.optString("id") == m.getString("id")) selectAdapter(it.optString("id")) }
            }
            "dumpScreen" -> dumpScreen()
            "setSetting" -> setSetting(m.getString("key"), m.get("value"))
            "perm" -> requestPermission(m.getString("which"))
            "autoSetup" -> autoSetupRoot()
            "checkRoot" -> checkRoot()
            "mcp" -> setMcp(m.getBoolean("enabled"))
            "rotateToken" -> {
                prefs.rotateMcpToken()
                if (mcp != null) setMcp(true) else emit(mcpJson())
            }
            "resendPreamble" -> {
                prefs.forgetPreamble(chatKey())
                toast("Инструкция для ИИ будет добавлена к следующему сообщению")
            }
            "pickFiles" -> pickFiles()
            "removeAttachment" -> {
                staged.remove(m.getString("id"))
                emitStaged()
            }
            "openFile" -> runCatching { platform.openFile(File(m.getString("path"))) }.onFailure { emitError(it.message ?: "Не удалось открыть файл") }
            "shareFile" -> shareFile(File(m.getString("path")))
            "finishOnboarding" -> prefs.onboarded = true
            "clearSites" -> clearSiteData()
            "copy" -> copy(m.getString("text"))
            "openExternal" -> runCatching { platform.openUrl(m.getString("url")) }
            "exit" -> activity.moveTaskToBack(true)
        }
    }

    // region adapters, installed apps

    private fun asset(path: String) = activity.assets.open(path).bufferedReader().use { it.readText() }

    private fun appPackages(a: JSONObject): List<String> {
        val out = ArrayList<String>()
        a.optJSONArray("packages")?.let { arr -> for (i in 0 until arr.length()) out += arr.optString(i) }
        a.optString("package").takeIf { it.isNotBlank() && it !in out }?.let { out += it }
        return out.filter { it.isNotBlank() }
    }

    private fun installedVersion(pkg: String): String? = try {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(pkg, 0).versionName ?: ""
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun allAdapters(): JSONArray {
        val byId = LinkedHashMap<String, JSONObject>()
        builtins.forEach { byId[it.optString("id")] = it.copy().put("builtin", true) }
        prefs.customAdapters.objects().forEach { c ->
            val id = c.optString("id")
            val base = byId[id]
            byId[id] = if (base != null) base.mergedWith(c).put("overridden", true) else c.copy().put("custom", true)
        }
        byId.values.filter { it.optString("kind") == "app" }.forEach { a ->
            val pkgs = appPackages(a)
            val hit = pkgs.firstNotNullOfOrNull { p -> installedVersion(p)?.let { p to it } }
            a.put("package", hit?.first ?: pkgs.firstOrNull() ?: "")
            a.put("installed", hit != null)
            a.put("version", hit?.second ?: "")
            val profile = prefs.profile(a.optString("id"))
            a.put("calibrated", profile?.has("calibratedAt") == true)
        }
        return JSONArray(byId.values.toList())
    }

    /** Installed apps that look like AI assistants but are not in the catalog. */
    private fun discovered(known: Set<String>): JSONArray {
        val arr = JSONArray()
        AndroidPlatform.launcherApps(activity)
            .filter { (pkg, label) -> pkg !in known && AiApps.looksLikeAi(label, pkg) }
            .forEach { (pkg, label) -> arr.put(JSONObject().put("package", pkg).put("label", label)) }
        return arr
    }

    private suspend fun adaptersJson(): JSONObject {
        val adapters = withContext(Dispatchers.IO) { allAdapters() }
        val known = adapters.objects().flatMap { appPackages(it) }.toSet()
        val disc = withContext(Dispatchers.IO) { discovered(known) }
        return JSONObject().put("type", "adapters").put("adapters", adapters).put("discovered", disc)
    }

    private fun findAdapter(id: String): JSONObject? = allAdapters().objects().firstOrNull { it.optString("id") == id }

    private fun currentSession(): SiteSession? = current?.let { sessions[it.optString("id")] }

    private fun isApp(a: JSONObject?) = a?.optString("kind") == "app"

    private fun appState(a: JSONObject): String = when {
        !a.optBoolean("installed") -> "notInstalled"
        !accessibilityEnabled() -> "needsAccessibility"
        prefs.profile(a.optString("id"))?.has("calibratedAt") != true -> "needsSetup"
        else -> "ready"
    }

    private fun selectAdapter(id: String) {
        val a = findAdapter(id) ?: return emitError("Сервис «$id» не найден")
        val changed = current?.optString("id") != id
        current = a
        prefs.lastAdapter = id
        if (isApp(a)) {
            if (changed) {
                appNav = null
                appChatHref = null
            }
            sessions.values.forEach { it.webView.visibility = View.INVISIBLE }
            emit(sessionJson(id, appState(a), appChatHref ?: appHome(a), a.optString("name")))
            emitAppChats(a)
            return
        }
        val s = sessions.getOrPut(id) {
            SiteSession(activity, a, coreJs, scope, this, ::onSiteEvent).also {
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

    private fun appHome(a: JSONObject) = "app:" + a.optString("package")

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
            "pageLoaded" -> if (s === currentSession()) {
                scope.launch {
                    refreshStatus(s)
                    emit(JSONObject().put("type", "url").put("adapterId", s.id).put("url", ev.optString("url")))
                }
                scheduleChats(s)
            }
            "urlChanged" -> if (s === currentSession()) {
                emit(JSONObject().put("type", "url").put("adapterId", s.id).put("url", ev.optString("url")))
                scheduleChats(s)
            }
            "picked", "pickCancelled" -> {
                picking = null
                showSite(false)
                emit(ev)
            }
            "log" -> Log.d(TAG, "site ${s.id}: ${ev.optString("message")}")
        }
    }

    private fun scheduleChats(s: SiteSession) {
        chatsJob?.cancel()
        chatsJob = scope.launch {
            for (d in longArrayOf(900, 2500, 6000)) {
                delay(d)
                if (s !== currentSession()) return@launch
                emitChats(s)
                refreshStatus(s)
            }
        }
    }

    private suspend fun emitChats(s: SiteSession) {
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
    }

    private fun emitAppChats(a: JSONObject) {
        val home = appHome(a)
        val items = JSONArray().put(JSONObject().put("title", "Открытый чат в приложении").put("href", home).put("kind", "chat"))
        val profile = prefs.profile(a.optString("id"))
        profile?.optJSONArray("chats")?.objects()?.forEach { c ->
            items.put(JSONObject().put("title", c.optString("title")).put("href", "$home#chat:${c.optString("title")}").put("kind", c.optString("kind", "chat")))
        }
        emit(
            JSONObject().put("type", "chats").put("adapterId", a.optString("id")).put("items", items)
                .put("updatedAt", profile?.optLong("chatsAt") ?: 0),
        )
    }

    private fun refreshChats() {
        val a = current ?: return
        if (!isApp(a)) {
            currentSession()?.let { scope.launch { emitChats(it) } }
            return
        }
        if (!checkAppReady(a)) return
        if (busyWithApp()) return
        toast("Открываю ${a.optString("name")} и читаю список чатов…")
        appJob = scope.launch {
            val d = driverFor(a)
            try {
                val chats = d.readChats()
                prefs.updateProfile(a.optString("id")) { p ->
                    p.put("hints", d.hints.toJson())
                    p.put("chats", JSONArray(chats.map { JSONObject().put("title", it.title).put("kind", it.kind) }))
                    p.put("chatsAt", System.currentTimeMillis())
                }
                returnToRelay()
                emitAppChats(a)
                toast(if (chats.isEmpty()) "Список чатов не найден в приложении" else "Найдено чатов: ${chats.size}")
            } catch (e: CancellationException) {
                withContext(NonCancellable) { returnToRelay() }
            } catch (e: Exception) {
                returnToRelay()
                emitError(e.message ?: e.toString())
            }
        }
    }

    private fun openChat(href: String) {
        val a = current ?: return
        if (isApp(a)) {
            val title = href.substringAfter("#chat:", "")
            appNav = if (title.isNotEmpty()) AppDriver.Nav.OpenChat(title) else null
            appChatHref = href
            emit(JSONObject().put("type", "url").put("adapterId", a.optString("id")).put("url", href))
            return
        }
        val s = currentSession() ?: return
        s.navigate(href)
        emit(JSONObject().put("type", "url").put("adapterId", s.id).put("url", href))
    }

    private fun newChat() {
        val a = current ?: return
        if (isApp(a)) {
            appNav = AppDriver.Nav.NewChat
            appChatHref = appHome(a) + "#new"
            prefs.forgetPreamble(chatKey())
            emit(JSONObject().put("type", "url").put("adapterId", a.optString("id")).put("url", appChatHref))
            return
        }
        val s = currentSession() ?: return
        val url = a.optString("newChatUrl").ifBlank { a.optString("url") }
        s.navigate(url)
        emit(JSONObject().put("type", "url").put("adapterId", s.id).put("url", url))
    }

    private suspend fun diagnose(selectors: JSONObject?) {
        val s = currentSession() ?: return emitError("Сначала выберите этот сайт")
        emit(s.diagnose(selectors).put("type", "diagnose"))
    }

    private suspend fun saveAdapter(adapter: JSONObject) {
        val id = adapter.optString("id")
        if (id.isBlank()) return emitError("У сервиса нет id")
        listOf("installed", "overridden", "custom", "builtin", "version", "calibrated").forEach { adapter.remove(it) }
        val list = prefs.customAdapters.objects().filter { it.optString("id") != id }.toMutableList()
        list += adapter
        prefs.customAdapters = JSONArray(list)
        val merged = findAdapter(id) ?: return
        sessions[id]?.updateAdapter(merged)
        if (current?.optString("id") == id) current = merged
        emit(adaptersJson())
        toast("Сохранено")
    }

    private suspend fun deleteAdapter(id: String) {
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
            prefs.resetProfile(id)
            if (current?.optString("id") == id) current = null
        }
        emit(adaptersJson())
        toast(if (isBuiltin) "Настройки сброшены" else "Удалено")
    }

    private suspend fun listApps() {
        val apps = withContext(Dispatchers.IO) { AndroidPlatform.launcherApps(activity) }
        val arr = JSONArray()
        apps.forEach { (pkg, label) -> arr.put(JSONObject().put("package", pkg).put("label", label)) }
        emit(JSONObject().put("type", "apps").put("items", arr))
    }

    private fun installApp(pkg: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(market)
        } catch (e: Exception) {
            platform.openUrl("https://play.google.com/store/apps/details?id=$pkg")
        }
    }

    // endregion

    // region apps: driver, auto-setup

    private fun driverFor(a: JSONObject): AppDriver {
        val profile = prefs.profile(a.optString("id"))
        val entry = a.optString("entry").takeIf { it.isNotBlank() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        return AppDriver(
            activity.applicationContext, a.optString("package"), a.optString("name"),
            AppHints.fromJson(profile?.optJSONObject("hints")), entry,
        ) { Log.d(TAG, "app ${a.optString("id")}: $it") }
    }

    private fun checkAppReady(a: JSONObject): Boolean {
        if (!a.optBoolean("installed")) {
            emitError("${a.optString("name")} не установлено на телефоне. Нажмите «Установить».")
            return false
        }
        if (!accessibilityEnabled()) {
            emitError("Включите службу Relay в спец. возможностях: без неё Relay не может нажимать кнопки в ${a.optString("name")}.")
            return false
        }
        return true
    }

    private fun busyWithApp(): Boolean {
        if (runJob?.isActive == true || appJob?.isActive == true) {
            emitError("Relay уже работает с приложением — дождитесь окончания или нажмите «Стоп».")
            return true
        }
        return false
    }

    private fun calibrate(id: String, test: Boolean) {
        val a = findAdapter(id) ?: return emitError("Сервис «$id» не найден")
        fun done(ok: Boolean, message: String) =
            emit(JSONObject().put("type", "calibDone").put("adapterId", id).put("ok", ok).put("message", message))
        if (!checkAppReady(a)) return done(false, "Приложение не готово")
        if (busyWithApp()) return done(false, "Relay занят")
        appJob = scope.launch {
            val d = driverFor(a)
            try {
                val res = d.calibrate(test) { key, status, detail ->
                    emit(JSONObject().put("type", "calib").put("adapterId", id).put("step", key).put("status", status).put("detail", detail))
                }
                returnToRelay()
                val opened = res.report.optJSONObject("open")?.optString("status") == "done"
                prefs.updateProfile(id) { p ->
                    p.put("hints", res.hints.toJson())
                    p.put("report", res.report)
                    if (res.chats.isNotEmpty()) {
                        p.put("chats", JSONArray(res.chats.map { JSONObject().put("title", it.title).put("kind", it.kind) }))
                        p.put("chatsAt", System.currentTimeMillis())
                    }
                    if (opened) p.put("calibratedAt", System.currentTimeMillis())
                }
                emit(adaptersJson())
                if (current?.optString("id") == id) selectAdapter(id)
                done(opened && res.ok, if (opened && res.ok) "${a.optString("name")} настроено" else "Настройка завершена с ошибками")
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    returnToRelay()
                    done(false, "Остановлено")
                }
            } catch (e: Exception) {
                returnToRelay()
                done(false, e.message ?: e.toString())
            }
        }
    }

    private fun dumpScreen() {
        val a = current
        if (a == null || !isApp(a)) return emitError("Выберите приложение, чей экран нужно сохранить")
        if (!checkAppReady(a) || busyWithApp()) return
        appJob = scope.launch {
            try {
                val d = driverFor(a)
                d.launch()
                delay(3000)
                val json = d.dump()
                returnToRelay()
                val file = withContext(Dispatchers.IO) {
                    val dir = File(platform.storageRoot, "Download").apply { mkdirs() }
                    ToolExecutor.unique(dir, "relay-screen-${a.optString("id")}-${System.currentTimeMillis() / 1000}.json").also {
                        it.writeText(json.toString(1))
                    }
                }
                emitFiles(listOf(file), "dump")
                toast("Снимок экрана сохранён в Загрузки")
            } catch (e: Exception) {
                returnToRelay()
                emitError(e.message ?: e.toString())
            }
        }
    }

    // endregion

    // region site visibility & picker

    private fun showSite(visible: Boolean, hint: String? = null) {
        val a = current
        if (visible && isApp(a)) {
            if (a!!.optBoolean("installed")) {
                runCatching { platform.launchApp(a.optString("package")) }.onFailure { emitError(it.message ?: "Не удалось открыть приложение") }
            } else {
                installApp(a.optString("package"))
            }
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

    private fun chatKey(): String {
        val a = current ?: return "none"
        val id = a.optString("id")
        if (isApp(a)) return "$id|" + (appChatHref ?: appHome(a))
        return "$id|" + normalizeUrl(sessions[id]?.currentUrl ?: a.optString("url"))
    }

    /** A brand-new chat always needs the tool instructions, whatever was sent from that URL before. */
    private fun isFreshChat(): Boolean {
        val a = current ?: return false
        if (isApp(a)) return appNav == AppDriver.Nav.NewChat
        if (a.optBoolean("singleThread")) return false
        val url = normalizeUrl(sessions[a.optString("id")]?.currentUrl ?: return false)
        return url == normalizeUrl(a.optString("newChatUrl").ifBlank { a.optString("url") }) || url == normalizeUrl(a.optString("url"))
    }

    private fun preambleContext() = ToolProtocol.Context(
        storageRoot = platform.storageRoot.path,
        rootAvailable = prefs.rootMode != "off" && (shell.cachedRoot ?: shell.suPresent()),
        device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
    )

    private fun send(text: String, agent: Boolean, attachmentIds: JSONArray?) {
        if (runJob?.isActive == true || appJob?.isActive == true) return emitError("Агент уже работает — дождитесь ответа или нажмите «Стоп».")
        val a = current ?: return emitError("Сначала выберите ИИ-сервис")
        var driver: AppDriver? = null
        val target: ChatTarget = if (isApp(a)) {
            if (!checkAppReady(a)) return
            val d = driverFor(a)
            driver = d
            NativeAppTarget(
                driver = d,
                host = this,
                shareMode = a.optString("share", "auto"),
                autoDownload = { prefs.autoDownload },
                takeNav = { appNav.also { appNav = null } },
                onFilesTapped = { names -> emitInfo("Скачиваю из ${a.optString("name")}: ${names.joinToString()}") },
            )
        } else {
            sessions[a.optString("id")] ?: return emitError("Сайт ещё не загружен")
        }
        val atts = (0 until (attachmentIds?.length() ?: 0)).mapNotNull { staged.remove(attachmentIds!!.optString(it)) }
        emitStaged()
        synchronized(runQueue) {
            runQueue.clear()
            runQueue += atts
        }

        val fresh = isFreshChat()
        val keyBefore = chatKey()
        val withPreamble = agent && prefs.autoPreamble && (fresh || !prefs.preambleSent(keyBefore))
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

            override suspend fun requestApproval(call: ToolCall, risk: Risk): Decision = askApproval(call, risk, "chat")
        }

        runJob = scope.launch {
            emit(JSONObject().put("type", "turn").put("state", "start").put("preamble", withPreamble))
            withContext(Dispatchers.IO) { tracker.start() }
            try {
                AgentLoop(
                    target = target,
                    execute = { c -> withContext(Dispatchers.IO) { executor.execute(c) } },
                    riskOf = ToolExecutor::riskOf,
                    listener = listener,
                    approvals = approvals,
                    maxSteps = prefs.maxSteps,
                    takeAttachments = { synchronized(runQueue) { runQueue.toList().also { runQueue.clear() } } },
                    stepExtras = { newFilesNote() },
                ).run(first, agent)
            } catch (e: CancellationException) {
                emitInfo("Остановлено")
            } catch (e: Exception) {
                Log.w(TAG, "agent failed", e)
                emitError(e.message ?: e.toString())
            } finally {
                withContext(NonCancellable) {
                    if (withPreamble && delivered) {
                        prefs.markPreamble(keyBefore)
                        prefs.markPreamble(chatKey())
                    }
                    driver?.let { d -> prefs.updateProfile(a.optString("id")) { it.put("hints", d.hints.toJson()) } }
                    pendingApprovals.values.forEach { it.complete(Decision.DENY) }
                    newFilesNote()
                    emit(JSONObject().put("type", "turn").put("state", "end"))
                }
            }
            // Downloads started by the AI app may finish a little later.
            for (wait in longArrayOf(4000, 8000)) {
                delay(wait)
                if (runJob?.isActive == true) break
                newFilesNote()
            }
        }
    }

    /** Emits cards for files that appeared during the task and describes them for the AI. */
    private suspend fun newFilesNote(): String? {
        val files = withContext(Dispatchers.IO) { tracker.collect { executor.touched.contains(it) } }
        if (files.isEmpty()) return null
        emitFiles(files, "new")
        return "Новые файлы на устройстве (появились во время задачи):\n" +
            files.joinToString("\n") { "• ${it.path} (${ToolExecutor.human(it.length())})" }
    }

    private fun stop() {
        pendingApprovals.values.forEach { it.complete(Decision.DENY) }
        runJob?.cancel()
        appJob?.cancel()
    }

    private fun approvalMode() = when (prefs.approvalMode) {
        "all" -> ApprovalMode.AUTO_ALL
        "read" -> ApprovalMode.AUTO_READ
        else -> ApprovalMode.ASK
    }

    private suspend fun askApproval(call: ToolCall, risk: Risk, source: String): Decision {
        val d = CompletableDeferred<Decision>()
        pendingApprovals[call.id] = d
        emit(
            JSONObject().put("type", "approval").put("source", source).put("risk", risk.name)
                .put("call", callJson(call)),
        )
        return try {
            d.await()
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

    // region files: attachments, downloads, cards

    private fun emitStaged() {
        val arr = JSONArray()
        staged.values.forEach { arr.put(attachmentJson(it)) }
        emit(JSONObject().put("type", "attachments").put("items", arr))
    }

    private fun attachmentJson(a: Attachment) = JSONObject()
        .put("id", a.id).put("name", a.name).put("size", a.size).put("mime", a.mime)

    private fun emitFiles(files: List<File>, source: String) {
        val fresh = synchronized(carded) { files.filter { carded.add(it.path) } }
        if (fresh.isEmpty()) return
        val arr = JSONArray()
        fresh.forEach { f ->
            arr.put(JSONObject().put("path", f.path).put("name", f.name).put("size", f.length()).put("mime", Mime.of(f.name)))
        }
        emit(JSONObject().put("type", "files").put("source", source).put("items", arr))
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        activity.startForResult(intent) { code, data -> if (code == Activity.RESULT_OK) importUris(urisFrom(data)) }
    }

    private fun urisFrom(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val out = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let(out::add) }
        if (out.isEmpty()) data.data?.let(out::add)
        return out
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            for (u in uris) {
                try {
                    importUri(u)?.let { staged[it.id] = it }
                } catch (e: Exception) {
                    emitError(e.message ?: "Не удалось прочитать файл")
                }
            }
            emitStaged()
        }
    }

    /** Copies a picked/shared document into the app cache so it can be re-shared to an AI app. */
    private suspend fun importUri(uri: Uri): Attachment? = withContext(Dispatchers.IO) {
        val cr = activity.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        if (size > ToolExecutor.MAX_ATTACH) throw IllegalStateException("«$name» больше ${ToolExecutor.human(ToolExecutor.MAX_ATTACH)}")
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120)
        val dir = File(activity.cacheDir, "outbox/" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        val f = File(dir, safe)
        val input = cr.openInputStream(uri) ?: return@withContext null
        input.use { inp -> f.outputStream().use { inp.copyTo(it) } }
        Attachment(f, safe, cr.getType(uri)?.takeIf { it != "application/octet-stream" } ?: Mime.of(safe))
    }

    fun onShareIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList() ?: emptyList()
            else -> emptyList()
        }
        importUris(uris)
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
            emit(JSONObject().put("type", "compose").put("text", it))
        }
        if (uris.isNotEmpty()) toast("Файлы добавлены к сообщению")
    }

    private fun shareFile(f: File) {
        if (!f.isFile) return emitError("Файл не найден: ${f.path}")
        val uri = AndroidPlatform.uriFor(activity, f)
        val send = Intent(Intent.ACTION_SEND).setType(Mime.of(f.name)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(f.name, uri)
        activity.startActivity(Intent.createChooser(send, f.name))
    }

    override val autoDownload: Boolean get() = prefs.autoDownload

    override fun chooseFiles(cb: (Array<Uri>?) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        activity.startForResult(intent) { code, data -> cb(if (code == Activity.RESULT_OK) urisFrom(data).toTypedArray() else null) }
    }

    override fun download(url: String, userAgent: String, disposition: String?, mime: String?) {
        val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            mime?.let { req.setMimeType(it) }
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloads[dm.enqueue(req)] = name
            toast("Скачиваю $name")
        } catch (e: Exception) {
            emitError("Не удалось скачать $name: ${e.message}")
        }
    }

    private fun onDownloadDone(id: Long) {
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            if (status == DownloadManager.STATUS_SUCCESSFUL && local != null) {
                Uri.parse(local).path?.let { emitFiles(listOf(File(it)), "download") }
            } else {
                emitError("Скачивание не удалось")
            }
        }
    }

    override fun saveDataUrl(name: String, dataUrl: String) {
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val comma = dataUrl.indexOf(',')
                    require(comma > 0) { "пустые данные" }
                    val meta = dataUrl.substring(0, comma)
                    val payload = dataUrl.substring(comma + 1)
                    val bytes = if (meta.contains(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
                    var fileName = name.ifBlank { "download" }
                    if (!fileName.contains('.')) Mime.ext(meta.removePrefix("data:").substringBefore(';'))?.let { fileName += ".$it" }
                    val dir = File(platform.storageRoot, "Download").apply { mkdirs() }
                    ToolExecutor.unique(dir, fileName).also { it.writeBytes(bytes) }
                }
                MediaScannerConnection.scanFile(activity, arrayOf(file.path), null, null)
                emitFiles(listOf(file), "download")
            } catch (e: Exception) {
                emitError("Не удалось сохранить файл: ${e.message}")
            }
        }
    }

    // endregion

    // region NativeHost

    fun onFocus(has: Boolean) {
        focused = has
        if (has) {
            focusWaiters.forEach { it.complete(Unit) }
            focusWaiters.clear()
        }
    }

    override suspend fun returnToRelay(): Boolean = withContext(Dispatchers.Main) {
        if (focused) return@withContext true
        val svc = RelayAccessibilityService.instance
        if (svc != null) {
            svc.bringToFront(MainActivity::class.java)
        } else {
            runCatching {
                activity.startActivity(
                    Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
        val d = CompletableDeferred<Unit>()
        focusWaiters += d
        withTimeoutOrNull(4000) { d.await() } != null || focused
    }

    override fun canReadClipboard(): Boolean = focused

    override suspend fun readClipboard(): String? = withContext(Dispatchers.Main) {
        if (!focused) return@withContext null
        runCatching { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(activity)?.toString() }.getOrNull()
    }

    override suspend fun writeClipboard(text: String?) = withContext(Dispatchers.Main) {
        runCatching {
            if (text == null) {
                if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", text))
            }
        }
        Unit
    }

    override fun shareIntent(pkg: String, attachments: List<Attachment>): Intent? {
        val uris = attachments.map { AndroidPlatform.uriFor(activity, it.file) }
        val types = attachments.map { it.mime }.distinct()
        val type = when {
            types.size == 1 -> types[0]
            types.all { it.startsWith("image/") } -> "image/*"
            else -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        val clip = ClipData.newRawUri(attachments[0].name, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.setPackage(pkg)
        val targets = activity.packageManager.queryIntentActivities(intent, 0)
        val target = targets.firstOrNull() ?: return null
        return intent.setClassName(target.activityInfo.packageName, target.activityInfo.name)
    }

    // endregion

    // region MCP

    private fun setMcp(enabled: Boolean) {
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
            when (askApproval(call, risk, "mcp")) {
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

    private suspend fun initJson(): JSONObject {
        val adapters = adaptersJson()
        return JSONObject()
            .put("type", "init")
            .put("adapters", adapters.getJSONArray("adapters"))
            .put("discovered", adapters.getJSONArray("discovered"))
            .put("settings", prefs.settingsJson())
            .put("device", deviceJson())
            .put("mcp", mcpJson())
            .put("onboarded", prefs.onboarded)
            .put("lastAdapter", prefs.lastAdapter ?: JSONObject.NULL)
            .put("attachments", JSONArray().also { arr -> staged.values.forEach { arr.put(attachmentJson(it)) } })
            .put("version", runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "")
    }

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
        scope.launch {
            emit(adaptersJson())
            val a = current ?: return@launch
            if (isApp(a)) findAdapter(a.optString("id"))?.let { fresh ->
                current = fresh
                emit(sessionJson(fresh.optString("id"), appState(fresh), appChatHref ?: appHome(fresh), fresh.optString("name")))
            }
        }
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

    private suspend fun grantStorageRoot(): Boolean {
        val pkg = activity.packageName
        val cmd = if (Build.VERSION.SDK_INT >= 30) {
            "appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow"
        } else {
            "pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE; pm grant $pkg android.permission.READ_EXTERNAL_STORAGE"
        }
        return rootRun(cmd) && hasStorage()
    }

    private suspend fun enableA11yRoot(): Boolean {
        val comp = ComponentName(activity, RelayAccessibilityService::class.java).flattenToString()
        val existing = Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val merged = (existing.split(':').filter { it.isNotBlank() && !it.equals(comp, true) } + comp).joinToString(":")
        val ok = rootRun("settings put secure enabled_accessibility_services ${Shell.quote(merged)} && settings put secure accessibility_enabled 1")
        if (ok) delay(1200)
        return ok
    }

    /** One tap with root: file access, accessibility service and no battery restrictions. */
    private suspend fun autoSetupRoot() {
        if (!shell.suPresent() || !withContext(Dispatchers.IO) { shell.hasRoot(refresh = true) }) {
            emit(deviceJson())
            return emitError("Root не выдан. Разрешите Relay в Magisk/KernelSU или выдайте разрешения вручную.")
        }
        val storage = hasStorage() || grantStorageRoot()
        val a11y = accessibilityEnabled() || enableA11yRoot()
        rootRun("dumpsys deviceidle whitelist +${activity.packageName}")
        refreshDevice()
        toast(if (storage && a11y) "Всё настроено через root" else "Часть разрешений не выдалась — выдайте их вручную")
    }

    /** With root the permission is granted silently; otherwise the system settings screen opens. */
    private suspend fun requestPermission(which: String) {
        val pkg = activity.packageName
        when (which) {
            "storage" -> {
                if (hasStorage()) return toast("Доступ к файлам уже есть")
                if (grantStorageRoot()) {
                    toast("Доступ к файлам выдан через root")
                } else if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$pkg"))
                    runCatching { activity.startActivity(intent) }
                        .onFailure { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                } else {
                    activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), 1)
                }
            }
            "accessibility" -> {
                if (accessibilityEnabled()) return toast("Служба уже включена")
                if (enableA11yRoot()) {
                    toast("Служба включена через root")
                } else {
                    toast("Найдите «Relay» в списке и включите. Если переключатель серый — сначала «Разрешить ограниченные настройки»")
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            "appinfo" -> {
                toast("Нажмите ⋮ в правом верхнем углу → «Разрешить ограниченные настройки»")
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
            }
        }
        refreshDevice()
    }

    private fun setSetting(key: String, value: Any) {
        when (key) {
            "approvalMode" -> prefs.approvalMode = value.toString()
            "rootMode" -> prefs.rootMode = value.toString()
            "autoPreamble" -> prefs.autoPreamble = value as? Boolean ?: value.toString().toBoolean()
            "autoDownload" -> prefs.autoDownload = value as? Boolean ?: value.toString().toBoolean()
            "maxSteps" -> prefs.maxSteps = value.toString().toIntOrNull() ?: 12
        }
        approvals.reset()
        emit(JSONObject().put("type", "settings").put("settings", prefs.settingsJson()))
    }

    private fun clearSiteData() {
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        sessions.values.forEach {
            it.webView.clearCache(true)
            it.webView.reload()
        }
        toast("Вы вышли со всех сайтов")
    }

    private fun copy(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("Relay", text))
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
        runJob?.cancel()
        appJob?.cancel()
        mcp?.stop()
        downloadReceiver?.let { runCatching { activity.unregisterReceiver(it) } }
        sessions.values.forEach { it.destroy() }
        sessions.clear()
        scope.cancel()
    }

    companion object {
        private const val TAG = "Relay"
        private val PICK_HINTS = mapOf(
            "input" to "🎯 Нажмите на поле ввода сообщения",
            "send" to "🎯 Нажмите на кнопку «Отправить»",
            "response" to "🎯 Нажмите на любую видимую часть ответа ИИ",
            "chatLink" to "🎯 Нажмите на любой чат в списке (откройте меню, если нужно)",
            "projectLink" to "🎯 Нажмите на любой проект",
            "busy" to "🎯 Нажмите на кнопку «Стоп» во время ответа",
        )
    }
}
