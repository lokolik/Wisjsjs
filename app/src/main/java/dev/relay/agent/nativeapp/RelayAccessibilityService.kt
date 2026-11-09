package dev.relay.agent.nativeapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Drives AI apps that have no web version or connector (e.g. Hark Pro on Android):
 * types the prompt into the app's input, taps "send" and reads the new text on screen.
 * Works with and without root; root only helps to enable the service automatically.
 */
class RelayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun rootOf(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) return it }
        return try {
            windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == pkg }
        } catch (e: Exception) {
            null
        }
    }

    /** Accessibility services are exempt from background activity-start limits. */
    fun bringToFront(activity: Class<*>) {
        startActivity(
            Intent(this, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    companion object {
        @Volatile
        var instance: RelayAccessibilityService? = null
            private set
    }
}

object NodeUtils {
    private val SEND_RX = Regex("send|submit|отправ|arrow.?up|\\bask\\b|\\bgo\\b")
    private val NEG_RX = Regex("voice|mic|record|attach|camera|photo|plus|\\badd\\b|голос|микроф|прикреп|камер|фото|stop|стоп")

    fun walk(root: AccessibilityNodeInfo?, limit: Int = 4000, fn: (AccessibilityNodeInfo) -> Unit) {
        if (root == null) return
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var n = 0
        while (stack.isNotEmpty() && n < limit) {
            val node = stack.removeLast()
            n++
            fn(node)
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }
    }

    /** Visible, non-editable texts in on-screen order. */
    fun texts(root: AccessibilityNodeInfo?): List<String> {
        val out = mutableListOf<String>()
        walk(root) { n ->
            if (!n.isVisibleToUser || n.isEditable) return@walk
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) out += t
        }
        return out
    }

    fun findEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        val r = Rect()
        walk(root) { n ->
            if (n.isEditable && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                val score = r.bottom + if (n.isFocused) 100_000 else 0
                if (score > bestScore) {
                    bestScore = score
                    best = n
                }
            }
        }
        return best
    }

    fun findSend(root: AccessibilityNodeInfo?, edit: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val er = Rect()
        edit.getBoundsInScreen(er)
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val r = Rect()
        walk(root) { n ->
            if (!n.isClickable || !n.isVisibleToUser || n.isEditable) return@walk
            val label = listOfNotNull(n.text, n.contentDescription, n.viewIdResourceName).joinToString(" ").lowercase()
            var s = 0
            if (SEND_RX.containsMatchIn(label)) s += 10
            if (NEG_RX.containsMatchIn(label)) s -= 8
            n.getBoundsInScreen(r)
            if (r.centerY() in (er.top - 120)..(er.bottom + 120)) s += 3
            if (r.left >= er.centerX()) s += 2
            if (s > bestScore) {
                bestScore = s
                best = n
            }
        }
        return if (bestScore >= 5) best else null
    }
}
