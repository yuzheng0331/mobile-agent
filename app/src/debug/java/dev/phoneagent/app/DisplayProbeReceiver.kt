package dev.phoneagent.app

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Target

/**
 * Debug only, for the virtual-display experiment (docs/virtual-display-experiments.md): asks the
 * accessibility service what it can do on one display. Logs counts, sizes and results, never text.
 *
 * adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display <id> --es do <what> ...
 *   windows                       every display's windows: type, package, node count
 *   shot                          one screenshot of the display: size, time, mean brightness
 *   ocr --es label <label>        whole-display OCR: how many boxes, and where the boxes containing <label> are
 *   find --es label <label>       where the node whose text or description is exactly <label> is
 *   tap --ei x <px> --ei y <px>   a gesture at that point of the display
 *   click --es label <label>      a node action on the node whose text or description is exactly <label>
 *   own --es flags <F,F,...>      the app itself (no shell) makes a 1080x2316 virtual display with these
 *                                 DisplayManager.VIRTUAL_DISPLAY_FLAG_* names; kept until the process dies
 *   start --es pkg <package>      the app itself starts <package> on that display (ActivityOptions.setLaunchDisplayId)
 */
class DisplayProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = AgentService.instance
        if (service == null) {
            Log.i(TAG, "accessibility service not connected")
            return
        }
        val pending = goAsync()
        // Screenshots and gestures block until the system answers: off the main thread, where their callbacks land.
        Thread {
            try {
                probe(service, intent.getIntExtra("display", 0), intent)
            } catch (e: Exception) {
                Log.i(TAG, "probe failed: $e")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun probe(service: AgentService, display: Int, intent: Intent) {
        when (val what = intent.getStringExtra("do")) {
            "windows" -> {
                val all = service.windowsOnAllDisplays
                for (i in 0 until all.size()) {
                    val windows = all.valueAt(i).joinToString { w ->
                        "${w.type}:${w.root?.packageName ?: "-"}:${w.root?.let(::count) ?: 0}${if (w.isFocused) "*" else ""}"
                    }
                    Log.i(TAG, "display ${all.keyAt(i)}: $windows")
                }
            }
            "shot" -> {
                val start = SystemClock.uptimeMillis()
                val pixels = service.capture(display).screenshot
                val ms = SystemClock.uptimeMillis() - start
                if (pixels == null) {
                    Log.i(TAG, "shot display $display: failed after $ms ms")
                } else {
                    val luma = pixels.argb.filterIndexed { i, _ -> i % 997 == 0 }
                        .map { (299 * (it shr 16 and 0xff) + 587 * (it shr 8 and 0xff) + 114 * (it and 0xff)) / 1000 }.average()
                    Log.i(TAG, "shot display $display: ${pixels.width}x${pixels.height} in $ms ms, luma ${luma.toInt()}")
                }
            }
            "tap" -> {
                val x = intent.getIntExtra("x", -1)
                val y = intent.getIntExtra("y", -1)
                service.capture(display) // the gesture goes to the display of the last capture
                val done = service.press(Target(Box(x, y, x + 1, y + 1)), long = false)
                Log.i(TAG, "tap display $display at $x,$y: ${if (done) "completed" else "not completed"}")
            }
            "ocr" -> {
                // For apps whose tree is empty (WeChat): proves the display shows the real UI.
                val label = intent.getStringExtra("label")
                service.useOcr(true)
                val hits = service.capture(display).ocr()
                service.useOcr(false)
                Log.i(TAG, "ocr display $display: ${hits.size} boxes, $label at ${hits.filter { label != null && label in it.text }.map { it.bounds }}")
            }
            "find" -> {
                val label = intent.getStringExtra("label")
                val node = service.capture(display).tree?.walk()?.firstOrNull { it.text == label || it.desc == label }
                Log.i(TAG, "find display $display: ${node?.bounds ?: "no such node"}")
            }
            "click" -> {
                val label = intent.getStringExtra("label")
                val node = service.capture(display).tree?.walk()?.firstOrNull { it.text == label || it.desc == label }
                val done = node != null && service.press(Target(node.bounds, node), long = false)
                Log.i(TAG, "click display $display: ${if (node == null) "no such node" else if (done) "done" else "not done"}")
            }
            // Can Phone Agent do without the shell server, given a role's permissions (ADD_TRUSTED_DISPLAY, ...)?
            "own" -> {
                val names = intent.getStringExtra("flags").orEmpty().split(",").filter { it.isNotBlank() }
                val flags = names.fold(0) { all, name -> all or FLAGS.getValue(name.trim()) }
                val reader = ImageReader.newInstance(1080, 2316, PixelFormat.RGBA_8888, 2)
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                own = service.getSystemService(DisplayManager::class.java)
                    .createVirtualDisplay("phone-agent-own", 1080, 2316, 480, reader.surface, flags)
                ownReader = reader
                Log.i(TAG, "own display ${own?.display?.displayId}, flags 0x${Integer.toHexString(flags)} ($names)")
            }
            "start" -> {
                val id = own?.display?.displayId
                val launch = service.packageManager.getLaunchIntentForPackage(intent.getStringExtra("pkg").orEmpty())
                if (id == null || launch == null) {
                    Log.i(TAG, "start: ${if (id == null) "no own display" else "no such app"}")
                } else {
                    service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle())
                    Log.i(TAG, "start on own display $id: sent")
                }
            }
            else -> Log.i(TAG, "unknown probe: $what")
        }
    }

    private fun count(node: AccessibilityNodeInfo): Int =
        1 + (0 until node.childCount).sumOf { i -> node.getChild(i)?.let(::count) ?: 0 }

    private companion object {
        const val TAG = "PhoneAgent"
        @Volatile var own: VirtualDisplay? = null
        @Volatile var ownReader: ImageReader? = null

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*: most are hidden, and an app may not read them by reflection.
        // Values as the shell tool printed them on Android 14 (docs/virtual-display-experiments.md).
        val FLAGS = mapOf(
            "PUBLIC" to 0x1, "OWN_CONTENT_ONLY" to 0x8, "SUPPORTS_TOUCH" to 0x40, "ROTATES_WITH_CONTENT" to 0x80,
            "DESTROY_CONTENT_ON_REMOVAL" to 0x100, "SHOULD_SHOW_SYSTEM_DECORATIONS" to 0x200, "TRUSTED" to 0x400,
            "OWN_DISPLAY_GROUP" to 0x800, "ALWAYS_UNLOCKED" to 0x1000, "TOUCH_FEEDBACK_DISABLED" to 0x2000,
            "OWN_FOCUS" to 0x4000, "DEVICE_DISPLAY_GROUP" to 0x8000, "STEAL_TOP_FOCUS_DISABLED" to 0x10000,
        )
    }
}
