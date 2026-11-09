package dev.relay.agent.nativeapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DriverException(message: String) : Exception(message)

/** Drives one installed AI app through [RelayAccessibilityService] using [ScreenAnalyzer] heuristics. */
class AppDriver(
    private val context: Context,
    val pkg: String,
    val appName: String,
    var hints: AppHints,
    private val entry: Regex? = null,
    private val log: (String) -> Unit = {},
) {
    sealed class Nav {
        data class OpenChat(val title: String) : Nav()
        data object NewChat : Nav()
    }

    class Reply(val lines: List<String>, val timedOut: Boolean, val copied: Boolean, val filesTapped: List<String>)

    private fun now() = SystemClock.uptimeMillis()

    private fun service(): RelayAccessibilityService = RelayAccessibilityService.instance
        ?: throw DriverException("Служба Relay в спец. возможностях выключена. Настройки Relay → Разрешения → Спец. возможности.")

    suspend fun snap(): LiveTree? = withContext(Dispatchers.Default) { service().capture(pkg) }

    private fun launchIntent(clear: Boolean = false): Intent {
        val i = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw DriverException("Приложение $appName не установлено")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (clear) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return i
    }

    private suspend fun start(intent: Intent) = withContext(Dispatchers.Main) { context.startActivity(intent) }

    suspend fun launch() = start(launchIntent())

    // region primitives

    suspend fun click(t: LiveTree, n: UiNode): Boolean {
        var cur: UiNode? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.clickable && cur.index >= 0 && t.perform(cur, AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
            depth++
        }
        return service().tap(n.bounds.cx.toFloat(), n.bounds.cy.toFloat())
    }

    private suspend fun findInputNow(): Pair<LiveTree, UiNode>? {
        val t = snap() ?: return null
        val i = ScreenAnalyzer.findInput(t.root, hints) ?: return null
        return t to i
    }

    private suspend fun inputHas(text: String): Boolean {
        val (_, i) = findInputNow() ?: return false
        val cur = ScreenAnalyzer.norm(i.text.orEmpty())
        val want = ScreenAnalyzer.norm(text)
        return if (want.isEmpty()) cur.isEmpty() else cur.startsWith(want.take(24))
    }

    suspend fun setText(t: LiveTree, input: UiNode, text: String): Boolean {
        t.perform(input, AccessibilityNodeInfo.ACTION_FOCUS)
        t.perform(input, AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        t.perform(input, AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(300)
        if (inputHas(text)) return true
        if (text.isEmpty()) return false
        // Some editors ignore SET_TEXT: paste instead (the caller restores the clipboard).
        log("set text failed, pasting")
        service().setClipboard(text)
        val (t2, in2) = findInputNow() ?: return false
        t2.perform(in2, AccessibilityNodeInfo.ACTION_FOCUS)
        t2.perform(in2, AccessibilityNodeInfo.ACTION_PASTE)
        delay(400)
        return inputHas(text)
    }

    /** Accessibility scroll first; falls back to a slow swipe that any list understands. */
    suspend fun scroll(t: LiveTree, list: UiNode, up: Boolean, gestureOnly: Boolean = false): Boolean {
        if (!gestureOnly && t.scroll(list, up)) return true
        val b = list.bounds
        val x = b.cx.toFloat()
        val top = b.t + b.h * 0.3f
        val bottom = b.t + b.h * 0.7f
        return if (up) service().swipe(x, top, x, bottom, 450) else service().swipe(x, bottom, x, top, 450)
    }

    private suspend fun imeEnter(t: LiveTree, input: UiNode): Boolean =
        Build.VERSION.SDK_INT >= 30 && t.perform(input, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)

    // endregion

    // region opening the app

    /** Opens the app (or a share intent into it) and waits for the chat input. */
    suspend fun openToInput(intent: Intent? = null, timeoutMs: Long = 15_000): Pair<LiveTree, UiNode> {
        start(intent ?: launchIntent())
        return waitInput(timeoutMs, allowRecovery = intent == null)
    }

    suspend fun waitInput(timeoutMs: Long = 8_000, allowRecovery: Boolean = false): Pair<LiveTree, UiNode> {
        val t0 = now()
        var triedEntry = false
        var relaunched = false
        while (now() - t0 < timeoutMs) {
            delay(350)
            val t = snap() ?: continue
            ScreenAnalyzer.findInput(t.root, hints)?.let { return t to it }
            val elapsed = now() - t0
            if (!triedEntry && elapsed > 2500) {
                ScreenAnalyzer.findEntry(t.root, hints, entry)?.let { e ->
                    log("entry: ${ScreenAnalyzer.shortLabel(e)}")
                    triedEntry = true
                    click(t, e)
                }
            }
            if (allowRecovery && !relaunched && elapsed > 7500) {
                // The app was left on some inner screen: restart it on its main (chat) screen.
                relaunched = true
                start(launchIntent(clear = true))
            }
        }
        val t = snap()
        if (t == null) throw DriverException("$appName не открылось. Разблокируйте экран и повторите.")
        if (ScreenAnalyzer.isLoginScreen(t.root)) throw DriverException("Войдите в аккаунт в приложении $appName и повторите.")
        throw DriverException("Не нашёл поле ввода в $appName. Откройте в приложении любой чат и повторите (или запустите автонастройку).")
    }

    // endregion

    // region sending

    suspend fun send(
        text: String,
        nav: Nav?,
        share: Intent?,
        waitSendMs: Long,
        autoDownload: Boolean,
        onPartial: (String) -> Unit,
    ): Reply {
        var (t, input) = openToInput(share)
        if (nav != null) {
            val ok = when (nav) {
                is Nav.OpenChat -> openChat(nav.title, alreadyOpen = true)
                Nav.NewChat -> newChat(alreadyOpen = true)
            }
            if (!ok) log("navigation failed: $nav")
            val r = waitInput()
            t = r.first
            input = r.second
        }
        val list0 = ScreenAnalyzer.findList(t.root, input, hints)
        val before = ScreenAnalyzer.lines(list0 ?: t.root, input).map { it.text }

        if (!setText(t, input, text)) throw DriverException("Не удалось вставить текст в поле ввода $appName")
        pressSend(text, waitSendMs)

        // Wait for the reply: text stops changing and no "Stop" button.
        val start = now()
        var last = ""
        var lastChange = now()
        var timedOut = true
        while (now() - start < REPLY_TIMEOUT) {
            delay(650)
            val s = snap() ?: continue
            val inp = ScreenAnalyzer.findInput(s.root, hints)
            val list = ScreenAnalyzer.findList(s.root, inp, hints)
            val lines = ScreenAnalyzer.lines(list ?: s.root, inp).map { it.text }
            val reply = ScreenAnalyzer.replyAfter(lines, text) ?: ScreenAnalyzer.diffNew(before, lines, text)
            val cur = reply.joinToString("\n").trim()
            val busy = ScreenAnalyzer.isBusy(s.root)
            val tNow = now()
            if (cur != last) {
                last = cur
                lastChange = tNow
                if (cur.isNotEmpty()) onPartial(cur)
            } else if (cur.isNotEmpty() && !busy && tNow - lastChange > STABLE_MS) {
                timedOut = false
                break
            }
        }

        var lines = fullRead(text, before)
        if (lines.isEmpty() && last.isNotEmpty()) lines = last.lines()
        val copied = tapCopy(text)
        val files = if (autoDownload && !timedOut) downloadFiles(text) else emptyList()
        return Reply(lines, timedOut, copied, files)
    }

    private suspend fun pressSend(text: String, waitSendMs: Long) {
        val deadline = now() + waitSendMs
        var attempts = 0
        while (true) {
            val s = snap()
            if (s != null) {
                val inp = ScreenAnalyzer.findInput(s.root, hints)
                val btn = ScreenAnalyzer.findSend(s.root, inp, hints)
                val pressed = when {
                    btn != null -> click(s, btn)
                    now() > deadline && inp != null -> imeEnter(s, inp)
                    else -> false
                }
                if (pressed) {
                    attempts++
                    delay(1200)
                    if (!inputHas(text)) return
                    if (attempts >= 2) throw DriverException("$appName не отправило сообщение (текст остался в поле ввода)")
                    continue
                }
                if (now() > deadline) throw DriverException("Не нашёл кнопку «Отправить» в $appName — запустите автонастройку.")
            }
            delay(400)
        }
    }

    /**
     * Reads the whole reply even when it does not fit on screen: scrolls up page by page until our
     * own message is visible, stitching overlapping pages, then scrolls back down.
     */
    private suspend fun fullRead(prompt: String, before: List<String>): List<String> {
        var t = snap() ?: return emptyList()
        var input = ScreenAnalyzer.findInput(t.root, hints)
        var list = ScreenAnalyzer.findList(t.root, input, hints)
        var page = ScreenAnalyzer.lines(list ?: t.root, input).map { it.text }
        var merged = page
        if (ScreenAnalyzer.promptIndex(merged, prompt) < 0 && list != null) {
            var scrolls = 0
            var gestureOnly = false
            while (scrolls < MAX_SCROLLS) {
                scroll(t, list!!, up = true, gestureOnly = gestureOnly)
                delay(500)
                val t2 = snap() ?: break
                val in2 = ScreenAnalyzer.findInput(t2.root, hints)
                val l2 = ScreenAnalyzer.findList(t2.root, in2, hints) ?: break
                val p2 = ScreenAnalyzer.lines(l2, in2).map { it.text }
                scrolls++
                if (p2 == page) {
                    if (!gestureOnly) {
                        gestureOnly = true
                        continue
                    }
                    break
                }
                if (!ScreenAnalyzer.overlapsTop(merged, p2) && ScreenAnalyzer.overlapsTop(p2, merged)) {
                    // The accessibility action scrolled the wrong way (reversed list): use swipes.
                    log("scroll direction reversed, switching to gestures")
                    gestureOnly = true
                    t = t2
                    list = l2
                    page = p2
                    continue
                }
                merged = ScreenAnalyzer.mergeUp(merged, p2)
                t = t2
                input = in2
                list = l2
                page = p2
                if (ScreenAnalyzer.promptIndex(p2, prompt) >= 0) break
            }
            log("read ${merged.size} lines after $scrolls scrolls")
            // Back to the newest message.
            var prev: List<String>? = null
            repeat(scrolls + 3) {
                val s = snap() ?: return@repeat
                val i = ScreenAnalyzer.findInput(s.root, hints)
                val l = ScreenAnalyzer.findList(s.root, i, hints) ?: return@repeat
                val p = ScreenAnalyzer.lines(l, i).map { it.text }
                if (p == prev) return@repeat
                prev = p
                scroll(s, l, up = false, gestureOnly = gestureOnly)
                delay(250)
            }
        }
        return ScreenAnalyzer.replyAfter(merged, prompt) ?: ScreenAnalyzer.diffNew(before, merged, prompt)
    }

    private suspend fun promptBottom(t: LiveTree, prompt: String): Int? {
        val input = ScreenAnalyzer.findInput(t.root, hints)
        val list = ScreenAnalyzer.findList(t.root, input, hints)
        val lines = ScreenAnalyzer.lines(list ?: t.root, input)
        val idx = ScreenAnalyzer.promptIndex(lines.map { it.text }, prompt)
        return if (idx >= 0) lines[idx].node.bounds.b else null
    }

    /** Taps the reply's own "Copy" button: the clipboard then holds the full markdown of the reply. */
    private suspend fun tapCopy(prompt: String): Boolean {
        val t = snap() ?: return false
        val input = ScreenAnalyzer.findInput(t.root, hints)
        val copy = ScreenAnalyzer.findCopy(t.root, promptBottom(t, prompt), input) ?: return false
        val ok = click(t, copy)
        delay(400)
        return ok
    }

    /** Taps file attachments in the reply and their "Download" buttons; new files are picked up by FileTracker. */
    private suspend fun downloadFiles(prompt: String): List<String> {
        val t = snap() ?: return emptyList()
        val input = ScreenAnalyzer.findInput(t.root, hints)
        val chips = ScreenAnalyzer.fileChips(t.root, promptBottom(t, prompt), input).take(4)
        val done = mutableListOf<String>()
        for (chip in chips) {
            val s = snap() ?: break
            val target = s.root.preorder().firstOrNull { it.bounds == chip.target.bounds && it.clickable } ?: continue
            log("file chip: ${chip.name.ifEmpty { "download" }}")
            click(s, target)
            delay(1500)
            var after = snap() ?: continue
            if (chip.name.isNotEmpty()) {
                ScreenAnalyzer.findDownloadButton(after.root)?.let {
                    click(after, it)
                    delay(1500)
                    after = snap() ?: after
                }
            }
            done += chip.name.ifEmpty { "файл" }
            // Return to the chat if a preview or sheet opened.
            var backs = 0
            while (backs < 3 && ScreenAnalyzer.findInput(after.root, hints) == null) {
                service().back()
                backs++
                delay(700)
                after = snap() ?: break
            }
        }
        return done
    }

    // endregion

    // region chats & navigation

    private suspend fun openMenu(t: LiveTree): LiveTree? {
        val menu = ScreenAnalyzer.findMenu(t.root, hints)
        if (menu != null) {
            click(t, menu)
            hints = hints.copy(menuId = menu.viewId ?: hints.menuId, menuDesc = ScreenAnalyzer.shortLabel(menu).ifBlank { null } ?: hints.menuDesc)
        } else {
            // No menu button found: swipe the navigation drawer in from the left edge.
            val b = t.root.bounds
            service().swipe(b.l + 4f, b.cy.toFloat(), b.l + b.w * 0.75f, b.cy.toFloat(), 300)
        }
        delay(900)
        return snap()
    }

    private suspend fun closeMenu() {
        service().back()
        delay(600)
        if (findInputNow() == null && service().foregroundPackage() != pkg) start(launchIntent())
    }

    suspend fun readChats(): List<ChatItem> {
        val (t0, _) = openToInput()
        var after = openMenu(t0) ?: return emptyList()
        val items = LinkedHashMap<String, ChatItem>()
        ScreenAnalyzer.chatItems(t0.root, after.root).forEach { items.putIfAbsent(ScreenAnalyzer.norm(it.title), it) }
        if (items.isEmpty()) {
            closeMenu()
            return emptyList()
        }
        val drawer = ScreenAnalyzer.drawerList(t0.root, after.root)
        if (drawer != null) {
            for (i in 0 until 4) {
                val d = ScreenAnalyzer.drawerList(t0.root, after.root) ?: break
                if (!scroll(after, d, up = false)) break
                delay(500)
                after = snap() ?: break
                val more = ScreenAnalyzer.chatItems(t0.root, after.root).filter { ScreenAnalyzer.norm(it.title) !in items }
                if (more.isEmpty()) break
                more.forEach { items[ScreenAnalyzer.norm(it.title)] = it }
            }
        }
        closeMenu()
        return items.values.toList()
    }

    suspend fun openChat(title: String, alreadyOpen: Boolean = false): Boolean {
        val t0 = if (alreadyOpen) snap() ?: return false else openToInput().first
        var cur = openMenu(t0) ?: return false
        for (i in 0 until 10) {
            ScreenAnalyzer.findByText(cur.root, title)?.let { target ->
                click(cur, target)
                return runCatching { waitInput(6000) }.isSuccess
            }
            val d = ScreenAnalyzer.drawerList(t0.root, cur.root) ?: break
            if (!scroll(cur, d, up = false)) break
            delay(450)
            cur = snap() ?: break
        }
        closeMenu()
        return false
    }

    suspend fun newChat(alreadyOpen: Boolean = false): Boolean {
        val t0 = if (alreadyOpen) snap() ?: return false else openToInput().first
        ScreenAnalyzer.findNewChat(t0.root, hints)?.let {
            click(t0, it)
            return runCatching { waitInput(6000) }.isSuccess
        }
        val menu = openMenu(t0) ?: return false
        ScreenAnalyzer.findNewChat(menu.root, hints)?.let {
            click(menu, it)
            return runCatching { waitInput(6000) }.isSuccess
        }
        closeMenu()
        return false
    }

    // endregion

    // region auto-setup

    class Calibration(val hints: AppHints, val chats: List<ChatItem>, val report: JSONObject, val ok: Boolean)

    /**
     * Auto-setup: opens the app, finds the input, the send button (by typing a test word), the message
     * list, the chat menu and the new-chat button, optionally sends a test message.
     */
    suspend fun calibrate(testMessage: Boolean, progress: (key: String, status: String, detail: String) -> Unit): Calibration {
        val report = JSONObject()
        fun step(key: String, status: String, detail: String) {
            report.put(key, JSONObject().put("status", status).put("detail", detail))
            progress(key, status, detail)
        }

        step("open", "running", "Открываю $appName…")
        val (t, input) = try {
            openToInput(timeoutMs = 20_000)
        } catch (e: DriverException) {
            step("open", "error", e.message ?: "")
            return Calibration(hints, emptyList(), report, false)
        }
        step("open", "done", "Приложение открыто")
        hints = hints.copy(inputId = input.viewId ?: hints.inputId)
        step("input", "done", "Поле ввода: «${input.hint ?: input.text ?: "без подсказки"}»")

        step("send", "running", "Ищу кнопку отправки…")
        setText(t, input, "Relay")
        delay(900)
        val t2 = snap()
        val in2 = t2?.let { ScreenAnalyzer.findInput(it.root, hints) }
        val send = if (t2 != null && in2 != null) {
            ScreenAnalyzer.findSend(t2.root, in2, hints) ?: ScreenAnalyzer.findSendByDiff(t.root, t2.root, in2)
        } else {
            null
        }
        if (send != null) {
            hints = hints.copy(sendId = send.viewId, sendDesc = ScreenAnalyzer.shortLabel(send).ifBlank { null })
            step("send", "done", "Кнопка: «${ScreenAnalyzer.shortLabel(send).ifBlank { send.shortId ?: "значок" }}»")
        } else {
            step("send", "warn", "Кнопка не найдена — буду отправлять клавишей Enter")
        }
        findInputNow()?.let { (tt, ii) -> setText(tt, ii, "") }

        val t3 = snap() ?: t
        val in3 = ScreenAnalyzer.findInput(t3.root, hints)
        val list = ScreenAnalyzer.findList(t3.root, in3, hints)
        if (list != null) {
            hints = hints.copy(listId = list.viewId ?: hints.listId)
            step("list", "done", "Лента сообщений найдена — длинные ответы дочитываю прокруткой")
        } else {
            step("list", "warn", "Ленты нет (пустой чат) — найду её после первого сообщения")
        }

        step("chats", "running", "Открываю меню с чатами…")
        val chats = try {
            readChats()
        } catch (e: DriverException) {
            emptyList()
        }
        if (chats.isNotEmpty()) {
            step("chats", "done", "Чатов и проектов: ${chats.size}")
        } else {
            step("chats", "warn", "Список чатов не найден — буду писать в открытый чат")
        }

        val t4 = snap()
        val nc = t4?.let { ScreenAnalyzer.findNewChat(it.root, hints) }
        if (nc != null) {
            hints = hints.copy(newChatId = nc.viewId, newChatDesc = ScreenAnalyzer.shortLabel(nc).ifBlank { null })
            step("newchat", "done", "Кнопка нового чата найдена")
        } else {
            step("newchat", "warn", "Кнопку нового чата не нашёл (поищу в меню)")
        }

        var ok = true
        if (testMessage) {
            step("test", "running", "Отправляю пробное сообщение…")
            try {
                val r = send(TEST_PROMPT, null, null, 4000, autoDownload = false) {}
                val reply = r.lines.joinToString(" ").trim()
                if (reply.isNotEmpty()) {
                    step("test", "done", "Ответ получен: «${reply.take(80)}»")
                } else {
                    ok = false
                    step("test", "error", "Ответ не прочитан")
                }
            } catch (e: DriverException) {
                ok = false
                step("test", "error", e.message ?: "")
            }
        }
        return Calibration(hints, chats, report, ok)
    }

    /** Screen dump for diagnostics (no actions). */
    suspend fun dump(): JSONObject {
        val t = snap() ?: throw DriverException("$appName сейчас не на экране")
        val input = ScreenAnalyzer.findInput(t.root, hints)
        return JSONObject()
            .put("package", pkg)
            .put("hints", hints.toJson())
            .put("found", JSONObject()
                .put("input", input?.let { ScreenAnalyzer.shortLabel(it).ifEmpty { it.hint ?: "" } } ?: JSONObject.NULL)
                .put("send", ScreenAnalyzer.findSend(t.root, input, hints)?.let { ScreenAnalyzer.shortLabel(it) } ?: JSONObject.NULL)
                .put("menu", ScreenAnalyzer.findMenu(t.root, hints)?.let { ScreenAnalyzer.shortLabel(it) } ?: JSONObject.NULL)
                .put("busy", ScreenAnalyzer.isBusy(t.root))
                .put("lines", JSONArray(ScreenAnalyzer.lines(ScreenAnalyzer.findList(t.root, input, hints) ?: t.root, input).takeLast(30).map { it.text })))
            .put("tree", t.root.toJson())
    }

    // endregion

    companion object {
        const val REPLY_TIMEOUT = 240_000L
        const val STABLE_MS = 2500L
        const val MAX_SCROLLS = 30
        const val TEST_PROMPT = "Relay: проверка связи. Ответь одним словом: готово"
    }
}
