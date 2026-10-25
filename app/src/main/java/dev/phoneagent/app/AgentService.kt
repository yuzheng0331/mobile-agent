package dev.phoneagent.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Device
import dev.phoneagent.core.run.Frame
import dev.phoneagent.core.run.Target
import dev.phoneagent.core.run.UiNode
import java.util.IdentityHashMap

/**
 * The accessibility service, and the [Device] the runner drives. Reads the window
 * trees into [UiNode]s, acts through node actions (falling back to a gesture at the
 * node's centre) and reports when the UI has settled. Blocking calls must come from
 * a worker thread; events arrive on the main thread.
 */
class AgentService : AccessibilityService(), Device {

    @Volatile private var lastChangeAt = 0L
    private val toasts = ArrayDeque<Pair<Long, String>>()

    // Nodes of the last capture, to act on the real node behind a target.
    @Volatile private var lastNodes: Map<UiNode, AccessibilityNodeInfo> = emptyMap()
    @Volatile private var lastDisplayId = 0

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (event.className?.contains("Toast") == true) {
                    synchronized(toasts) { toasts.addLast(SystemClock.uptimeMillis() to event.text.joinToString(" ")) }
                }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> lastChangeAt = SystemClock.uptimeMillis()
        }
    }

    // ---- Device

    override fun nowMs(): Long = SystemClock.uptimeMillis()

    override fun capture(displayId: Int): Frame {
        val nodes = IdentityHashMap<UiNode, AccessibilityNodeInfo>()
        val roots = (windowsOnAllDisplays[displayId] ?: emptyList())
            .mapNotNull { it.root }
            .filter { it.packageName != packageName }
        val tree = UiNode(children = roots.map { convert(it, nodes) })
        lastNodes = nodes
        lastDisplayId = displayId
        val since = nowMs() - TOAST_WINDOW_MS
        val recent = synchronized(toasts) {
            while (toasts.isNotEmpty() && toasts.first().first < since) toasts.removeFirst()
            toasts.map { it.second }
        }
        return Frame(rootInActiveWindow?.packageName?.toString(), tree, recent, displayId)
    }

    override fun launch(packageName: String): Boolean {
        // shortcut: main display only; a virtual display would pass ActivityOptions.setLaunchDisplayId.
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Only report success once the app is really in front: vivo may hold it behind a jump-confirm dialog.
        val deadline = nowMs() + LAUNCH_TIMEOUT_MS
        while (nowMs() < deadline) {
            if (rootInActiveWindow?.packageName == packageName) return true
            confirmJump()
            awaitSettle(deadline - nowMs())
        }
        return false
    }

    /**
     * The one exception to "only touch required apps" (owner's decision): the system dialog
     * "<this app> 想要打开 <target>". Taps "始终打开" when the dialog names this app, so it is
     * asked once per target; this app only ever launches the apps a pack requires.
     */
    private fun confirmJump() {
        val me = applicationInfo.loadLabel(packageManager).toString()
        for (root in windows.mapNotNull { it.root }) {
            // Same dialog window, but the app name and "想要打开" may sit in separate text nodes.
            val asks = root.findAccessibilityNodeInfosByText("想要打开").isNotEmpty() &&
                root.findAccessibilityNodeInfosByText(me).isNotEmpty()
            if (!asks) continue
            root.findAccessibilityNodeInfosByText("始终打开").firstOrNull()
                ?.let { actionable(it, long = false) }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
    }

    override fun press(target: Target, long: Boolean): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val node = target.node?.let { lastNodes[it] }?.let { actionable(it, long) }
        if (node?.performAction(action) == true) return true
        val path = Path().apply { moveTo(target.bounds.centerX.toFloat(), target.bounds.centerY.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, if (long) LONG_PRESS_MS else TAP_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).setDisplayId(lastDisplayId).build()
        return dispatchGesture(gesture, null, null)
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun awaitSettle(timeoutMs: Long): Boolean {
        val start = nowMs()
        val deadline = start + timeoutMs
        while (nowMs() < deadline) {
            // Settled = no UI event for SETTLE_MS, counted from this call at the earliest.
            if (nowMs() - maxOf(start, lastChangeAt) >= SETTLE_MS) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun convert(info: AccessibilityNodeInfo, nodes: MutableMap<UiNode, AccessibilityNodeInfo>): UiNode {
        val rect = Rect().also(info::getBoundsInScreen)
        val node = UiNode(
            text = info.text?.toString(),
            desc = info.contentDescription?.toString(),
            id = info.viewIdResourceName,
            className = info.className?.toString(),
            bounds = Box(rect.left, rect.top, rect.right, rect.bottom),
            clickable = info.isClickable,
            longClickable = info.isLongClickable,
            scrollable = info.isScrollable,
            children = (0 until info.childCount).mapNotNull { info.getChild(it) }.map { convert(it, nodes) },
        )
        nodes[node] = info
        return node
    }

    private fun actionable(info: AccessibilityNodeInfo, long: Boolean): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = info
        repeat(MAX_ANCESTORS) {
            val node = current ?: return null
            if (if (long) node.isLongClickable else node.isClickable) return node
            current = node.parent
        }
        return null
    }

    companion object {
        @Volatile var instance: AgentService? = null
            private set

        private const val SETTLE_MS = 300L
        private const val POLL_MS = 50L
        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 800L
        private const val MAX_ANCESTORS = 8
        private const val TOAST_WINDOW_MS = 5_000L
        private const val LAUNCH_TIMEOUT_MS = 5_000L
    }
}
