package dev.phoneagent.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import dev.phoneagent.core.ocr.PpOcr
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Pixels
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Debug only: times PP-OCR inference for every model folder pushed to `files/ocr/<name>/`
 * (det.onnx + rec.onnx), at 2 and 4 threads, and the screenshot cost and minimum interval. Inference time does not depend on what the
 * image shows, so a blank full-screen input stands in for a screenshot. Pre/post-processing
 * is not ported. Start it with `adb shell am start -n dev.phoneagent.app/.OcrBenchActivity`.
 */
class OcrBenchActivity : Activity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(48, 96, 48, 48); text = "测速中…" }
        setContentView(out)
        Thread {
            val dirs = File(getExternalFilesDir(null), "ocr").listFiles { f -> File(f, "det.onnx").isFile }.orEmpty().sortedBy { it.name }
            val shots = screenshots()
            val e2e = runCatching { endToEnd() }.getOrElse { "端到端 OCR 出错：$it" }
            val lines = listOf(shots, e2e) + if (dirs.isEmpty()) listOf("没有模型：把 det.onnx / rec.onnx 推到 ${getExternalFilesDir(null)}/ocr/<名字>/")
            else dirs.flatMap { dir -> THREADS.map { t -> runCatching { bench(dir, t) }.getOrElse { "${dir.name} t=$t 出错：$it" } } }
            lines.forEach { Log.i(TAG, it) }
            runOnUiThread { out.text = lines.joinToString("\n\n") }
        }.start()
    }

    /**
     * The shipped OCR (assets, PpOcr, 4 threads) end to end on `files/ocr/screen.png`, a real
     * screenshot pushed with adb: whole screen, a quarter-height band, and one line. Logs box
     * counts and times only, never the text.
     */
    private fun endToEnd(): String {
        val file = File(getExternalFilesDir(null), "ocr/screen.png")
        if (!file.isFile) return "端到端 OCR：没有 ${file.path}"
        val bitmap = BitmapFactory.decodeFile(file.path)
        val screen = Pixels(bitmap.width, bitmap.height, IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        })
        fun bytes(name: String) = assets.open("ocr/$name").use { it.readBytes() }
        var start = SystemClock.uptimeMillis()
        val ocr = PpOcr(bytes("det.onnx"), bytes("rec.onnx"), assets.open("ocr/dict.txt").bufferedReader().readLines())
        val loadMs = SystemClock.uptimeMillis() - start
        start = SystemClock.uptimeMillis()
        ocr.warmUp(screen.width, screen.height)
        val warmMs = SystemClock.uptimeMillis() - start
        fun timed(block: () -> Int): Pair<Int, Long> {
            val runs = List(3) { val t = SystemClock.uptimeMillis(); block() to SystemClock.uptimeMillis() - t }
            return runs.first().first to runs.map { it.second }.sorted()[1]
        }
        val (all, allMs) = timed { ocr.recognize(screen, null).size }
        val band = Box(0, screen.height * 3 / 4, screen.width, screen.height)
        val (part, partMs) = timed { ocr.recognize(screen, band).size }
        val line = ocr.recognize(screen, null).first().bounds
        val (_, lineMs) = timed { if (ocr.readLine(screen, line) != null) 1 else 0 }
        ocr.close()
        return "端到端 OCR（small，4 线程）：加载 $loadMs ms，预热 $warmMs ms；整屏 $all 框 $allMs ms；" +
            "下 1/4 屏 $part 框 $partMs ms；单行只识别 $lineMs ms"
    }

    private fun bench(dir: File, threads: Int): String {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            addConfigEntry("session.intra_op.allow_spinning", "0")
        }
        val loadStart = SystemClock.uptimeMillis()
        env.createSession(File(dir, "det.onnx").path, options).use { det ->
            env.createSession(File(dir, "rec.onnx").path, options).use { rec ->
                val loadMs = SystemClock.uptimeMillis() - loadStart
                val detMs = time(env, det, longArrayOf(1, 3, DET_HEIGHT, DET_WIDTH))
                val batches = (REC_LINES + REC_BATCH - 1) / REC_BATCH
                val recMs = time(env, rec, longArrayOf(REC_BATCH.toLong(), 3, REC_HEIGHT, REC_WIDTH)) * batches
                return "${dir.name} t=$threads 加载 $loadMs ms，检测 ${DET_WIDTH}x$DET_HEIGHT $detMs ms，" +
                    "识别 $REC_LINES 行 $recMs ms，合计 ${detMs + recMs} ms"
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

    /** Median of [RUNS] runs after one warm-up. */
    private fun time(env: OrtEnvironment, session: OrtSession, shape: LongArray): Long {
        val input = floatBuffer(shape.fold(1L, Long::times).toInt())
        return OnnxTensor.createTensor(env, input, shape).use { tensor ->
            val feed = mapOf(session.inputNames.first() to tensor)
            session.run(feed).close()
            List(RUNS) {
                val start = SystemClock.uptimeMillis()
                session.run(feed).close()
                SystemClock.uptimeMillis() - start
            }.sorted()[RUNS / 2]
        }
    }

    private fun floatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private companion object {
        const val TAG = "PhoneAgent"
        val THREADS = listOf(2, 4)
        const val RUNS = 5
        const val SHOTS = 6
        const val RETRY_MS = 50L
        // Full 1080x2400 screen, rounded to multiples of 32 like the Python engine (no downscaling).
        const val DET_WIDTH = 1088L
        const val DET_HEIGHT = 2400L
        // About 40 text boxes on the WeChat list and menu screenshots.
        const val REC_LINES = 40
        const val REC_BATCH = 6
        const val REC_HEIGHT = 48L
        const val REC_WIDTH = 320L
    }
}
