package dev.relay.agent

import dev.relay.agent.agent.Mime
import dev.relay.agent.agent.ToolProtocol
import dev.relay.agent.apps.AiApps
import dev.relay.agent.files.FileTracker
import dev.relay.agent.mcp.McpHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilesAndAppsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun trackerReportsOnlyFilesCreatedDuringTheTask() {
        val root = tmp.newFolder("storage")
        val dl = File(root, "Download").apply { mkdirs() }
        var now = 1_000_000_000L
        val old = File(dl, "old.pdf").apply { writeText("x"); setLastModified(now - 3_600_000) }
        val tracker = FileTracker({ listOf(dl, File(root, "Pictures")) }, { now })
        tracker.start()

        now += 10_000
        File(dl, "report.pdf").apply { writeText("pdf"); setLastModified(now) }
        File(dl, "big.zip.crdownload").apply { writeText("partial"); setLastModified(now) }
        File(dl, ".pending-123-x.pdf").apply { writeText("p"); setLastModified(now) }
        File(root, "Pictures/ChatGPT").mkdirs()
        File(root, "Pictures/ChatGPT/image.png").apply { writeText("png"); setLastModified(now) }
        File(dl, "mine.txt").apply { writeText("by tool"); setLastModified(now) }

        val found = tracker.collect { it.endsWith("mine.txt") }.map { it.name }.sorted()
        assertEquals(listOf("image.png", "report.pdf"), found)
        assertTrue("already reported files are not repeated", tracker.collect().none { it.name == "report.pdf" })
        assertTrue(old.exists())
    }

    @Test
    fun aiAppHeuristics() {
        assertTrue(AiApps.looksLikeAi("ГигаЧат", "com.sdkit.search.app"))
        assertTrue(AiApps.looksLikeAi("Алиса AI: Нейросеть Яндекса", "com.yandex.aliceapp"))
        assertTrue(AiApps.looksLikeAi("Poe – Fast AI Chat", "com.quora.poe"))
        assertTrue(AiApps.looksLikeAi("Kimi", "com.moonshot.kimichat"))
        assertFalse(AiApps.looksLikeAi("Telegram", "org.telegram.messenger"))
        assertFalse(AiApps.looksLikeAi("Калькулятор", "com.android.calculator2"))
        assertFalse(AiApps.looksLikeAi("Gmail", "com.google.android.gm"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/pdf", Mime.of("Отчёт.PDF"))
        assertTrue(Mime.isText(Mime.of("data.csv"), "data.csv"))
        assertFalse(Mime.isText(Mime.of("photo.jpg"), "photo.jpg"))
        assertEquals("xlsx", Mime.ext("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; charset=binary"))
    }

    @Test
    fun copiedMarkdownCodeBlocks() {
        val md = "Создаю.\n\n```relay\n{\"tool\": \"mkdir\", \"args\": {\"path\": \"~/x\"}}\n```\n\n```kotlin\nval a = 1\n```"
        val blocks = ToolProtocol.fencedBlocks(md)
        assertEquals(2, blocks.size)
        assertEquals("mkdir", ToolProtocol.parse(md, blocks).single().tool)
    }

    @Test
    fun preambleExplainsFilesAndMcpHidesAttach() {
        val p = ToolProtocol.preamble(ToolProtocol.Context("/sdcard", false, "Phone"))
        assertTrue(p.contains("attach_file"))
        assertTrue(p.contains("download_file"))
        val tools = McpHandler({ dev.relay.agent.tools.ToolResult(true, "") }).toolsJson()
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertFalse(names.contains("attach_file"))
        assertTrue(names.contains("download_file"))
    }
}
