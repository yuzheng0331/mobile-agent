package dev.phoneagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 *   tap --ei x <px> --ei y <px>   a gesture at that point of the display
 *   click --es label <label>      a node action on the node whose text or description is exactly <label>
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
            "click" -> {
                val label = intent.getStringExtra("label")
                val node = service.capture(display).tree?.walk()?.firstOrNull { it.text == label || it.desc == label }
                val done = node != null && service.press(Target(node.bounds, node), long = false)
                Log.i(TAG, "click display $display: ${if (node == null) "no such node" else if (done) "done" else "not done"}")
            }
            else -> Log.i(TAG, "unknown probe: $what")
        }
    }

    private fun count(node: AccessibilityNodeInfo): Int =
        1 + (0 until node.childCount).sumOf { i -> node.getChild(i)?.let(::count) ?: 0 }

    private companion object {
        const val TAG = "PhoneAgent"
    }
}
