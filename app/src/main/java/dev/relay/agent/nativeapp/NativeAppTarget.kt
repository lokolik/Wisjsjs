package dev.relay.agent.nativeapp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.relay.agent.agent.ChatTarget
import dev.relay.agent.agent.TargetReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Chat inside an installed app, driven through [RelayAccessibilityService]. */
class NativeAppTarget(
    private val context: Context,
    private val packageName: String,
    private val returnTo: Class<*>,
) : ChatTarget {

    override suspend fun send(text: String, onPartial: (String) -> Unit): TargetReply = withContext(Dispatchers.Main) {
        val svc = RelayAccessibilityService.instance
            ?: throw IllegalStateException("Включите службу «Relay» в Спец. возможностях: Настройки Relay → Разрешения.")
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalStateException("Приложение $packageName не установлено")
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            var edit: AccessibilityNodeInfo? = null
            val openDeadline = SystemClock.uptimeMillis() + 12_000
            while (edit == null && SystemClock.uptimeMillis() < openDeadline) {
                delay(400)
                edit = NodeUtils.findEditable(svc.rootOf(packageName))
            }
            if (edit == null) throw IllegalStateException("Не нашёл поле ввода в приложении. Откройте нужный чат в нём и повторите.")

            val before = NodeUtils.texts(svc.rootOf(packageName)).groupingBy { it }.eachCount()
            edit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (!edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                throw IllegalStateException("Приложение не принимает текст через спец. возможности")
            }
            delay(600)

            val root = svc.rootOf(packageName)
            val edit2 = NodeUtils.findEditable(root) ?: edit
            var sent = NodeUtils.findSend(root, edit2)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!sent && Build.VERSION.SDK_INT >= 30) {
                sent = edit2.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (!sent) throw IllegalStateException("Не нашёл кнопку «Отправить» в приложении")

            val prompt = text.trim()
            var last = ""
            var lastChange = SystemClock.uptimeMillis()
            val deadline = lastChange + 180_000
            var timedOut = true
            while (SystemClock.uptimeMillis() < deadline) {
                delay(700)
                val r = svc.rootOf(packageName) ?: continue
                val remaining = HashMap(before)
                val fresh = NodeUtils.texts(r)
                    .filter { t ->
                        val c = remaining[t] ?: 0
                        if (c > 0) {
                            remaining[t] = c - 1
                            false
                        } else {
                            true
                        }
                    }
                    .filter { t -> t != prompt && !(t.length >= 12 && prompt.contains(t)) }
                val cur = fresh.joinToString("\n").trim()
                val now = SystemClock.uptimeMillis()
                if (cur != last) {
                    last = cur
                    lastChange = now
                    if (cur.isNotEmpty()) onPartial(cur)
                } else if (cur.isNotEmpty() && now - lastChange > 3500) {
                    timedOut = false
                    break
                }
            }
            TargetReply(last, emptyList(), timedOut)
        } finally {
            svc.bringToFront(returnTo)
        }
    }
}
