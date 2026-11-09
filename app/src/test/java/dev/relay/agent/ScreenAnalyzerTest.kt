package dev.relay.agent

import dev.relay.agent.nativeapp.AppHints
import dev.relay.agent.nativeapp.Box
import dev.relay.agent.nativeapp.ScreenAnalyzer
import dev.relay.agent.nativeapp.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heuristics run against synthetic screens shaped like real chat apps (Views/Compose a11y trees). */
class ScreenAnalyzerTest {
    private var counter = 0

    private fun n(
        text: String? = null,
        desc: String? = null,
        hint: String? = null,
        id: String? = null,
        cls: String? = null,
        b: Box = Box(0, 0, 10, 10),
        click: Boolean = false,
        edit: Boolean = false,
        scroll: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        kids: List<UiNode> = emptyList(),
    ): UiNode {
        val node = UiNode(counter++, text, desc, hint, id, cls, b, clickable = click, editable = edit, scrollable = scroll, enabled = enabled, visible = visible)
        kids.forEach { node.add(it) }
        return node
    }

    private val prompt = "[Relay • инструкция для ИИ]\nТы работаешь как агент на устройстве пользователя.\n\nЗадача пользователя:\nсоздай папку привет"
    private val json = """{"tool": "mkdir", "args": {"path": "~/Download/привет"}}"""

    private fun toolbar(title: String = "ChatGPT") = n(
        cls = "android.view.ViewGroup", b = Box(0, 60, 1080, 220),
        kids = listOf(
            n(desc = "Open sidebar", cls = "android.widget.ImageButton", b = Box(0, 80, 140, 220), click = true),
            n(text = title, b = Box(400, 100, 700, 200)),
            n(desc = "New chat", cls = "android.widget.ImageButton", b = Box(940, 80, 1080, 220), click = true),
        ),
    )

    private fun composer(extra: List<UiNode> = emptyList()) = n(
        cls = "android.widget.LinearLayout", b = Box(0, 2040, 1080, 2220),
        kids = listOf(
            n(hint = "Message", cls = "android.widget.EditText", b = Box(40, 2050, 800, 2200), edit = true, click = true),
            n(desc = "Voice mode", cls = "android.widget.ImageButton", b = Box(820, 2050, 940, 2200), click = true),
            n(desc = "Send prompt", cls = "android.widget.ImageButton", b = Box(950, 2050, 1070, 2200), click = true),
        ) + extra,
    )

    private fun chatList(replyKids: List<UiNode>) = n(
        cls = "androidx.recyclerview.widget.RecyclerView", b = Box(0, 220, 1080, 2000), scroll = true,
        kids = listOf(
            n(b = Box(0, 230, 1080, 330), kids = listOf(n(text = "Привет", b = Box(700, 240, 1040, 320)))),
            n(b = Box(0, 340, 1080, 500), kids = listOf(n(text = "Здравствуйте! Чем помочь?", b = Box(40, 350, 1000, 480)))),
            n(b = Box(0, 800, 1080, 1000), kids = listOf(n(text = prompt, b = Box(100, 810, 1040, 990)))),
            n(b = Box(0, 1000, 1080, 1800), kids = replyKids),
            n(
                b = Box(0, 1810, 1080, 1990),
                kids = listOf(
                    n(desc = "Copy", cls = "android.widget.ImageButton", b = Box(40, 1820, 140, 1900), click = true),
                    n(desc = "Good response", cls = "android.widget.ImageButton", b = Box(150, 1820, 250, 1900), click = true),
                    n(desc = "Share", cls = "android.widget.ImageButton", b = Box(260, 1820, 360, 1900), click = true),
                    n(text = "ChatGPT can make mistakes. Check important info.", b = Box(40, 1910, 1040, 1980)),
                ),
            ),
        ),
    )

    private val replyKids get() = listOf(
        n(text = "Создаю папку.", b = Box(40, 1010, 1040, 1080)),
        n(text = "relay", b = Box(60, 1100, 300, 1150)),
        n(text = "Copy code", cls = "android.widget.Button", b = Box(800, 1100, 1020, 1150), click = true),
        n(text = json, b = Box(60, 1160, 1020, 1300)),
        n(text = "Жду результат.", b = Box(40, 1320, 1040, 1390)),
    )

    private fun chatScreen(extraComposer: List<UiNode> = emptyList(), title: String = "ChatGPT") = n(
        cls = "android.widget.FrameLayout", b = Box(0, 0, 1080, 2400),
        kids = listOf(toolbar(title), chatList(replyKids), composer(extraComposer)),
    )

    @Test
    fun findsComposerAndSendButtonButNotMic() {
        val root = chatScreen()
        val input = ScreenAnalyzer.findInput(root)!!
        assertEquals("Message", input.hint)
        assertEquals("Send prompt", ScreenAnalyzer.findSend(root, input)!!.desc)
        assertFalse(ScreenAnalyzer.isBusy(root))
    }

