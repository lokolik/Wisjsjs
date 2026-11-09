package dev.relay.agent.nativeapp

import android.content.Intent
import dev.relay.agent.agent.Attachment
import dev.relay.agent.agent.ChatTarget
import dev.relay.agent.agent.TargetReply
import dev.relay.agent.agent.ToolProtocol
import dev.relay.agent.tools.ToolExecutor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What the app driver needs from Relay itself. */
interface NativeHost {
    /** Brings Relay back to the foreground; true once it has window focus. */
    suspend fun returnToRelay(): Boolean
    fun canReadClipboard(): Boolean
    suspend fun readClipboard(): String?
    suspend fun writeClipboard(text: String?)
    /** ACTION_SEND intent targeted at [pkg], or null if the app does not accept shared files. */
    fun shareIntent(pkg: String, attachments: List<Attachment>): Intent?
}

/** Chat inside an installed app (Hark Pro, ChatGPT, Claude, Notion…), driven by [AppDriver]. */
class NativeAppTarget(
    private val driver: AppDriver,
    private val host: NativeHost,
    private val shareMode: String,
    private val autoDownload: () -> Boolean,
    private val takeNav: () -> AppDriver.Nav?,
    private val onFilesTapped: (List<String>) -> Unit = {},
) : ChatTarget {

    override suspend fun send(text: String, attachments: List<Attachment>, onPartial: (String) -> Unit): TargetReply {
        val clipKnown = host.canReadClipboard()
        val prevClip = if (clipKnown) host.readClipboard() else null
        var prompt = text
        var share: Intent? = null
        if (attachments.isNotEmpty()) {
            share = if (shareMode != "off") host.shareIntent(driver.pkg, attachments) else null
            if (share == null) prompt = inline(text, attachments)
        }
        val nav = takeNav()
        try {
            val r = driver.send(
                text = prompt,
                nav = if (share == null) nav else null,
                share = share,
                waitSendMs = if (share != null) 45_000 else 2_500,
                autoDownload = autoDownload(),
                onPartial = onPartial,
            )
            if (r.filesTapped.isNotEmpty()) onFilesTapped(r.filesTapped)
            var reply = r.lines.joinToString("\n").trim()
            var blocks = emptyList<String>()
            host.returnToRelay()
            if (r.copied) {
                val clip = host.readClipboard()
                // The copied markdown is complete and keeps ``` fences; use it if it is really this reply.
                if (!clip.isNullOrBlank() && clip != prevClip && agrees(clip, reply)) {
                    reply = clip.trim()
                    blocks = ToolProtocol.fencedBlocks(clip)
                }
            }
            return TargetReply(reply, blocks, r.timedOut)
        } finally {
            withContext(NonCancellable) {
                host.returnToRelay()
                if (clipKnown && host.readClipboard() != prevClip) host.writeClipboard(prevClip)
            }
        }
    }

    private fun inline(text: String, attachments: List<Attachment>): String {
        val sb = StringBuilder(text)
        for (a in attachments) {
            if (!a.isText || a.size > INLINE_LIMIT) {
                throw DriverException(
                    "${driver.appName} не принимает файлы через «Поделиться», а «${a.name}» нельзя вставить текстом. " +
                        "Прикрепите его в приложении вручную или выберите сервис с поддержкой файлов.",
                )
            }
            sb.append("\n\n[Файл: ").append(a.name).append("]\n```\n").append(a.file.readText().trimEnd()).append("\n```")
        }
        return sb.toString()
    }

    companion object {
        private const val INLINE_LIMIT = 200L * 1024
        private val MD = Regex("""[*_`#>\[\]]""")

        /** True when copied text and on-screen text are the same reply (markdown vs rendered text). */
        fun agrees(clip: String, screen: String): Boolean {
            val c = ScreenAnalyzer.norm(clip.replace(MD, ""))
            val s = ScreenAnalyzer.norm(screen.replace(MD, ""))
            if (s.isEmpty()) return c.isNotEmpty()
            val head = s.take(20)
            val tail = s.takeLast(20)
            return c.contains(head) || c.contains(tail) || s.contains(c.take(20))
        }

        const val MAX_ATTACH = ToolExecutor.MAX_ATTACH
    }
}
