package dev.phoneagent.app

import android.app.Activity
import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Bundle
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import dev.phoneagent.core.ocr.OcrConfig
import dev.phoneagent.core.ocr.OcrProvider
import dev.phoneagent.core.ocr.PpOcr
import dev.phoneagent.core.ocr.OcrScore
import dev.phoneagent.core.ocr.parseLabels
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
 * `--es score 0,2240` instead scores against the labels at those detection sides: see [score].
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
        // "4", "2@6", "3@5;6": threads for both graphs, then ORT affinities (1-based cores).
        val threads = list("threads")?.map { it.substringBefore("@").toInt() to it.substringAfter("@", "").ifEmpty { null } } ?: listOf(4 to null)
        // Seconds to wait before measuring, to leave time to put another app in front (vivo's background cpuset).
        val delay = intent.getStringExtra("delay")?.toLong() ?: 0
        // The cores experiment: hold a foreground service while measuring; report to a performance hint session.
        val fgs = intent.getStringExtra("fgs") != null
        hint = intent.getStringExtra("hint") != null
        if (fgs) startForegroundService(Intent(this, BenchService::class.java))
        // One measurement per process: the activity may be created again (e.g. reopened from recents)
        // while the first one still runs, and two at once compete for cores and run out of memory.
        if (running) return run { out.text = "已经在测速，结果看日志" }
        running = true
        Thread {
            val lines = mutableListOf<String>()
            fun log(line: String) {
                lines += line
                Log.i(TAG, line)
                runOnUiThread { out.text = lines.joinToString("\n\n") }
            }
            val score = list("score")?.map { it.toInt() }
            if (score == null && delay == 0L) log(screenshots())
            Thread.sleep(delay * 1000)
            runCatching { if (score != null) score(score, ::log) else ocrGrid(providers, sides, batches, threads, ::log) }
                .onFailure { log("OCR 出错：$it") }
            if (fgs) stopService(Intent(this, BenchService::class.java))
            log("测速结束")
            running = false
        }.start()
    }

    private fun ocrGrid(providers: List<Pair<OcrProvider, OcrProvider>>, sides: List<Int?>, batches: List<Int>, threads: List<Pair<Int, String?>>, log: (String) -> Unit) {
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
        for ((detProvider, recProvider) in providers) for (side in sides) for (batch in batches) for ((n, affinity) in threads) {
            val config = OcrConfig(
                detMaxSide = side, recBatch = batch, detProvider = detProvider, recProvider = recProvider,
                detThreads = n, recThreads = n, affinity = affinity,
            )
            val name = "检测 $detProvider + 识别 $recProvider / 检测边长 ${side ?: "原图"} / 每批 $batch / $n 线程${affinity?.let { "绑 $it" } ?: ""} / 开始时 ${cpuset()}"
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
                    val session = if (hint) getSystemService(PerformanceHintManager::class.java)?.createHintSession(intArrayOf(Process.myTid()), HINT_TARGET_NS) else null
                    val runs = List(RUNS) {
                        val t = SystemClock.uptimeMillis()
                        val hits = ocr.recognize(screen, null).map { it.text to it.bounds }.toSet()
                        session?.reportActualWorkDuration((SystemClock.uptimeMillis() - t) * 1_000_000)
                        Triple(SystemClock.uptimeMillis() - t, hits, ocr.timings.toString())
                    }.sortedBy { it.first }
                    session?.close()
                    val placed = "核：${cores()}；独占核 ${Process.getExclusiveCores().toList()}" +
                        (if (hint) "；性能提示 ${if (session == null) "不支持" else "已开"}" else "")
                    val (ms, hits, split) = runs[RUNS / 2]
                    val base = if (detProvider == OcrProvider.CPU && recProvider == OcrProvider.CPU) cpu.getOrPut(side to batch) { hits } else cpu[side to batch]
                    val same = base?.let { b -> "与 CPU 相同 ${hits.count { it in b }}/${b.size}" } ?: "没有 CPU 基准"
                    var text = "整屏 ${hits.size} 框 $ms ms（$split）；$same；加载 $loadMs，预热 $warmMs ms；$placed；结束时 ${cpuset()}"
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
     * Accuracy on the phone itself: `files/ocr/labelled/labels.json` (the label files merged, in
     * order) and the screenshots as `00.png`, `01.png`, … in that order, both pushed with adb and
     * deleted after. Scored as on the PC (OcrScore); only counts are logged, never the text.
     */
    private fun score(sides: List<Int>, log: (String) -> Unit) {
        val dir = File(getExternalFilesDir(null), "ocr/labelled")
        val labels = File(dir, "labels.json").takeIf { it.isFile }?.let { parseLabels(it.readText()) } ?: return log("判分：没有标注")
        fun bytes(name: String) = assets.open("ocr/$name").use { it.readBytes() }
        val dict = assets.open("ocr/dict.txt").bufferedReader().readLines()
        for (side in sides) PpOcr(bytes("det.onnx"), bytes("rec.onnx"), dict, OcrConfig(detMaxSide = side.takeIf { it > 0 })).use { ocr ->
            val score = OcrScore()
            labels.forEachIndexed { index, shot ->
                val bitmap = BitmapFactory.decodeFile(File(dir, "%02d.png".format(index)).path)
                val screen = Pixels(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                })
                score.add(index, shot, ocr.recognize(screen, null))
            }
            log("判分（手机）检测边长 ${if (side == 0) "原图" else side}：${labels.size} 张，${score.summary()}")
            score.byLength().forEach { log("  $it") }
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

    private fun cpuset() = File("/proc/self/cgroup").readLines().firstOrNull { "cpuset" in it }?.substringAfterLast('/')

    /** Which cores this process's threads may run on, as "cores × threads", e.g. "0-5×31". */
    private fun cores() = File("/proc/self/task").listFiles().orEmpty().mapNotNull { t ->
        runCatching { File(t, "status").readLines().first { it.startsWith("Cpus_allowed_list") }.substringAfter(":").trim() }.getOrNull()
    }.groupingBy { it }.eachCount().entries.joinToString(" ") { "${it.key}×${it.value}" }

    private var hint = false

    private companion object {
        const val HINT_TARGET_NS = 1_000_000_000L
        @Volatile var running = false
        const val TAG = "PhoneAgent"
        const val SHOTS = 6
        const val RETRY_MS = 50L
        const val RUNS = 3
        // Every combination, not one knob at a time. Threads stay at 4 + 4: on 2026-10-26 det and
        // rec times each depended only on their own thread count, and 4 beat 2, 6 and 8 for both.
        val DET_SIDES = listOf<Int?>(2240, null, 1280, 960)
        val REC_BATCHES = listOf(6, 1, 12)
    }
}