    @Test
    fun busyWhileStopButtonVisibleButNotForVoiceRecording() {
        val generating = chatScreen(listOf(n(desc = "Stop generating", cls = "android.widget.ImageButton", b = Box(950, 2050, 1070, 2200), click = true)))
        assertTrue(ScreenAnalyzer.isBusy(generating))
        val recording = chatScreen(listOf(n(desc = "Stop recording", cls = "android.widget.ImageButton", b = Box(950, 2050, 1070, 2200), click = true)))
        assertFalse(ScreenAnalyzer.isBusy(recording))
    }

    @Test
    fun readsReplyAfterOurPromptWithoutChrome() {
        val root = chatScreen()
        val input = ScreenAnalyzer.findInput(root)
        val list = ScreenAnalyzer.findList(root, input)!!
        assertEquals("androidx.recyclerview.widget.RecyclerView", list.cls)
        val lines = ScreenAnalyzer.lines(list, input).map { it.text }
        assertFalse("copy-code button is chrome", lines.contains("Copy code"))
        assertFalse("disclaimer is chrome", lines.any { it.contains("can make mistakes") })
        val reply = ScreenAnalyzer.replyAfter(lines, prompt)
        assertEquals(listOf("Создаю папку.", "relay", json, "Жду результат."), reply)
    }

    @Test
    fun copyButtonOfTheNewestReplyIsNotCopyCode() {
        val root = chatScreen()
        val input = ScreenAnalyzer.findInput(root)
        val copy = ScreenAnalyzer.findCopy(root, 1000, input)!!
        assertEquals("Copy", copy.desc)
    }

    @Test
    fun menuAndNewChatButtons() {
        val root = chatScreen()
        assertEquals("Open sidebar", ScreenAnalyzer.findMenu(root)!!.desc)
        assertEquals("New chat", ScreenAnalyzer.findNewChat(root)!!.desc)
    }

    @Test
    fun hintsFromAutoSetupWin() {
        val root = n(
            b = Box(0, 0, 1080, 2400),
            kids = listOf(
                n(hint = "Ask anything", id = "app:id/composer", cls = "android.widget.EditText", b = Box(40, 2050, 800, 2200), edit = true),
                n(id = "app:id/fab_go", cls = "android.widget.ImageView", b = Box(950, 2050, 1070, 2200), click = true),
            ),
        )
        val input = ScreenAnalyzer.findInput(root)
        assertNull("unlabeled button is not guessed", ScreenAnalyzer.findSend(root, input))
        val hinted = ScreenAnalyzer.findSend(root, input, AppHints(sendId = "app:id/fab_go"))
        assertEquals("app:id/fab_go", hinted!!.viewId)
    }

    @Test
    fun sendButtonFoundByDiffDuringAutoSetup() {
        fun screen(sendEnabled: Boolean) = n(
            b = Box(0, 0, 1080, 2400),
            kids = listOf(
                n(hint = "Спросите", cls = "android.widget.EditText", b = Box(40, 2050, 800, 2200), edit = true),
                n(desc = "Микрофон", cls = "android.widget.ImageButton", b = Box(820, 2050, 940, 2200), click = true),
                n(cls = "android.widget.ImageView", b = Box(950, 2050, 1070, 2200), click = true, enabled = sendEnabled),
            ),
        )
        val before = screen(false)
        val after = screen(true)
        val btn = ScreenAnalyzer.findSendByDiff(before, after, ScreenAnalyzer.findInput(after)!!)
        assertEquals(Box(950, 2050, 1070, 2200), btn!!.bounds)
    }

    @Test
    fun shortPromptMatchesExactlyAndKeepsSimilarReply() {
        val lines = listOf("Привет", "Привет! Чем помочь?")
        assertEquals(listOf("Привет! Чем помочь?"), ScreenAnalyzer.replyAfter(lines, "Привет"))
    }

    @Test
    fun multiParagraphPromptIsSkipped() {
        val p = "Первая строка запроса, достаточно длинная для сравнения\n\nВторая часть задачи пользователя тут"
        val lines = listOf("старое", "Первая строка запроса, достаточно длинная для сравнения", "Вторая часть задачи пользователя тут", "Ответ ИИ")
        assertEquals(listOf("Ответ ИИ"), ScreenAnalyzer.replyAfter(lines, p))
        assertNull(ScreenAnalyzer.replyAfter(listOf("a", "b"), p))
    }

    @Test
    fun latestPromptWinsWhenTheSameHeaderRepeats() {
        val r1 = "[Relay • результат]\n1) mkdir → OK\nПродолжай выполнение задачи."
        val r2 = "[Relay • результат]\n1) list_dir → OK\nПродолжай выполнение задачи."
        val lines = listOf(r1, "Шаг 1 готов", r2, "Всё сделано")
        assertEquals(listOf("Всё сделано"), ScreenAnalyzer.replyAfter(lines, r2))
    }

