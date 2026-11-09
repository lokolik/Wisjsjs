package dev.relay.agent

import dev.relay.agent.tools.Platform
import dev.relay.agent.tools.Shell
import dev.relay.agent.tools.ShellResult
import dev.relay.agent.tools.ToolCall
import dev.relay.agent.tools.ToolExecutor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

class FakePlatform(override val storageRoot: File) : Platform {
    val opened = mutableListOf<String>()
    val attached = mutableListOf<File>()
    val created = mutableListOf<Pair<File, String>>()
    override fun openUrl(url: String): String = "Открыто: $url".also { opened += url }
    override fun launchApp(nameOrPackage: String): String = "Запущено: $nameOrPackage"
    override fun deviceInfo(): Map<String, Any?> = mapOf("Устройство" to "Test")
    override fun openFile(file: File): String = "Открыт файл: ${file.path}".also { opened += file.path }
    override fun attachFile(file: File): String = "Файл ${file.name} будет прикреплён к следующему сообщению".also { attached += file }
    override fun onFileCreated(file: File, source: String) {
        created += file to source
    }
}

/** Root-capable shell that records commands instead of running su. */
class FakeRootShell(private val root: Boolean) : Shell() {
    val commands = mutableListOf<String>()
    override fun suPresent() = root
    override fun hasRoot(refresh: Boolean) = root
    override fun run(command: String, root: Boolean, timeoutSec: Long, workDir: File?): ShellResult {
        if (root) {
            commands += command
            return ShellResult(0, "", "")
        }
        return super.run(command, false, timeoutSec, workDir)
    }
}

class ToolExecutorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun exec(rootAllowed: Boolean = false, shell: Shell = FakeRootShell(false)): Pair<ToolExecutor, File> {
        val root = tmp.newFolder("storage")
        File(root, "Download").mkdirs()
        return ToolExecutor(FakePlatform(root), shell) { rootAllowed } to root
    }

    private fun call(tool: String, vararg args: Pair<String, Any>) =
        ToolCall("t", tool, JSONObject().also { o -> args.forEach { o.put(it.first, it.second) } })

    @Test
    fun mkdirInDownloadsWithCyrillicName() {
        val (ex, root) = exec()
        val r = ex.execute(call("mkdir", "path" to "~/Download/привет"))
        assertTrue(r.output, r.ok)
        assertTrue(File(root, "Download/привет").isDirectory)
        // Russian alias and repeated call.
        val again = ex.execute(call("mkdir", "path" to "Загрузки/привет"))
        assertTrue(again.ok)
        assertTrue(again.output.contains("уже существует"))
    }

    @Test
    fun writeReadListSearchMoveCopyDelete() {
        val (ex, root) = exec()
        assertTrue(ex.execute(call("write_file", "path" to "~/Documents/notes/a.txt", "content" to "привет\nмир")).ok)
        assertTrue(ex.execute(call("write_file", "path" to "~/Documents/notes/a.txt", "content" to "!", "append" to true)).ok)
        assertEquals("привет\nмир!", File(root, "Documents/notes/a.txt").readText())
        assertEquals("привет\nмир!", ex.execute(call("read_file", "path" to "/sdcard/Documents/notes/a.txt")).output)

        val list = ex.execute(call("list_dir", "path" to "~/Documents/notes"))
        assertTrue(list.output.contains("📄 a.txt"))

        val found = ex.execute(call("search_files", "path" to "~", "pattern" to "*.TXT"))
        assertTrue(found.output, found.output.contains("a.txt"))

        assertTrue(ex.execute(call("copy", "from" to "~/Documents/notes/a.txt", "to" to "~/Download")).ok)
        assertTrue(File(root, "Download/a.txt").isFile)
        assertTrue(ex.execute(call("move", "from" to "~/Download/a.txt", "to" to "~/Download/b.txt")).ok)
        assertTrue(File(root, "Download/b.txt").isFile)
        assertFalse(File(root, "Download/a.txt").exists())

        val notEmpty = ex.execute(call("delete", "path" to "~/Documents/notes"))
        assertFalse(notEmpty.ok)
        assertTrue(ex.execute(call("delete", "path" to "~/Documents/notes", "recursive" to true)).ok)
        assertFalse(File(root, "Documents/notes").exists())
    }

    @Test
    fun refusesToDeleteStorageRootAndSystem() {
        val (ex, root) = exec()
        assertFalse(ex.execute(call("delete", "path" to "~", "recursive" to true)).ok)
        assertFalse(ex.execute(call("delete", "path" to "/system/bin", "recursive" to true)).ok)
        assertTrue(root.isDirectory)
    }

    @Test
    fun missingArgumentAndUnknownTool() {
        val (ex, _) = exec()
        val missing = ex.execute(call("mkdir"))
        assertFalse(missing.ok)
        assertTrue(missing.output.contains("path"))
        val unknown = ex.execute(call("format_disk"))
        assertFalse(unknown.ok)
        assertTrue(unknown.output.contains("Неизвестный"))
    }

    @Test
    fun shellRunsWithoutRootAndRespectsRootSetting() {
        val (ex, _) = exec(rootAllowed = false)
        val r = ex.execute(call("shell", "command" to "echo hi"))
        assertTrue(r.output, r.ok)
        assertTrue(r.output.contains("hi"))
        val denied = ex.execute(call("shell", "command" to "id", "root" to true))
        assertFalse(denied.ok)
        assertTrue(denied.output.contains("отключён"))
    }

    @Test
    fun fallsBackToRootWhenPlainAccessFails() {
        val shell = FakeRootShell(root = true)
        val (ex, _) = exec(rootAllowed = true, shell = shell)
        // A path that cannot be created without privileges.
        val r = ex.execute(call("mkdir", "path" to "/proc/relay-test-dir"))
        assertTrue(r.output, r.ok)
        assertTrue(r.output.contains("root"))
        assertEquals("mkdir -p -- '/proc/relay-test-dir'", shell.commands.single())
    }

    @Test
    fun deviceInfoAndOpenUrl() {
        val (ex, _) = exec()
        val info = ex.execute(call("device_info"))
        assertTrue(info.output.contains("Общая память"))
        assertTrue(ex.execute(call("open_url", "url" to "https://example.com")).ok)
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", Shell.quote("it's"))
    }

    /** Tiny HTTP/1.1 server (android.jar has no com.sun.net.httpserver). */
    private fun httpServer(routes: Map<String, Pair<String, ByteArray>>): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        Thread {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                s.use { sock ->
                    val reader = sock.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val (head, body) = routes[path] ?: ("HTTP/1.1 404 Not Found" to ByteArray(0))
                    val out = sock.getOutputStream()
                    out.write("$head\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.write(body)
                    out.flush()
                }
            }
        }.apply { isDaemon = true }.start()
        return server
    }

    @Test
    fun downloadsFileWithNameFromHeadersFollowingRedirects() {
        val server = httpServer(
            mapOf(
                "/go" to ("HTTP/1.1 302 Found\r\nLocation: /files/x" to ByteArray(0)),
                "/files/x" to (
                    "HTTP/1.1 200 OK\r\nContent-Disposition: attachment; filename*=UTF-8''%D0%BE%D1%82%D1%87%D1%91%D1%82.txt" to
                        "отчёт".toByteArray()
                    ),
                "/plain/data.csv" to ("HTTP/1.1 200 OK\r\nContent-Type: text/csv" to "a,b\n1,2".toByteArray()),
            ),
        )
        try {
            val root = tmp.newFolder("dl")
            val platform = FakePlatform(root)
            val ex = ToolExecutor(platform, FakeRootShell(false)) { false }
            val base = "http://127.0.0.1:${server.localPort}"
            val r = ex.execute(call("download_file", "url" to "$base/go"))
            assertTrue(r.output, r.ok)
            assertEquals("отчёт", File(root, "Download/отчёт.txt").readText())
            // Same name again → unique copy instead of overwriting.
            assertTrue(ex.execute(call("download_file", "url" to "$base/go")).ok)
            assertTrue(File(root, "Download/отчёт (1).txt").isFile)
            val toDir = ex.execute(call("download_file", "url" to "$base/plain/data.csv", "path" to "~/Documents/"))
            assertTrue(toDir.output, toDir.ok)
            assertEquals("a,b\n1,2", File(root, "Documents/data.csv").readText())
            assertEquals(3, platform.created.size)
            assertTrue(ex.touched.contains(File(root, "Download/отчёт.txt").path))
            assertFalse(ex.execute(call("download_file", "url" to "$base/missing")).ok)
            assertFalse(ex.execute(call("download_file", "url" to "file:///etc/passwd")).ok)
        } finally {
            server.close()
        }
    }

    @Test
    fun attachAndOpenFiles() {
        val root = tmp.newFolder("st2")
        val platform = FakePlatform(root)
        val ex = ToolExecutor(platform, FakeRootShell(false)) { false }
        File(root, "Documents").mkdirs()
        File(root, "Documents/report.pdf").writeText("%PDF")
        val r = ex.execute(call("attach_file", "path" to "Документы/report.pdf"))
        assertTrue(r.output, r.ok)
        assertEquals("report.pdf", platform.attached.single().name)
        assertFalse(ex.execute(call("attach_file", "path" to "~/Documents")).ok)
        assertFalse(ex.execute(call("attach_file", "path" to "~/nope.pdf")).ok)
        assertTrue(ex.execute(call("open_file", "path" to "~/Documents/report.pdf")).ok)
        assertTrue(platform.opened.last().endsWith("report.pdf"))
    }

    @Test
    fun fileNameParsing() {
        assertEquals("a b.pdf", ToolExecutor.fileNameFrom("attachment; filename=\"a b.pdf\""))
        assertEquals("x.csv", ToolExecutor.fileNameFrom("inline; filename=x.csv; size=10"))
        assertEquals(null, ToolExecutor.fileNameFrom(null))
    }
}
