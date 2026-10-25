package dev.phoneagent.app

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import dev.phoneagent.core.ocr.OcrConfig
import dev.phoneagent.core.ocr.OcrProvider
import dev.phoneagent.core.ocr.PpOcr
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Pixels
import java.io.File

/**
 * Debug only: the phone side of the OCR experiments. Measures the screenshot cost and minimum
 * interval, then runs the shipped OCR on `files/ocr/screen.png` (a real screenshot pushed with
 * adb) for every combination of the speed knobs, splitting each run into phases. Logs box
 * counts and times only, never the text. Start it with
 * `adb shell am start -n dev.phoneagent.app/.OcrBenchActivity`; keep the screen on.
 */
class OcrBenchActivity : Activity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(48, 96, 48, 48); text = "测速中…" }
        setContentView(out)
        Thread {
            val lines = mutableListOf(screenshots())
            runCatching { ocrGrid(lines) }.onFailure { lines += "OCR 出错：$it" }
            lines.forEach { Log.i(TAG, it) }
            runOnUiThread { out.text = lines.joinToString("\n\n") }
        }.start()
    }

    private fun ocrGrid(lines: MutableList<String>) {
        val file = File(getExternalFilesDir(null), "ocr/screen.png")
        if (!file.isFile) return run { lines += "OCR：没有 ${file.path}" }
        val bitmap = BitmapFactory.decodeFile(file.path)
        val screen = Pixels(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        })
        fun bytes(name: String) = assets.open("ocr/$name").use { it.readBytes() }
        val det = bytes("det.onnx")
        val rec = bytes("rec.onnx")
        val dict = assets.open("ocr/dict.txt").bufferedReader().readLines()
        // What the CPU read at the same side and batch: other providers must read the same.
        val cpu = mutableMapOf<Pair<Int?, Int>, Set<Pair<String, Box>>>()
        for (provider in OcrProvider.entries) for (side in DET_SIDES) for (batch in REC_BATCHES) {
            val config = OcrConfig(detMaxSide = side, recBatch = batch, provider = provider)
            var line = "$provider / 检测边长 ${side ?: "原图"} / 每批 $batch："
            line += runCatching {
                var start = SystemClock.uptimeMillis()
                PpOcr(det, rec, dict, config).use { ocr ->
                    val loadMs = SystemClock.uptimeMillis() - start
                    start = SystemClock.uptimeMillis()
                    ocr.warmUp(screen.width, screen.height)
                    val warmMs = SystemClock.uptimeMillis() - start
                    // Median of RUNS whole-screen runs, with that run's phase split.
                    val runs = List(RUNS) {
                        val t = SystemClock.uptimeMillis()
                        val hits = ocr.recognize(screen, null).map { it.text to it.bounds }.toSet()
                        Triple(SystemClock.uptimeMillis() - t, hits, ocr.timings.toString())
                    }.sortedBy { it.first }
                    val (ms, hits, split) = runs[RUNS / 2]
                    val same = cpu.getOrPut(side to batch) { hits }.let { base -> "与 CPU 相同 ${hits.count { it in base }}/${base.size}" }
                    var text = "整屏 ${hits.size} 框 $ms ms（$split）；$same；加载 $loadMs，预热 $warmMs ms"
                    if (config == OcrConfig()) {
                        val band = Box(0, screen.height * 3 / 4, screen.width, screen.height)
                        start = SystemClock.uptimeMillis()
                        val part = ocr.recognize(screen, band).size
                        text += "；下 1/4 屏 $part 框 ${SystemClock.uptimeMillis() - start} ms"
                    }
                    text
                }
            }.getOrElse { "出错 ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
            lines += line
            Log.i(TAG, line)
        }
    }

    /**
     * Screenshot cost and the system's minimum interval: keeps asking, retrying a refused
     * request every [RETRY_MS], until [SHOTS] succeed; reports each success's cost and the gaps
     * between successes. Sleeping is fine here, this is a debug measurement, not the runner.
     */
    private fun screenshots(): String {
        val service = AgentService.instance ?: return "截图：无障碍服务未开启"
        val okAt = mutableListOf<Long>()
        val costs = mutableListOf<Long>()
        var refused = 0
        while (okAt.size < SHOTS) {
            val start = SystemClock.uptimeMillis()
            if (service.capture().screenshot != null) {
                okAt += start
                costs += SystemClock.uptimeMillis() - start
            } else {
                refused++
                Thread.sleep(RETRY_MS)
            }
        }
        return "截图：成功 $SHOTS 次，期间被拒 $refused 次；单次耗时 $costs ms；成功之间的间隔 ${okAt.zipWithNext { a, b -> b - a }} ms"
    }

    private companion object {
        const val TAG = "PhoneAgent"
        const val SHOTS = 6
        const val RETRY_MS = 50L
        const val RUNS = 3
        // Every combination, not one knob at a time. Threads stay at 4 + 4: on 2026-10-26 det and
        // rec times each depended only on their own thread count, and 4 beat 2, 6 and 8 for both.
        val DET_SIDES = listOf<Int?>(null, 1280, 960)
        val REC_BATCHES = listOf(6, 1, 12)
    }
}
