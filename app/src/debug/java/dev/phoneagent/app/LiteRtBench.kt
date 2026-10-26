package dev.phoneagent.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import dev.phoneagent.core.run.Pixels
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug only: the GPU experiment. PP-OCR's fixed-shape `.tflite` conversions (det 1×2240×1024×3,
 * rec 6×48×320×3, NHWC; see docs/ocr-experiments.md for how they are made), pushed to
 * `files/ocr/litert/`, run on LiteRT with each [variants] entry (CPU, GPU, GPU_FP16, GPU_FP32),
 * timed (run + readback, median of [RUNS]) and compared with onnxruntime on the CPU on the same
 * input: detection pixels on the other side of the 0.3 threshold, recognition steps whose best
 * class differs. The input is the screenshot sampled nearest-neighbour, not the shipped
 * preprocessing: only the two runtimes are compared. [graphs] are `file:batch` entries, e.g.
 * `det:1,rec:6,rec1:1`: `<file>.tflite`, compared with `det.onnx` or `rec.onnx` by its first letters.
 */
internal class LiteRtBench(private val dir: File, private val onnx: (String) -> ByteArray, private val log: (String) -> Unit) {

    fun run(screen: Pixels, variants: List<String>, graphs: List<String>) {
        // Up to six strips of the screen, as the PC check does.
        val strips = (300 until 1500 step 200).map { 40 to it }
        val env = Environment.create()
        for (entry in graphs) {
            val (file, n) = entry.split(":").let { it[0] to it[1].toInt() }
            val graph = if (file.startsWith("det")) Graph(file, "det", 1, DET_H, DET_W, sample(screen, listOf(0 to 0), screen.width, screen.height, DET_W, DET_H, DET_MEAN, DET_STD))
            else Graph(file, "rec", n, REC_H, REC_W, sample(screen, strips.take(n), 760, 60, REC_W, REC_H, REC_MEAN, REC_STD))
            val (refMs, ref) = graph.onnx()
            log("${graph.name} onnxruntime CPU 4 线程：$refMs ms")
            for (variant in variants) log("${graph.name} LiteRT $variant：" + runCatching { graph.liteRt(env, variant, ref) }.getOrElse { "出错 ${it.javaClass.simpleName}: ${it.message?.take(200)}" })
        }
        env.close()
    }

    private inner class Graph(val name: String, val kind: String, val n: Int, val h: Int, val w: Int, val nhwc: FloatArray) {
        fun onnx(): Pair<Long, FloatArray> {
            val ort = OrtEnvironment.getEnvironment()
            val nchw = FloatArray(nhwc.size)
            for (b in 0 until n) for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3)
                nchw[((b * 3 + c) * h + y) * w + x] = nhwc[((b * h + y) * w + x) * 3 + c]
            // As PpOcr runs it: 4 threads, no spinning, a direct input buffer, warmed up first.
            val input = ByteBuffer.allocateDirect(nchw.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(nchw).also { it.rewind() }
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                ort.createSession(onnx("$kind.onnx"), options).use { session ->
                    val once = {
                        OnnxTensor.createTensor(ort, input, longArrayOf(n.toLong(), 3, h.toLong(), w.toLong())).use { t ->
                            session.run(mapOf(session.inputNames.first() to t)).use { (it[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also(b::get) } }
                        }
                    }
                    once()
                    return timed(once)
                }
            }
        }

        fun liteRt(env: Environment, variant: String, ref: FloatArray): String {
            val options = if (variant == "CPU") CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(numThreads = 4) }
            else CompiledModel.Options(Accelerator.GPU).apply {
                variant.substringAfter("GPU_", "").takeIf { it.isNotEmpty() }?.let {
                    gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.valueOf(it))
                }
            }
            var start = SystemClock.uptimeMillis()
            val model = CompiledModel.create(File(dir, "$name.tflite").path, options, env)
            val createMs = SystemClock.uptimeMillis() - start
            val inputs = model.createInputBuffers()
            val outputs = model.createOutputBuffers()
            try {
                start = SystemClock.uptimeMillis()
                inputs[0].writeFloat(nhwc)
                model.run(inputs, outputs)
                outputs[0].readFloat()
                val firstMs = SystemClock.uptimeMillis() - start
                val (ms, out) = timed { inputs[0].writeFloat(nhwc); model.run(inputs, outputs); outputs[0].readFloat() }
                return "$ms ms（建模 $createMs，首次 $firstMs ms）；${compare(out, ref)}"
            } finally {
                (inputs + outputs).forEach { it.close() }
                model.close()
            }
        }

        fun compare(out: FloatArray, ref: FloatArray): String {
            if (out.size != ref.size) return "输出大小不同 ${out.size} / ${ref.size}"
            val maxDiff = out.indices.maxOf { kotlin.math.abs(out[it] - ref[it]) }
            if (kind == "det") return "最大差 %.4f，阈值两边不同 %d / %d 像素".format(maxDiff, out.indices.count { (out[it] > .3f) != (ref[it] > .3f) }, ref.count { it > .3f })
            val steps = ref.size / CLASSES
            fun best(a: FloatArray, s: Int) = (0 until CLASSES).maxBy { a[s * CLASSES + it] }
            return "最大差 %.4f，最可能的字不同 %d / %d 步".format(maxDiff, (0 until steps).count { best(out, it) != best(ref, it) }, steps)
        }
    }

    private fun timed(block: () -> FloatArray): Pair<Long, FloatArray> {
        var out = FloatArray(0)
        val times = List(RUNS) { val t = SystemClock.uptimeMillis(); out = block(); SystemClock.uptimeMillis() - t }.sorted()
        return times[RUNS / 2] to out
    }

    private companion object {
        const val RUNS = 3
        const val DET_W = 1024
        const val DET_H = 2240
        const val REC_W = 320
        const val REC_H = 48
        const val CLASSES = 18710
        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

        /** Each (left, top) [srcW]×[srcH] patch scaled nearest-neighbour to [w]×[h], NHWC, BGR, `(v / 255 - mean) / std`. */
        fun sample(p: Pixels, at: List<Pair<Int, Int>>, srcW: Int, srcH: Int, w: Int, h: Int, mean: FloatArray, std: FloatArray): FloatArray {
            val out = FloatArray(at.size * h * w * 3)
            at.forEachIndexed { b, (left, top) ->
                for (y in 0 until h) for (x in 0 until w) {
                    val argb = p[left + x * srcW / w, top + y * srcH / h]
                    for (c in 0 until 3) out[((b * h + y) * w + x) * 3 + c] = ((argb shr (c * 8) and 0xFF) / 255f - mean[c]) / std[c]
                }
            }
            return out
        }
    }
}
