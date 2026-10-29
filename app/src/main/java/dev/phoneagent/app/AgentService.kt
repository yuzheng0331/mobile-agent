package dev.phoneagent.app

import android.accessibilityservice.AccessibilityService
import android.view.inputmethod.InputMethodManager
import android.provider.Settings
import android.content.ComponentName
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.Gravity
import android.view.KeyEvent
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
import dev.phoneagent.app.shell.AdbLauncher
import dev.phoneagent.app.shell.Shell
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The accessibility service, and the [Device] the runner drives. Reads the window
 * trees into [UiNode]s, acts through node actions (falling back to a gesture at the
 * node's centre) and reports when the UI has settled. Blocking calls must come from
 * a worker thread; events arrive on the main thread.
 */
class AgentService : AccessibilityService(), Device {

    @Volatile private var lastChangeAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // OCR: loaded once per process, only by runs whose pack needs it. Recognition on the GPU (about
    // 3x faster, same readings), or LiteRT's CPU when the GPU refuses it; detection on the CPU.
    private val ocr by lazy {
        val start = SystemClock.uptimeMillis()
        val models = LiteRtModels(this)
        Log.i("PhoneAgent", "OCR 识别用 ${models.accelerator}，加载 ${SystemClock.uptimeMillis() - start} ms")
        PpOcr(models, assets.open("ocr/dict.txt").bufferedReader().readLines(), LiteRtModels.CONFIG)
    }
    private val ocrReader = object : TextRecognizer {
        // Sizes, counts and times only, never the text.
        override fun recognize(pixels: Pixels, region: Box?) = ocr.recognize(pixels, region).also {
            val area = region?.let { r -> "${r.right - r.left}x${r.bottom - r.top}" } ?: "整屏"
            Log.i("PhoneAgent", "OCR $area：${it.size} 框（${ocr.timings}）")
        }
        override fun readLine(pixels: Pixels, region: Box) = ocr.readLine(pixels, region)
    }
    @Volatile private var recognizer: TextRecognizer? = null

    /**
     * Turns OCR on for the next run (or off). On, it loads and warms up the models in the
     * background, so that cost overlaps launching the target app instead of the first OCR step.
     */
    fun useOcr(on: Boolean) {
        recognizer = if (on) ocrReader else null
        // The full display, as screenshots are (displayMetrics leaves out the navigation bar): a warm-up at
        // another size would leave the first whole-screen OCR to set up its own.
        if (on) Thread { getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { ocr.warmUp(it.width(), it.height()) } }.start()
    }
    private val toasts = ArrayDeque<Pair<Long, String>>()

    // Nodes of the last capture, to act on the real node behind a target.
    @Volatile private var lastNodes: Map<UiNode, AccessibilityNodeInfo> = emptyMap()
    @Volatile private var lastDisplayId = 0

    /**
     * The display runs act on: 0, or background mode's virtual display ([Shell]). Reading, screenshots and
     * gestures already go by display; launching and back go through the shell server there.
     */
    @Volatile var display = 0

    /**
     * How far the virtual display's content sits below the main display's top: the main status bar, which a
     * virtual display lacks (vivo draws none there, even with system decorations). The virtual display is made
     * that much shorter, and what we read of it is moved down by this and what we press moved up, so packs
     * see the same coordinates as on the main display (WeChat: title, list and tabs all line up).
     */
    @Volatile var displayTop = 0

    // The shift for what was read from / is done on a display.
    private fun shift(displayId: Int) = if (displayId != 0 && displayId == display) displayTop else 0

    override fun onServiceConnected() {
        instance = this
        // A run cut short (crash, killed) may have left our input method selected: give the keyboard back.
        useFastCores(false)
        // At boot and after an update. Without the role, start the shell server over wireless debugging: it gives
        // the role back as it attaches. If that can't happen now (no Wi-Fi, not paired), a lost role is worth a word.
        if (Background.wanted(this) && !Background.hasRole(this)) Thread {
            if (!AdbLauncher.start(this) && Background.lostRole(this)) {
                showHint("Phone Agent：后台运行的授权没了（${AdbLauncher.problem}）。解决后再跑后台任务会自动恢复，在这之前任务在前台跑")
                mainHandler.postDelayed({ hideHint() }, ROLE_HINT_MS)
            }
        }.start()
    }

    /**
     * On: selects [AgentIme] so this process gets all cores while the target app is in front,
     * remembering the user's keyboard first (persisted, so a crash can still restore it). Off:
     * switches back to that keyboard. False when it couldn't, e.g. the user hasn't enabled
     * our input method in the system settings; the run then goes on, only slower.
     */
    fun useFastCores(on: Boolean): Boolean {
        val saved = getSharedPreferences(KEYBOARD_PREFS, MODE_PRIVATE)
        val ours = ComponentName(this, AgentIme::class.java).flattenToShortString()
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (!on) {
            val previous = saved.getString(PREVIOUS_KEYBOARD, null) ?: return true
            if (current == ours && !softKeyboardController.switchToInputMethod(previous)) return false
            saved.edit().remove(PREVIOUS_KEYBOARD).apply()
            return true
        }
        if (current == ours) return true
        if (!isEnabledInputMethod(ours)) return false
        saved.edit().putString(PREVIOUS_KEYBOARD, current).commit()
        return softKeyboardController.switchToInputMethod(ours)
    }

    private fun isEnabledInputMethod(id: String) =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == id }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == packageName) return
        when (event.eventType) {
            // Not tied to the run's display: a toast from an app on the virtual display doesn't come with its id.
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                if (event.className?.contains("Toast") == true) {
                    synchronized(toasts) { toasts.addLast(SystemClock.uptimeMillis() to event.text.joinToString(" ")) }
                }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                // In background mode the user is busy on the main display: only the run's display says when the UI has settled.
                if (display != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && event.displayId != display) return
                lastChangeAt = SystemClock.uptimeMillis()
            }
        }
    }

    // ---- Device

    override fun nowMs(): Long = SystemClock.uptimeMillis()

    override fun capture(): Frame = capture(display)

    override fun capture(displayId: Int): Frame {
        val nodes = IdentityHashMap<UiNode, AccessibilityNodeInfo>()
        val roots = (windowsOnAllDisplays[displayId] ?: emptyList())
            .mapNotNull { it.root }
            .filter { it.packageName != packageName }
        val tree = UiNode(children = roots.map { convert(it, nodes, shift(displayId)) })
        lastNodes = nodes
        lastDisplayId = displayId
        val since = nowMs() - TOAST_WINDOW_MS
        val recent = synchronized(toasts) {
            while (toasts.isNotEmpty() && toasts.first().first < since) toasts.removeFirst()
            toasts.map { it.second }
        }
        return Frame(foreground(displayId), tree, recent, displayId, recognizer, shooter = { screenshot(displayId) })
    }

    /** The app in front on a display: the active window's on the main one, the topmost app window's elsewhere. */
    fun foreground(displayId: Int): String? =
        if (displayId == 0) rootInActiveWindow?.packageName?.toString()
        else windowsOnAllDisplays[displayId]?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName?.toString()

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
        // The system holds on to the callback long after it ran, and with it whatever the callback holds: a 10 MB
        // screenshot each, which filled the 256 MB heap within one background run. So the pixels only pass through
        // this slot, emptied below, and a callback that comes after we stopped waiting keeps nothing.
        val slot = AtomicReference<Pixels?>()
        val waiting = AtomicBoolean(true)
        val tooSoon = AtomicBoolean()
        // A black strip where the main display has its status bar (displayTop).
        val top = shift(displayId)
        takeScreenshot(displayId, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                result.hardwareBuffer.use { buffer ->
                    if (!waiting.get()) return
                    val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    if (bitmap != null) {
                        val argb = IntArray(bitmap.width * (top + bitmap.height))
                        bitmap.getPixels(argb, top * bitmap.width, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        slot.set(Pixels(bitmap.width, top + bitmap.height, argb))
                        bitmap.recycle()
                    }
                }
                if (!waiting.get()) slot.set(null)
                done.countDown()
            }

            override fun onFailure(errorCode: Int) {
                tooSoon.set(errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                if (!tooSoon.get()) Log.i("PhoneAgent", "screenshot failed: $errorCode")
                done.countDown()
            }
        })
        done.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        waiting.set(false)
        return slot.getAndSet(null) to tooSoon.get()
    }

    override fun launch(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        val display = display
        // An app is on one display at a time: starting it here would pull it from under the user (MainActivity says why).
        if (display != 0 && foreground(0) == packageName) return false
        if (display == 0) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (Background.owns(display)) {
            // Role mode: our display is trusted and public, so we may start other apps on it. The display is new
            // each run (Background.release), so the app starts at its start screen without a back key.
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle())
        } else {
            // An app may not start another app on a display it doesn't own; the shell user may.
            val started = runCatching { Shell.server?.start(intent.component!!.flattenToString(), display) == true }.getOrDefault(false)
            if (!started) return false
        }
        // Only report success once the app is really in front: vivo may hold it behind a jump-confirm dialog.
        var deadline = nowMs() + LAUNCH_TIMEOUT_MS
        var asked = false
        try {
            while (nowMs() < deadline) {
                if (foreground(display) == packageName) return true
                // vivo blocks taps on this dialog from accessibility (node clicks and gestures), so ask the user.
                if (display == 0 && !asked && jumpDialogShown()) {
                    asked = true
                    deadline = nowMs() + USER_CONFIRM_TIMEOUT_MS
                    showHint("Phone Agent：请在下方弹窗里点“始终打开”，任务会继续")
                }
                // Short slices: a live camera preview never goes quiet, so a full settle would eat the whole timeout.
                awaitSettle(minOf(LAUNCH_POLL_MS, deadline - nowMs()))
            }
            Log.i("PhoneAgent", "launch $packageName timed out; active=${foreground(display)}")
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
        // Targets are in the packs' (main display) coordinates; the virtual display's are displayTop higher.
        val bounds = target.bounds.toRect().apply { offset(0, -shift(lastDisplayId)) }
        // The screen may have changed since capture (a new message moved the bubble, a menu closed): never act on
        // stale coordinates or a recycled view; report a miss so the runner finds the target again.
        if (info != null && (!info.refresh() || Rect().also(info::getBoundsInScreen) != bounds)) return false
        val node = info?.let { actionable(it, long) }
        if (node?.performAction(action) == true) return true
        return gesture(bounds.exactCenterX(), bounds.exactCenterY(), lastDisplayId, if (long) LONG_PRESS_MS else TAP_MS)
    }

    /** A press at a point of a display, in that display's own coordinates; returns once it is over. */
    private fun gesture(x: Float, y: Float, displayId: Int, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).setDisplayId(displayId).build()
        // Wait until the press is over: dispatchGesture returns at once, and the settle that follows
        // would otherwise end mid-press, before the long-press menu exists (a wasted ~3 s OCR).
        val done = CountDownLatch(1)
        var completed = false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) { completed = true; done.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription) = done.countDown()
        }
        if (!dispatchGesture(gesture, callback, mainHandler)) return false
        done.await(stroke.duration + GESTURE_GRACE_MS, TimeUnit.MILLISECONDS)
        return completed
    }

    /**
     * The global back goes to the top focused display, which stays the main one (the virtual display may not
     * steal focus), so a virtual display's back is never global: the shell server's key, or else the app's own
     * back button, or in role mode a tap where title bars keep it (WeChat's chat page has no nodes at all).
     * Whether that changed the screen is the caller's check (the runner compares screenshots).
     */
    override fun back(): Boolean {
        val display = display
        if (display == 0) return performGlobalAction(GLOBAL_ACTION_BACK)
        if (runCatching { Shell.server?.key(display, KeyEvent.KEYCODE_BACK) == true }.getOrDefault(false)) return true
        val button = windowsOnAllDisplays[display].orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { it.root?.let(::backButton) }
        if (button?.let { actionable(it, long = false) }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        // shortcut: one spot for every app (WeChat: arrow at x 0-130, title bar y 0-126 on the virtual display),
        // per-app spots in the pack if an app puts it elsewhere.
        if (!Background.owns(display)) return false
        val dp = resources.displayMetrics.density
        return gesture(BACK_X_DP * dp, BACK_Y_DP * dp, display, TAP_MS)
    }

    private fun backButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && (node.contentDescription?.toString() in BACK_LABELS || node.text?.toString() in BACK_LABELS)) return node
        return (0 until node.childCount).firstNotNullOfOrNull { i -> node.getChild(i)?.let(::backButton) }
    }

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

    /** [top]: how far to move the bounds down (see [displayTop]). */
    private fun convert(info: AccessibilityNodeInfo, nodes: MutableMap<UiNode, AccessibilityNodeInfo>, top: Int): UiNode {
        val rect = Rect().also(info::getBoundsInScreen)
        val node = UiNode(
            text = info.text?.toString(),
            desc = info.contentDescription?.toString(),
            id = info.viewIdResourceName,
            className = info.className?.toString(),
            bounds = Box(rect.left, rect.top + top, rect.right, rect.bottom + top),
            clickable = info.isClickable,
            longClickable = info.isLongClickable,
            scrollable = info.isScrollable,
            children = (0 until info.childCount).mapNotNull { info.getChild(it) }.map { convert(it, nodes, top) },
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
        private const val GESTURE_GRACE_MS = 1_000L
        private const val MAX_ANCESTORS = 8
        private const val TOAST_WINDOW_MS = 5_000L
        private const val LAUNCH_TIMEOUT_MS = 8_000L
        private const val KEYBOARD_PREFS = "keyboard"
        private const val PREVIOUS_KEYBOARD = "previous"
        private const val SCREENSHOT_TIMEOUT_MS = 3_000L
        private const val SCREENSHOT_RETRY_MS = 1_000L
        private const val SCREENSHOT_POLL_MS = 50L
        private const val LAUNCH_POLL_MS = 300L
        private const val USER_CONFIRM_TIMEOUT_MS = 30_000L
        private const val ROLE_HINT_MS = 8_000L
        private val BACK_LABELS = setOf("返回", "Navigate up")
        private const val BACK_X_DP = 20f
        private const val BACK_Y_DP = 24f
    }
}

private fun Box.toRect() = Rect(left, top, right, bottom)