    @Test
    fun diffFallbackWhenPromptScrolledAway() {
        val before = listOf("Привет", "Здравствуйте!")
        val after = listOf("Здравствуйте!", "новая строка 1", "новая строка 2")
        assertEquals(listOf("новая строка 1", "новая строка 2"), ScreenAnalyzer.diffNew(before, after, "Сделай что-нибудь полезное и длинное, пожалуйста"))
    }

    @Test
    fun mergesOverlappingPagesWhenScrollingUp() {
        val bottom = listOf("c", "d", "e")
        val upper = listOf("a", "b", "c", "d")
        assertTrue(ScreenAnalyzer.overlapsTop(bottom, upper))
        assertEquals(listOf("a", "b", "c", "d", "e"), ScreenAnalyzer.mergeUp(bottom, upper))
        assertFalse(ScreenAnalyzer.overlapsTop(bottom, listOf("e", "f")))
    }

    @Test
    fun readsChatsAndProjectsFromTheDrawer() {
        val before = chatScreen(title = "Планы на неделю")
        val drawer = n(
            cls = "android.widget.ScrollView", b = Box(0, 0, 800, 2400), scroll = true,
            kids = listOf(
                n(text = "New chat", b = Box(20, 100, 780, 180), click = true),
                n(hint = "Search chats", cls = "android.widget.EditText", b = Box(20, 200, 780, 280), edit = true),
                n(text = "Projects", b = Box(20, 300, 780, 360)),
                n(b = Box(20, 370, 780, 450), click = true, kids = listOf(n(text = "Работа", b = Box(40, 380, 600, 440)))),
                n(text = "Today", b = Box(20, 470, 780, 530)),
                n(
                    b = Box(20, 540, 780, 620), click = true,
                    kids = listOf(n(text = "Планы на неделю", b = Box(40, 550, 600, 610)), n(text = "12:30", b = Box(650, 550, 760, 610))),
                ),
                n(b = Box(20, 630, 780, 710), click = true, kids = listOf(n(text = "Скрипт для бэкапа", b = Box(40, 640, 600, 700)))),
                n(text = "Settings", b = Box(20, 2200, 780, 2280), click = true),
            ),
        )
        val after = n(cls = "android.widget.FrameLayout", b = Box(0, 0, 1080, 2400), kids = listOf(chatScreen(title = "Планы на неделю"), drawer))
        val items = ScreenAnalyzer.chatItems(before, after)
        assertEquals(
            listOf("project:Работа", "chat:Планы на неделю", "chat:Скрипт для бэкапа"),
            items.map { "${it.kind}:${it.title}" },
        )
        assertSame(drawer, ScreenAnalyzer.drawerList(before, after))
        val target = ScreenAnalyzer.findByText(after, "Скрипт для бэкапа")!!
        assertTrue(target.clickable)
        assertEquals(Box(20, 630, 780, 710), target.bounds)
    }

    @Test
    fun fileChipsInTheReply() {
        val root = n(
            b = Box(0, 0, 1080, 2400),
            kids = listOf(
                n(text = prompt, b = Box(100, 300, 1040, 500)),
                n(text = "Готово, вот файл. Его можно скачать.", b = Box(40, 520, 1040, 600)),
                n(b = Box(40, 620, 700, 760), click = true, kids = listOf(n(text = "report.pdf", b = Box(140, 640, 600, 700)), n(text = "PDF", b = Box(140, 700, 300, 750)))),
                n(desc = "Download", cls = "android.widget.ImageButton", b = Box(720, 640, 840, 760), click = true),
                n(b = Box(0, 780, 1080, 1700), click = true, kids = listOf(n(text = "Сохранил отчёт в report.pdf", b = Box(40, 800, 1040, 860)))),
                n(hint = "Message", cls = "android.widget.EditText", b = Box(40, 2050, 800, 2200), edit = true),
            ),
        )
        val chips = ScreenAnalyzer.fileChips(root, 500, ScreenAnalyzer.findInput(root))
        assertEquals(listOf("report.pdf", ""), chips.map { it.name })
        assertEquals("Download", chips[1].target.desc)
    }

    @Test
    fun loginScreenDetected() {
        val root = n(b = Box(0, 0, 1080, 2400), kids = listOf(n(text = "Continue with Google", b = Box(40, 1000, 1040, 1100), click = true)))
        assertTrue(ScreenAnalyzer.isLoginScreen(root))
        assertFalse(ScreenAnalyzer.isLoginScreen(chatScreen()))
    }

    @Test
    fun jsonRoundTripKeepsAnalysis() {
        val root = chatScreen()
        val copy = UiNode.fromJson(root.toJson())
        val input = ScreenAnalyzer.findInput(copy)
        assertEquals("Send prompt", ScreenAnalyzer.findSend(copy, input)!!.desc)
        val lines = ScreenAnalyzer.lines(ScreenAnalyzer.findList(copy, input)!!, input).map { it.text }
        assertEquals("Жду результат.", ScreenAnalyzer.replyAfter(lines, prompt)!!.last())
    }
}
