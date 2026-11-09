package dev.relay.agent.nativeapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Lets Relay drive AI apps that have no website or connector (Hark Pro, Notion, ChatGPT apps…):
 * reads the screen, types into the chat box, taps buttons and scrolls. Works with and without root;
 * root only enables the service automatically.
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

    /** All windows of [pkg] (main window plus popups/sheets) as one snapshot. */
    fun capture(pkg: String): LiveTree? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows.forEach { w -> w.root?.let { if (it.packageName?.toString() == pkg) roots += it } }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) roots += it }
        return LiveTree.capture(roots.reversed())
    }

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
                .build()
            val started = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!started && cont.isActive) cont.resume(false)
        }

    suspend fun tap(x: Float, y: Float): Boolean = swipe(x, y, x + 1, y + 1, 60)

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun setClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Relay", text))
    }

    /** Accessibility services may start activities from the background. */
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

/** Snapshot ([root]) plus the live nodes needed to act on it. */
class LiveTree(val root: UiNode, private val infos: List<AccessibilityNodeInfo>) {

    fun perform(n: UiNode, action: Int, args: Bundle? = null): Boolean {
        val info = infos.getOrNull(n.index) ?: return false
        return try {
            info.performAction(action, args)
        } catch (e: Exception) {
            false
        }
    }

    /** Scroll by accessibility action; visual direction first (ACTION_SCROLL_UP/DOWN), then backward/forward. */
    fun scroll(n: UiNode, up: Boolean): Boolean {
        val info = infos.getOrNull(n.index) ?: return false
        return try {
            val ids = info.actionList.map { it.id }
            val visual = if (up) android.R.id.accessibilityActionScrollUp else android.R.id.accessibilityActionScrollDown
            if (visual in ids && info.performAction(visual)) return true
            val legacy = if (up) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            legacy in ids && info.performAction(legacy)
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        fun capture(roots: List<AccessibilityNodeInfo>, limit: Int = 4000): LiveTree? {
            if (roots.isEmpty()) return null
            val infos = ArrayList<AccessibilityNodeInfo>()
            val tops = ArrayList<UiNode>()
            val rect = Rect()
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, UiNode?>>()
            for (r in roots.reversed()) stack.addLast(r to null)
            while (stack.isNotEmpty() && infos.size < limit) {
                val (info, parent) = stack.removeLast()
                info.getBoundsInScreen(rect)
                val showingHint = Build.VERSION.SDK_INT >= 26 && info.isShowingHintText
                val hint = if (Build.VERSION.SDK_INT >= 26) info.hintText?.toString() else null
                val node = UiNode(
                    index = infos.size,
                    text = if (showingHint) null else info.text?.toString(),
                    desc = info.contentDescription?.toString(),
                    hint = hint ?: if (showingHint) info.text?.toString() else null,
                    viewId = info.viewIdResourceName,
                    cls = info.className?.toString(),
                    bounds = Box(rect.left, rect.top, rect.right, rect.bottom),
                    clickable = info.isClickable,
                    longClickable = info.isLongClickable,
                    editable = info.isEditable,
                    scrollable = info.isScrollable,
                    focused = info.isFocused,
                    enabled = info.isEnabled,
                    visible = info.isVisibleToUser,
                    selected = info.isSelected,
                )
                infos += info
                if (parent == null) tops += node else parent.add(node)
                for (i in info.childCount - 1 downTo 0) {
                    val child = try {
                        info.getChild(i)
                    } catch (e: Exception) {
                        null
                    }
                    if (child != null) stack.addLast(child to node)
                }
            }
            if (tops.isEmpty()) return null
            val root = if (tops.size == 1) {
                tops[0]
            } else {
                UiNode(-1, cls = "relay.Windows", bounds = tops.map { it.bounds }.reduce { a, b -> a.union(b) }).also { r -> tops.forEach { r.add(it) } }
            }
            return LiveTree(root, infos)
        }
    }
}
