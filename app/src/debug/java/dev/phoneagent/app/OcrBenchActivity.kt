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
import dev.phoneagent.core.ocr.fingerprint
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Pixels
import java.io.File

/**
 * Debug only: the phone side of the OCR experiments. Measures the screenshot cost and minimum
 * interval, then runs the shipped OCR on `files/ocr/screen.png` (a real screenshot pushed with
 * adb) for every combination of the speed knobs, splitting each run into phases. Logs box
 * counts and times only, never the text. Start it with
 * `adb shell am start -n dev.phoneagent.app/.OcrBenchActivity`; keep the screen on.
 *
 * Extras narrow the grid, e.g. `--es providers NNAPI+NNAPI,XNNPACK+CPU --es sides 0,1280
 * --es batches 6` (detection+recognition provider; side 0 = full size). A provider that
 * crashes takes the process with it, so run those alone.
 * `--es agree 0,1920` instead compares with the PC: see [agreement].
 */
class OcrBenchActivity : Activity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(48, 96, 48, 48); text = "测速中…" }
        setContentView(out)
        fun list(name: String) = intent.getStringExtra(name)?.split(",")?.map { it.trim() }
        val providers = list("providers")?.map { pair -> pair.split("+").map(OcrProvider::valueOf).let { it[0] to it[1] } }
            ?: OcrProvider.entries.map { it to it }
        val sides = list("sides")?.map { it.toInt().takeIf { side -> side > 0 } } ?: DET_SIDES
        val batches = list("batches")?.map { it.toInt() } ?: REC_BATCHES
        Thread {
            val lines = mutableListOf<String>()
            fun log(line: String) {
                lines += line
                Log.i(TAG, line)
                runOnUiThread { out.text = lines.joinToString("\n\n") }
            }
            val agree = list("agree")?.map { it.toInt() }
            if (agree == null) log(screenshots())
            runCatching { if (agree != null) agreement(agree, ::log) else ocrGrid(providers, sides, batches, ::log) }
                .onFailure { log("OCR 出错：$it") }
            log("测速结束")
        }.start()
    }

    private fun ocrGrid(providers: List<Pair<OcrProvider, OcrProvider>>, sides: List<Int?>, batches: List<Int>, log: (String) -> Unit) {
        val file = File(getExternalFilesDir(null), "ocr/screen.png")
        if (!file.isFile) return log("OCR：没有 ${file.path}")
        val bitmap = BitmapFactory.decodeFile(file.path)
        val screen = Pixels(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        })
        fun bytes(name: String) = assets.open("ocr/$name").use { it.readBytes() }
        val det = bytes("det.onnx")
        val rec = bytes("rec.onnx")
        val dict = assets.open("ocr/dict.txt").bufferedReader().readLines()
        // What the CPU read at the same side and batch: other providers must read the same.
        // Only meaningful when a CPU+CPU run of that side and batch came first in this run.
        val cpu = mutableMapOf<Pair<Int?, Int>, Set<Pair<String, Box>>>()
        for ((detProvider, recProvider) in providers) for (side in sides) for (batch in batches) {
            val config = OcrConfig(detMaxSide = side, recBatch = batch, detProvider = detProvider, recProvider = recProvider)
            val name = "检测 $detProvider + 识别 $recProvider / 检测边长 ${side ?: "原图"} / 每批 $batch"
            // Logged first so a native crash still shows which combination it was.
            Log.i(TAG, "开始 $name")
            log("$name：" + runCatching {
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
                    val base = if (detProvider == OcrProvider.CPU && recProvider == OcrProvider.CPU) cpu.getOrPut(side to batch) { hits } else cpu[side to batch]
                    val same = base?.let { b -> "与 CPU 相同 ${hits.count { it in b }}/${b.size}" } ?: "没有 CPU 基准"
                    var text = "整屏 ${hits.size} 框 $ms ms（$split）；$same；加载 $loadMs，预热 $warmMs ms"
                    if (config == OcrConfig()) {
                        val band = Box(0, screen.height * 3 / 4, screen.width, screen.height)
                        start = SystemClock.uptimeMillis()
                        val part = ocr.recognize(screen, band).size
                        text += "；下 1/4 屏 $part 框 ${SystemClock.uptimeMillis() - start} ms"
                    }
                    text
                }
            }.getOrElse { "出错 ${it.javaClass.simpleName}: ${it.message?.take(160)}" })
        }
    }

    /**
     * Reads every screenshot in `files/ocr/agree/` (pushed in label order, names sorting as
     * that order) at each detection side (0 = full size) and logs one fingerprint per hit,
     * for OcrTest's `phone output matches the PC`. No text is logged.
     */
    private fun agreement(sides: List<Int>, log: (String) -> Unit) {
        val shots = File(getExternalFilesDir(null), "ocr/agree").listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.name }
        if (shots.isEmpty()) return log("对照：没有截图")
        fun bytes(name: String) = assets.open("ocr/$name").use { it.readBytes() }
        val dict = assets.open("ocr/dict.txt").bufferedReader().readLines()
        for (side in sides) PpOcr(bytes("det.onnx"), bytes("rec.onnx"), dict, OcrConfig(detMaxSide = side.takeIf { it > 0 })).use { ocr ->
            shots.forEachIndexed { index, file ->
                val bitmap = BitmapFactory.decodeFile(file.path)
                val screen = Pixels(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                })
                log("对照 $side $index " + ocr.recognize(screen, null).joinToString(" ") { it.fingerprint() })
            }
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
