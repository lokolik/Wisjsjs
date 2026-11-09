package dev.relay.agent

import dev.relay.agent.agent.ToolProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProtocolTest {

    @Test
    fun parsesCallFromCodeBlockText() {
        // innerText of a rendered code block on a chat site: language label + copy button + code.
        val reply = """
            Создаю папку.
            relay
            Copy code
            {"tool": "mkdir", "args": {"path": "~/Download/привет"}}
            Готово, жду результат.
        """.trimIndent()
        val calls = ToolProtocol.parse(reply)
        assertEquals(1, calls.size)
        assertEquals("mkdir", calls[0].tool)
        assertEquals("~/Download/привет", calls[0].args.getString("path"))
    }

    @Test
    fun prefersCodeBlocksAndKeepsOrder() {
        val blocks = listOf(
            """{"tool":"mkdir","args":{"path":"~/Download/a"}}""",
            """{"tool":"write_file","args":{"path":"~/Download/a/x.txt","content":"{not json} \"quoted\""}}""",
        )
        val calls = ToolProtocol.parse("noise {\"tool\":\"delete\",\"args\":{\"path\":\"~\"}}", blocks, "t")
        assertEquals(listOf("mkdir", "write_file"), calls.map { it.tool })
        assertEquals("t1", calls[0].id)
        assertEquals("{not json} \"quoted\"", calls[1].args.getString("content"))
    }

    @Test
    fun acceptsFlatAndOpenAiStyleCalls() {
        val text = """
            {"tool":"list_dir","path":"~/Download"}
            {"name":"device_info","arguments":"{}"}
        """.trimIndent()
        val calls = ToolProtocol.parse(text)
        assertEquals(listOf("list_dir", "device_info"), calls.map { it.tool })
        assertEquals("~/Download", calls[0].args.getString("path"))
    }

    @Test
    fun repairsTypographicQuotes() {
        val calls = ToolProtocol.parse("“tool” is next: {“tool”: “mkdir”, “args”: {“path”: “~/x”}}")
        assertEquals(1, calls.size)
        assertEquals("~/x", calls[0].args.getString("path"))
    }

    @Test
    fun ignoresUnrelatedJson() {
        assertTrue(ToolProtocol.parse("""Пример: {"a": 1, "b": {"c": 2}} и всё.""").isEmpty())
        assertTrue(ToolProtocol.parse("Обычный ответ без инструментов").isEmpty())
    }

    @Test
    fun stripCallsRemovesJsonAndChrome() {
        val reply = "Создаю папку.\nrelay\nCopy code\n{\"tool\": \"mkdir\", \"args\": {\"path\": \"~/Download/привет\"}}\nЖду результат."
        assertEquals("Создаю папку.\nЖду результат.", ToolProtocol.stripCalls(reply))
    }

    @Test
    fun preambleMentionsToolsAndPaths() {
        val p = ToolProtocol.preamble(ToolProtocol.Context("/storage/emulated/0", rootAvailable = true, device = "Pixel"))
        assertTrue(p.startsWith(ToolProtocol.PREAMBLE_HEADER))
        assertTrue(p.contains("mkdir"))
        assertTrue(p.contains("shell"))
        assertTrue(p.contains("/storage/emulated/0"))
        assertTrue(p.contains("доступен"))
        // The example in the preamble must itself be parseable by our parser.
        assertEquals("mkdir", ToolProtocol.parse(p).first().tool)
    }

    @Test
    fun formatResultsTruncatesLongOutput() {
        val call = ToolProtocol.parse("""{"tool":"read_file","args":{"path":"a"}}""").first()
        val msg = ToolProtocol.formatResults(listOf(call to dev.relay.agent.tools.ToolResult(true, "x".repeat(50_000))), maxEach = 1000, maxTotal = 3000)
        assertTrue(msg.startsWith(ToolProtocol.RESULT_HEADER))
        assertTrue(msg.length <= 3000)
        assertTrue(msg.contains("обрезано"))
        assertFalse(msg.contains("x".repeat(1001)))
    }
}
