package dev.phoneagent.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Device
import dev.phoneagent.core.run.Frame
import dev.phoneagent.core.run.Target
import dev.phoneagent.core.ocr.PpOcr
import dev.phoneagent.core.run.Pixels
import dev.phoneagent.core.run.TextRecognizer
import dev.phoneagent.core.run.UiNode
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The accessibility service, and the [Device] the runner drives. Reads the window
 * trees into [UiNode]s, acts through node actions (falling back to a gesture at the
 * node's centre) and reports when the UI has settled. Blocking calls must come from
 * a worker thread; events arrive on the main thread.
 */
class AgentService : AccessibilityService(), Device {

    @Volatile private var lastChangeAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // OCR: loaded once per process (31 MB of weights), only by runs whose pack needs it.
    private val ocr by lazy {
        fun bytes(name: String) = assets.open("ocr/$name").use { it.readBytes() }
        PpOcr(bytes("det.onnx"), bytes("rec.onnx"), assets.open("ocr/dict.txt").bufferedReader().readLines())
    }
    private val ocrReader = object : TextRecognizer {
        override fun recognize(pixels: Pixels, region: Box?) = ocr.recognize(pixels, region)
        override fun readLine(pixels: Pixels, region: Box) = ocr.readLine(pixels, region)
    }
    @Volatile private var recognizer: TextRecognizer? = null

    /**
     * Turns OCR on for the next run (or off). On, it loads and warms up the models in the
     * background, so that cost overlaps launching the target app instead of the first OCR step.
     */
    fun useOcr(on: Boolean) {
        recognizer = if (on) ocrReader else null
        if (on) Thread { resources.displayMetrics.let { ocr.warmUp(it.widthPixels, it.heightPixels) } }.start()
    }
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
        return Frame(rootInActiveWindow?.packageName?.toString(), tree, recent, displayId, recognizer, shooter = { screenshot(displayId) })
    }

    /**
     * Blocks the calling worker thread for one screenshot; the pixels stay in memory only. The
     * system refuses one taken too soon after the previous (Neo5: ~0.35–0.45 s apart), so a
     * refusal is retried until [SCREENSHOT_RETRY_MS] has passed. Null if it still fails.
     */
    private fun screenshot(displayId: Int): Pixels? {
        val deadline = nowMs() + SCREENSHOT_RETRY_MS
        while (true) {
            val (pixels, tooSoon) = screenshotOnce(displayId)
            if (pixels != null || !tooSoon || nowMs() >= deadline) return pixels
            Thread.sleep(SCREENSHOT_POLL_MS)
        }
    }

    /** One attempt: the pixels, or null and whether the system said it was too soon. */
    private fun screenshotOnce(displayId: Int): Pair<Pixels?, Boolean> {
        val done = CountDownLatch(1)
        var pixels: Pixels? = null
        var tooSoon = false
        takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bitmap != null) {
                        val argb = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        pixels = Pixels(bitmap.width, bitmap.height, argb)
                        bitmap.recycle()
                    }
                }
                done.countDown()
            }

            override fun onFailure(errorCode: Int) {
                tooSoon = errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                if (!tooSoon) Log.i("PhoneAgent", "screenshot failed: $errorCode")
                done.countDown()
            }
        })
        done.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return pixels to tooSoon
    }

    override fun launch(packageName: String): Boolean {
        // shortcut: main display only; a virtual display would pass ActivityOptions.setLaunchDisplayId.
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // Only report success once the app is really in front: vivo may hold it behind a jump-confirm dialog.
        var deadline = nowMs() + LAUNCH_TIMEOUT_MS
        var asked = false
        try {
            while (nowMs() < deadline) {
                if (rootInActiveWindow?.packageName == packageName) return true
                // vivo blocks taps on this dialog from accessibility (node clicks and gestures), so ask the user.
                if (!asked && jumpDialogShown()) {
                    asked = true
                    deadline = nowMs() + USER_CONFIRM_TIMEOUT_MS
                    showHint("Phone Agent：请在下方弹窗里点“始终打开”，任务会继续")
                }
                // Short slices: a live camera preview never goes quiet, so a full settle would eat the whole timeout.
                awaitSettle(minOf(LAUNCH_POLL_MS, deadline - nowMs()))
            }
            Log.i("PhoneAgent", "launch $packageName timed out; active=${rootInActiveWindow?.packageName}")
            return false
        } finally {
            if (asked) hideHint()
        }
    }

    // A strip at the top of the screen, above the dialog at the bottom; touches pass through it.
    private var hint: TextView? = null

    /** A strip over the top of the screen; vivo drops toasts from a backgrounded app, this always shows. */
    fun showHint(text: String) = mainHandler.post {
        val view = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(220, 30, 30, 30))
            setPadding(48, 120, 48, 48)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        getSystemService(WindowManager::class.java).addView(view, params)
        hint = view
    }

    fun hideHint() = mainHandler.post {
        hint?.let { getSystemService(WindowManager::class.java).removeView(it) }
        hint = null
    }

    /** The system dialog "<this app> 想要打开 <target>"; the name and the phrase may sit in separate nodes. */
    private fun jumpDialogShown(): Boolean {
        val me = applicationInfo.loadLabel(packageManager).toString()
        return (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)).any { root ->
            root.findAccessibilityNodeInfosByText("想要打开").isNotEmpty() &&
                root.findAccessibilityNodeInfosByText(me).isNotEmpty()
        }
    }

    override fun press(target: Target, long: Boolean): Boolean {
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val info = target.node?.let { lastNodes[it] }
        // The screen may have changed since capture (a new message moved the bubble, a menu closed): never act on
        // stale coordinates or a recycled view; report a miss so the runner finds the target again.
        if (info != null && (!info.refresh() || Rect().also(info::getBoundsInScreen) != target.bounds.toRect())) return false
        val node = info?.let { actionable(it, long) }
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
        private const val LAUNCH_TIMEOUT_MS = 8_000L
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L
        private const val SCREENSHOT_RETRY_MS = 1_000L
        private const val SCREENSHOT_POLL_MS = 50L
        private const val LAUNCH_POLL_MS = 300L
        private const val USER_CONFIRM_TIMEOUT_MS = 30_000L
    }
}

private fun Box.toRect() = Rect(left, top, right, bottom)
