package dev.phoneagent.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
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
            val lines = listOf(shots) + if (dirs.isEmpty()) listOf("没有模型：把 det.onnx / rec.onnx 推到 ${getExternalFilesDir(null)}/ocr/<名字>/")
            else dirs.flatMap { dir -> THREADS.map { t -> runCatching { bench(dir, t) }.getOrElse { "${dir.name} t=$t 出错：$it" } } }
            lines.forEach { Log.i(TAG, it) }
            runOnUiThread { out.text = lines.joinToString("\n\n") }
        }.start()
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
     * Screenshot cost and the system's minimum interval: [SHOTS] back-to-back requests (a refused
     * one returns at once), then the gap between successful ones.
     */
    private fun screenshots(): String {
        val service = AgentService.instance ?: return "截图：无障碍服务未开启"
        val results = List(SHOTS) {
            val start = SystemClock.uptimeMillis()
            val ok = service.capture().screenshot != null
            Triple(start, SystemClock.uptimeMillis() - start, ok)
        }
        val okStarts = results.filter { it.third }.map { it.first }
        val gaps = okStarts.zipWithNext { a, b -> b - a }
        return "截图：连续请求 $SHOTS 次，成功 ${okStarts.size} 次；成功的单次耗时 " +
            "${results.filter { it.third }.map { it.second }}" + " ms；成功之间的间隔 $gaps ms"
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
        const val SHOTS = 8
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
