package dev.relay.agent.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w: Int get() = r - l
    val h: Int get() = b - t
    val cx: Int get() = (l + r) / 2
    val cy: Int get() = (t + b) / 2
    val area: Long get() = max(0, w).toLong() * max(0, h).toLong()
    val empty: Boolean get() = w <= 0 || h <= 0

    fun union(o: Box) = Box(min(l, o.l), min(t, o.t), max(r, o.r), max(b, o.b))

    override fun toString() = "[$l,$t][$r,$b]"

    companion object {
        val ZERO = Box(0, 0, 0, 0)
    }
}

/**
 * Immutable snapshot of one accessibility node. [index] maps back to the live node (-1 = synthetic).
 * Pure Kotlin so screen heuristics can be unit-tested and screen dumps from devices replayed in tests.
 */
class UiNode(
    val index: Int,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val cls: String? = null,
    val bounds: Box = Box.ZERO,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val selected: Boolean = false,
) {
    var parent: UiNode? = null
        private set
    val children: MutableList<UiNode> = mutableListOf()

    fun add(child: UiNode): UiNode {
        child.parent = this
        children += child
        return child
    }

    val shortId: String? get() = viewId?.substringAfter(":id/")

    val label: String
        get() = listOfNotNull(text, desc, shortId?.replace('_', ' '), hint).joinToString(" ").trim()

    fun preorder(): List<UiNode> {
        val out = ArrayList<UiNode>()
        val stack = ArrayDeque<UiNode>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out += n
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
        return out
    }

    fun ancestors(): Sequence<UiNode> = generateSequence(parent) { it.parent }

    fun isInside(other: UiNode): Boolean = other === this || ancestors().any { it === other }

    fun toJson(): JSONObject {
        val o = JSONObject()
        text?.let { o.put("t", it) }
        desc?.let { o.put("d", it) }
        hint?.let { o.put("h", it) }
        viewId?.let { o.put("id", it) }
        cls?.let { o.put("c", it) }
        o.put("b", JSONArray().put(bounds.l).put(bounds.t).put(bounds.r).put(bounds.b))
        val flags = buildString {
            if (clickable) append('C')
            if (longClickable) append('L')
            if (editable) append('E')
            if (scrollable) append('S')
            if (focused) append('F')
            if (!enabled) append('D')
            if (!visible) append('I')
            if (selected) append('X')
        }
        if (flags.isNotEmpty()) o.put("f", flags)
        if (children.isNotEmpty()) o.put("ch", JSONArray().also { arr -> children.forEach { arr.put(it.toJson()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject, counter: IntArray = intArrayOf(0)): UiNode {
            val b = o.optJSONArray("b")
            val f = o.optString("f")
            val n = UiNode(
                index = counter[0]++,
                text = o.optString("t").takeIf { o.has("t") },
                desc = o.optString("d").takeIf { o.has("d") },
                hint = o.optString("h").takeIf { o.has("h") },
                viewId = o.optString("id").takeIf { o.has("id") },
                cls = o.optString("c").takeIf { o.has("c") },
                bounds = if (b != null && b.length() == 4) Box(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)) else Box.ZERO,
                clickable = 'C' in f,
                longClickable = 'L' in f,
                editable = 'E' in f,
                scrollable = 'S' in f,
                focused = 'F' in f,
                enabled = 'D' !in f,
                visible = 'I' !in f,
                selected = 'X' in f,
            )
            o.optJSONArray("ch")?.let { arr -> for (i in 0 until arr.length()) n.add(fromJson(arr.getJSONObject(i), counter)) }
            return n
        }
    }
}

/** What auto-setup learned about one app; every field is optional and only narrows the heuristics. */
data class AppHints(
    val inputId: String? = null,
    val sendId: String? = null,
    val sendDesc: String? = null,
    val listId: String? = null,
    val menuId: String? = null,
    val menuDesc: String? = null,
    val newChatId: String? = null,
    val newChatDesc: String? = null,
    val entryText: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().also { o ->
        inputId?.let { o.put("inputId", it) }
        sendId?.let { o.put("sendId", it) }
        sendDesc?.let { o.put("sendDesc", it) }
        listId?.let { o.put("listId", it) }
        menuId?.let { o.put("menuId", it) }
        menuDesc?.let { o.put("menuDesc", it) }
        newChatId?.let { o.put("newChatId", it) }
        newChatDesc?.let { o.put("newChatDesc", it) }
        entryText?.let { o.put("entryText", it) }
    }

    companion object {
        fun fromJson(o: JSONObject?): AppHints {
            if (o == null) return AppHints()
            fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() }
            return AppHints(
                s("inputId"), s("sendId"), s("sendDesc"), s("listId"), s("menuId"), s("menuDesc"),
                s("newChatId"), s("newChatDesc"), s("entryText"),
            )
        }
    }
}

data class Line(val text: String, val node: UiNode)

data class ChatItem(val title: String, val kind: String)

data class FileChip(val name: String, val target: UiNode)

object ScreenAnalyzer {
    private val IC = RegexOption.IGNORE_CASE

    private val SEND_RX = Regex("""send|submit|отправ""", IC)
    private val SEND_NEG_RX = Regex(
        """voice|mic|dictat|record|attach|camera|photo|image|plus|\badd\b|upload|\bfile|model|stop|голос|микроф|диктов|запис|прикреп|камер|фото|изображ|добав|файл|модел|стоп|останов""",
        IC,
    )
    private val STOP_RX = Regex("""(^|\b)(stop|остановить|стоп)(\b|$)|stop (generating|response|streaming)|cancel (generation|response)|прервать""", IC)
    private val STOP_NEG_RX = Regex("""record|voice|dictat|listening|запис|голос|диктов""", IC)
    private val MENU_RX = Regex(
        """menu|sidebar|side bar|drawer|navigation|history|conversations|\bchats\b|open (side|nav)|меню|боков|навигац|истори|чаты|диалоги|разговоры""",
        IC,
    )
    private val MENU_NEG_RX = Regex("""back|назад|close|закрыть|\bnew\b|нов[ыоа]|options|ещё|еще|settings|настро|share|поделит|model|модел""", IC)
    private val NEW_CHAT_RX = Regex(
        """new (chat|conversation|thread)|start (a )?new|новый (чат|диалог|разговор)|новая беседа|начать (новый|заново)|^compose$|^new$""",
        IC,
    )
    private val COPY_RX = Regex("""^(copy|копировать|скопировать)( (message|response|text|answer|reply|ответ|текст|сообщение))?$""", IC)
    private val SEARCH_RX = Regex("""search|поиск|найти""", IC)
    private val LOGIN_RX = Regex("""sign in|log in|login|sign up|continue with|войти|вход в|зарегистр|продолжить с""", IC)
    private val ENTRY_RX = Regex("""^(ai|ии|notion ai|ask ai|спросить ии|chat|чат|assistant|ассистент|new chat|новый чат)$""", IC)
    private val CHROME_RX = Regex(
        """^(copy( code| text| message)?|копировать( код| текст)?|скопировать|share|поделиться|edit|изменить|retry|regenerate|повторить|перегенерировать|read aloud|озвучить|like|dislike|good response|bad response|more|ещё|еще|show more|показать (больше|ещё|еще|полностью)|show less|свернуть|thinking(…|\.\.\.)?|думаю(…|\.\.\.)?|searching( the web)?(…|\.\.\.)?|ищу(…|\.\.\.)?|thought for .{1,20}|думал[аи]? .{1,20})$""",
        IC,
    )
    private val DISCLAIMER_RX = Regex("""can make mistakes|может (допускать )?ошиб|check important info|проверяйте важн|AI-generated|сгенерировано ии""", IC)
    private val TIME_RX = Regex("""^\d{1,2}:\d{2}(\s?[AaPp][Mm])?$""")
    private val PROJECT_HEADER_RX = Regex("""^(projects?|проекты?|spaces?|пространства|gpts|folders?|папки)$""", IC)
    private val SECTION_HEADER_RX = Regex(
        """^(today|yesterday|previous.*|last .*|this (week|month|year)|older|recents?|recent chats|chats|history|starred|pinned|сегодня|вчера|предыдущ.*|ранее|недавн.*|чаты|история|избранн.*|закрепл[её]нн.*|последн.*|\d+ (days|дн.*)|(январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр|january|february|march|april|may|june|july|august|september|october|november|december).*|20\d\d)$""",
        IC,
    )
    private val DRAWER_CHROME_RX = Regex(
        """^(new chat|новый чат|new project|новый проект|search( chats)?|поиск( чатов)?|settings|настройки|upgrade.*|get .*plus|get .*pro|explore( gpts)?|library|библиотека|images|изображения|codex|sora|profile|профиль|log ?out|sign out|выйти|see all|see more|show more|view all|показать (все|ещё|еще)|more|ещё|еще|help|помощь|home|главная|inbox|входящие|account|аккаунт)$""",
        IC,
    )
    private val FILE_RX = Regex(
        """([^\s/\\:*?"<>|«»()]{1,80}\.(pdf|docx?|xlsx?|pptx?|csv|tsv|txt|md|json|xml|ya?ml|zip|rar|7z|png|jpe?g|webp|gif|svg|mp3|wav|m4a|ogg|mp4|mov|webm|py|js|ts|kt|java|html?|css|sql|sh|apk|epub|rtf|odt|ods))(?![\w.])""",
        IC,
    )
    private val DOWNLOAD_RX = Regex("""^(download|скачать|загрузить|save|save file|save to device|сохранить|сохранить файл)$|download|скачать""", IC)

    fun norm(s: String): String = s.replace('\u00A0', ' ')
        .replace(Regex("[\u200B-\u200D\uFEFF]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()

    /** Label of a node plus its descendants (icon buttons often keep the description on a child). */
    fun deepLabel(n: UiNode, depth: Int = 2): String {
        val sb = StringBuilder(n.label)
        if (depth > 0) {
            for (c in n.children) {
                if (c.editable) continue
                val l = deepLabel(c, depth - 1)
                if (l.isNotBlank()) sb.append(' ').append(l)
            }
        }
        return sb.toString().trim()
    }

    /** Visible caption of a button: its own text/description or that of a child. */
    fun shortLabel(n: UiNode): String {
        (n.desc ?: n.text)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (c in n.children) {
            val l = shortLabel(c)
            if (l.isNotEmpty()) return l
        }
        return ""
    }

    private fun buttons(root: UiNode): List<UiNode> = root.preorder().filter {
        it.visible && !it.editable && !it.bounds.empty && (it.clickable || it.cls?.contains("Button") == true)
    }

    fun clickableTarget(n: UiNode): UiNode? {
        if (n.clickable) return n
        return n.ancestors().take(5).firstOrNull { it.clickable }
    }

    // region composer

    fun findInput(root: UiNode, hints: AppHints? = null): UiNode? {
        val edits = root.preorder().filter { it.editable && it.visible && !it.bounds.empty }
        if (edits.isEmpty()) return null
        hints?.inputId?.let { id -> edits.lastOrNull { it.viewId == id }?.let { return it } }
        return edits.maxByOrNull { n ->
            var s = n.bounds.b.toLong() + n.bounds.w / 3
            if (n.focused) s += 2000
            if (SEARCH_RX.containsMatchIn(n.label)) s -= 5000
            s
        }
    }

    fun findSend(root: UiNode, input: UiNode?, hints: AppHints? = null): UiNode? {
        val cands = buttons(root).filter { it !== input }
        if (hints != null && (hints.sendId != null || hints.sendDesc != null)) {
            cands.lastOrNull { n ->
                (hints.sendId != null && n.viewId == hints.sendId) ||
                    (hints.sendDesc != null && shortLabel(n).equals(hints.sendDesc, ignoreCase = true))
            }?.let { if (it.enabled) return it }
        }
        var best: UiNode? = null
        var bestScore = 0
        for (n in cands) {
            if (!n.enabled) continue
            val label = deepLabel(n)
            var s = 0
            if (SEND_RX.containsMatchIn(label)) s += 10
            if (SEND_NEG_RX.containsMatchIn(label)) s -= 9
            if (input != null) {
                val ib = input.bounds
                val b = n.bounds
                if (b.cy in (ib.t - 160)..(ib.b + 160)) s += 3
                if (b.cx >= ib.cx) s += 2
            }
            if (s > bestScore) {
                bestScore = s
                best = n
            }
        }
        return if (bestScore >= 10) best else null
    }

    /** During auto-setup: the button that appeared or got enabled next to the input after typing. */
    fun findSendByDiff(before: UiNode, after: UiNode, input: UiNode): UiNode? {
        fun key(n: UiNode) = "${shortLabel(n)}|${n.viewId}|${n.bounds}"
        val prev = buttons(before).associateBy(::key)
        val ib = input.bounds
        return buttons(after)
            .filter { n ->
                n.enabled && n !== input && n.bounds.cy in (ib.t - 160)..(ib.b + 160) && n.bounds.cx >= ib.cx &&
                    !SEND_NEG_RX.containsMatchIn(deepLabel(n)) && prev[key(n)]?.enabled != true
            }
            .maxByOrNull { it.bounds.r }
    }

    fun isBusy(root: UiNode): Boolean = buttons(root).any {
        val l = deepLabel(it, 1)
        STOP_RX.containsMatchIn(l) && !STOP_NEG_RX.containsMatchIn(l)
    }

    fun isLoginScreen(root: UiNode): Boolean =
        findInput(root) == null && root.preorder().any { it.visible && (it.text ?: it.desc)?.let(LOGIN_RX::containsMatchIn) == true }

    fun findEntry(root: UiNode, hints: AppHints?, entry: Regex?): UiNode? = buttons(root).firstOrNull { n ->
        val l = shortLabel(n).trim()
        l.isNotEmpty() && (
            (hints?.entryText != null && l.equals(hints.entryText, true)) ||
                (entry != null && entry.containsMatchIn(l)) ||
                ENTRY_RX.matches(l)
            )
    }

    // endregion

    // region conversation

    fun findList(root: UiNode, input: UiNode?, hints: AppHints? = null): UiNode? {
        val sc = root.preorder().filter { it.scrollable && it.visible && !it.editable && !it.bounds.empty }
        hints?.listId?.let { id -> sc.firstOrNull { it.viewId == id }?.let { return it } }
        val usable = if (input == null) sc else sc.filter { it.bounds.cy < input.bounds.t && !input.isInside(it) }
        return usable.maxByOrNull { it.bounds.area }
    }

    fun isChrome(n: UiNode, t: String): Boolean {
        val s = t.trim()
        if (s.length <= 40 && CHROME_RX.matches(s)) return true
        if (s.length < 160 && DISCLAIMER_RX.containsMatchIn(s)) return true
        if (TIME_RX.matches(s)) return true
        if (n.cls?.contains("Button") == true && s.length <= 30) return true
        return false
    }

    /** Message texts in reading order (top to bottom), without editable fields and UI chrome. */
    fun lines(scope: UiNode, input: UiNode? = null): List<Line> {
        val items = scope.children.toList()
        val ordered = if (items.size > 1 && items.all { it.visible && !it.bounds.empty }) items.sortedBy { it.bounds.t } else items
        val nodes = if (items.isEmpty()) listOf(scope) else listOf(scope) + ordered.flatMap { it.preorder() }
        val out = ArrayList<Line>()
        for (n in nodes) {
            val t = n.text?.trim()
            if (t.isNullOrEmpty() || n.editable) continue
            if (input != null && n.isInside(input)) continue
            if (isChrome(n, t)) continue
            val prev = out.lastOrNull()
            if (prev != null && prev.text == t && (n.isInside(prev.node) || prev.node.isInside(n))) continue
            out += Line(t, n)
        }
        return out
    }

    fun promptIndex(lines: List<String>, prompt: String): Int {
        val p = norm(prompt)
        if (p.isEmpty()) return -1
        val long = p.length >= 30
        val head = p.take(30)
        for (i in lines.indices.reversed()) {
            val n = norm(lines[i])
            if (n.isEmpty()) continue
            if (n == p) return i
            if (long && (n.startsWith(head) || (n.length >= 12 && p.startsWith(n)))) return i
        }
        if (long) {
            for (i in lines.indices.reversed()) {
                val n = norm(lines[i])
                if (n.length >= 40 && p.contains(n)) return i
            }
        }
        return -1
    }

    private fun isPromptPart(line: String, prompt: String): Boolean {
        val n = norm(line)
        val p = norm(prompt)
        return n.isNotEmpty() && (n == p || (n.length >= 12 && p.contains(n)))
    }

    /** Lines after our own message; null when the message is not on screen. */
    fun replyAfter(lines: List<String>, prompt: String): List<String>? {
        val i = promptIndex(lines, prompt)
        if (i < 0) return null
        var j = i + 1
        while (j < lines.size && isPromptPart(lines[j], prompt)) j++
        return lines.subList(j, lines.size)
    }

    /** Fallback when our message is not visible: texts that were not on screen before sending. */
    fun diffNew(before: List<String>, after: List<String>, prompt: String): List<String> {
        val remaining = HashMap<String, Int>()
        before.forEach { remaining[it] = (remaining[it] ?: 0) + 1 }
        return after.filter { t ->
            val c = remaining[t] ?: 0
            if (c > 0) {
                remaining[t] = c - 1
                false
            } else {
                true
            }
        }.filter { !isPromptPart(it, prompt) && promptIndex(listOf(it), prompt) < 0 }
    }

    /** Joins a page captured after scrolling up onto the text collected so far (pages overlap). */
    fun mergeUp(lower: List<String>, upper: List<String>): List<String> {
        if (lower.isEmpty()) return upper
        if (upper.isEmpty()) return lower
        for (k in minOf(lower.size, upper.size) downTo 1) {
            if (upper.subList(upper.size - k, upper.size) == lower.subList(0, k)) return upper + lower.drop(k)
        }
        return upper + lower
    }

    /** True when [upper] overlaps the top of [lower], i.e. the scroll really went up. */
    fun overlapsTop(lower: List<String>, upper: List<String>): Boolean {
        for (k in minOf(lower.size, upper.size) downTo 1) {
            if (upper.subList(upper.size - k, upper.size) == lower.subList(0, k)) return true
        }
        return false
    }

    /** The "Copy" button of the newest reply (not "Copy code"). */
    fun findCopy(root: UiNode, minTop: Int?, input: UiNode?): UiNode? = buttons(root)
        .filter { COPY_RX.matches(shortLabel(it).trim()) }
        .filter { minTop == null || it.bounds.t >= minTop }
        .filter { input == null || it.bounds.b <= input.bounds.t + 10 }
        .maxByOrNull { it.bounds.b }

    /** File attachments and download buttons that appear after our message. */
    fun fileChips(root: UiNode, minTop: Int?, input: UiNode?): List<FileChip> {
        val out = LinkedHashMap<UiNode, FileChip>()
        for (n in root.preorder()) {
            if (!n.visible || n.editable || n.bounds.empty) continue
            if (minTop != null && n.bounds.t < minTop) continue
            if (input != null && (n.isInside(input) || n.bounds.t >= input.bounds.t)) continue
            val label = (n.text ?: n.desc)?.trim() ?: continue
            val target = clickableTarget(n) ?: continue
            // Chip-sized targets only: tapping a whole message bubble could open unrelated menus.
            if (target.bounds.h > 320 || out.containsKey(target)) continue
            val name = FILE_RX.find(label)?.groupValues?.get(1)
            when {
                name != null && label.length <= 160 -> out[target] = FileChip(name, target)
                n.clickable && DOWNLOAD_RX.containsMatchIn(shortLabel(n)) -> out[target] = FileChip("", target)
            }
        }
        return out.values.toList()
    }

    fun findDownloadButton(root: UiNode): UiNode? = buttons(root).firstOrNull { DOWNLOAD_RX.containsMatchIn(shortLabel(it)) }

    // endregion

    // region navigation: menu, chats, new chat

    fun findMenu(root: UiNode, hints: AppHints? = null): UiNode? {
        val all = buttons(root)
        if (hints != null && (hints.menuId != null || hints.menuDesc != null)) {
            all.firstOrNull { n ->
                (hints.menuId != null && n.viewId == hints.menuId) ||
                    (hints.menuDesc != null && shortLabel(n).equals(hints.menuDesc, true))
            }?.let { return it }
        }
        val screenTop = root.bounds.t
        val h = root.bounds.h.takeIf { it > 0 } ?: 2400
        return all
            .filter { it.bounds.cy <= screenTop + h * 0.2 }
            .filter { n ->
                val l = deepLabel(n, 1)
                MENU_RX.containsMatchIn(l) && !MENU_NEG_RX.containsMatchIn(l)
            }
            .minByOrNull { it.bounds.l }
    }

    fun findNewChat(root: UiNode, hints: AppHints? = null): UiNode? {
        val all = buttons(root)
        if (hints != null && (hints.newChatId != null || hints.newChatDesc != null)) {
            all.firstOrNull { n ->
                (hints.newChatId != null && n.viewId == hints.newChatId) ||
                    (hints.newChatDesc != null && shortLabel(n).equals(hints.newChatDesc, true))
            }?.let { return it }
        }
        return all.filter { NEW_CHAT_RX.containsMatchIn(shortLabel(it).trim()) || NEW_CHAT_RX.containsMatchIn(deepLabel(it, 1)) }
            .minByOrNull { it.bounds.cy }
    }

    private fun drawerCandidates(before: UiNode?, after: UiNode): List<UiNode> {
        val old = HashSet<String>()
        before?.preorder()?.forEach { n -> if (n.visible) n.text?.let { old += norm(it) + "|" + n.bounds } }
        return after.preorder().filter { n ->
            val t = n.text?.trim()
            n.visible && !n.editable && !t.isNullOrEmpty() && t.length in 2..100 && clickableTarget(n) != null &&
                (norm(t) + "|" + n.bounds) !in old
        }
    }

    /** The scrollable container of an opened drawer (the one holding most new items). */
    fun drawerList(before: UiNode?, after: UiNode): UiNode? {
        val cands = drawerCandidates(before, after)
        if (cands.isEmpty()) return null
        return after.preorder()
            .filter { it.scrollable && it.visible }
            .map { sc -> sc to cands.count { it.isInside(sc) } }
            .filter { it.second >= 2 }
            .maxByOrNull { it.second }?.first
    }

    /** Chats and projects shown after opening the app's menu (diffed against the screen before). */
    fun chatItems(before: UiNode?, after: UiNode): List<ChatItem> {
        val cands = drawerCandidates(before, after).toHashSet()
        val container = drawerList(before, after)
        val strong = container != null && cands.count { it.isInside(container) } >= 3
        val out = LinkedHashMap<String, ChatItem>()
        val usedRows = HashSet<UiNode>()
        var section = "chat"
        for (n in after.preorder()) {
            if (!n.visible || n.editable) continue
            val t = n.text?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (PROJECT_HEADER_RX.matches(t)) {
                section = "project"
                continue
            }
            if (SECTION_HEADER_RX.matches(t)) {
                section = "chat"
                continue
            }
            if (n !in cands) continue
            // Items above the drawer's scrollable list (e.g. a projects block) are still accepted.
            if (strong && !n.isInside(container!!) && n.bounds.t >= container.bounds.t) continue
            if (DRAWER_CHROME_RX.matches(t) || CHROME_RX.matches(t) || TIME_RX.matches(t)) continue
            val row = clickableTarget(n) ?: continue
            if (!usedRows.add(row)) continue
            out.putIfAbsent(norm(t), ChatItem(t, section))
        }
        return out.values.toList()
    }

    fun findByText(root: UiNode, title: String): UiNode? {
        val want = norm(title)
        val hits = root.preorder().filter { it.visible && it.text != null && norm(it.text) == want }
        val hit = hits.firstOrNull { h -> h.ancestors().any { it.scrollable } } ?: hits.firstOrNull() ?: return null
        return clickableTarget(hit) ?: hit
    }

    // endregion
}
