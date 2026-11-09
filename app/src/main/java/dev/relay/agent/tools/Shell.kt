package dev.relay.agent.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    fun combined(): String = buildString {
        if (stdout.isNotBlank()) append(stdout.trimEnd())
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append('\n')
            append("[stderr] ").append(stderr.trimEnd())
        }
        if (timedOut) {
            if (isNotEmpty()) append('\n')
            append("[timeout]")
        }
    }
}

open class Shell(
    private val suBinary: String = "su",
    private val shBinary: String = "sh",
) {
    @Volatile
    private var rootCache: Boolean? = null

    /** null = not checked yet. */
    val cachedRoot: Boolean? get() = rootCache

    /** Cheap check that does not trigger the Magisk/KernelSU grant dialog. */
    open fun suPresent(): Boolean {
        if (SU_PATHS.any { File(it).exists() }) return true
        val path = System.getenv("PATH") ?: return false
        return path.split(':').any { it.isNotEmpty() && File(it, suBinary).exists() }
    }

    /** May show the root manager's grant dialog, so the timeout is generous. */
    open fun hasRoot(refresh: Boolean = false): Boolean {
        if (!refresh) rootCache?.let { return it }
        val result = try {
            exec(listOf(suBinary, "-c", "id"), timeoutSec = 25)
        } catch (e: Exception) {
            null
        }
        val has = result != null && result.ok && result.stdout.contains("uid=0")
        rootCache = has
        return has
    }

    open fun run(command: String, root: Boolean, timeoutSec: Long = 60, workDir: File? = null): ShellResult {
        val cmd = if (root) listOf(suBinary, "-c", command) else listOf(shBinary, "-c", command)
        return exec(cmd, timeoutSec, workDir)
    }

    protected fun exec(cmd: List<String>, timeoutSec: Long, workDir: File? = null): ShellResult {
        val pb = ProcessBuilder(cmd)
        if (workDir != null && workDir.isDirectory) pb.directory(workDir)
        val process = pb.start()
        runCatching { process.outputStream.close() }
        val out = StreamCollector(process.inputStream).also { it.start() }
        val err = StreamCollector(process.errorStream).also { it.start() }
        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        out.join(1000)
        err.join(1000)
        return ShellResult(if (finished) process.exitValue() else -1, out.text(), err.text(), !finished)
    }

    companion object {
        private val SU_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/data/adb/magisk", "/data/adb/ksu",
        )

        /** POSIX single-quote escaping. */
        fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }
}

private class StreamCollector(
    private val input: InputStream,
    private val limit: Int = 512 * 1024,
) : Thread() {
    private val buf = ByteArrayOutputStream()

    init {
        isDaemon = true
    }

    override fun run() {
        try {
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                synchronized(buf) {
                    val room = limit - buf.size()
                    if (room > 0) buf.write(chunk, 0, minOf(n, room))
                }
            }
        } catch (_: Exception) {
        }
    }

    fun text(): String = synchronized(buf) { String(buf.toByteArray(), Charsets.UTF_8) }
}
