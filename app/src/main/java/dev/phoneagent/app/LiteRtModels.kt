package dev.phoneagent.app

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import dev.phoneagent.core.ocr.OcrConfig
import dev.phoneagent.core.ocr.OcrModels
import dev.phoneagent.core.ocr.OrtModels
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * PpOcr's recognition on LiteRT's GPU at full precision, which reads the same as onnxruntime on
 * the CPU (docs/ocr-experiments.md 4.6): the fixed-shape conversions in `assets/ocr/litert/` that
 * tools/probe/ocr_litert_assets.sh makes, one per shape in [CONFIG]. Throws when they are missing
 * or the GPU refuses them. LiteRT wants a model created, run and closed on one thread, so every
 * call goes through [thread].
 *
 * Detection stays on onnxruntime on the CPU. On the GPU it only takes the whole-screen size
 * (padding a region into it reads differently: the model pools over its whole input) and holds
 * 292 MB of GPU memory, while packs so far detect in regions (2026-10-26).
 */
class LiteRtModels(context: Context) : OcrModels {
    private val cpu = OrtModels(context.assets.open("ocr/det.onnx").use { it.readBytes() }, null)
    private val thread = Executors.newSingleThreadExecutor()
    private val models = mutableMapOf<String, Model>()
    private lateinit var env: Environment

    init {
        // Compiled GPU programs are cached here, so only the first start compiles (seconds per model).
        val cache = File(context.cacheDir, "litert").apply { mkdirs() }.path
        try {
            on {
                env = Environment.create()
                for (width in CONFIG.recRuns!!.keys) models["rec$width"] = Model(context, "rec$width", cache)
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun channelsLast(shape: LongArray) = shape.contentEquals(rec(shape[3]))

    override fun detect(input: FloatBuffer, shape: LongArray) = cpu.detect(input, shape)
    override fun read(input: FloatBuffer, shape: LongArray) = on { models.getValue("rec${shape[3]}").run(input) }

    override fun close() {
        runCatching { on { models.values.forEach { it.close() }; if (::env.isInitialized) env.close() } }
        thread.shutdown()
        cpu.close()
    }

    private fun <T> on(block: () -> T): T = try {
        thread.submit(block).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    private inner class Model(context: Context, name: String, cache: String) {
        private val path = "ocr/litert/$name.tflite"
        private val compiled = CompiledModel.create(context.assets, path, CompiledModel.Options(Accelerator.GPU).apply {
            gpuOptions = CompiledModel.GpuOptions(
                precision = CompiledModel.GpuOptions.Precision.FP32,
                serializationDir = cache,
                // A new conversion has another size, so it doesn't pick up the old one's programs.
                modelCacheKey = "$name-${context.assets.openFd(path).use { it.length }}",
                serializeProgramCache = true,
            )
        }, env)
        private val inputs = compiled.createInputBuffers()
        private val outputs = compiled.createOutputBuffers()
        private var array = FloatArray(0)

        fun run(input: FloatBuffer): FloatArray {
            if (array.size != input.remaining()) array = FloatArray(input.remaining())
            input.duplicate().get(array)
            inputs[0].writeFloat(array)
            compiled.run(inputs, outputs)
            return outputs[0].readFloat()
        }

        fun close() {
            (inputs + outputs).forEach { it.close() }
            compiled.close()
        }
    }

    companion object {
        /**
         * The shapes the bundled conversions were made for, width to lines per run. GPU memory grows
         * with the run: six lines of 1280 held 234 MB, so the wide ones, a quarter of the runs, go alone.
         */
        val CONFIG = OcrConfig(recRuns = mapOf(320 to 6, 640 to 1, 960 to 1, 1280 to 1))
        private fun rec(width: Long) = longArrayOf((CONFIG.recRuns!![width.toInt()] ?: 0).toLong(), 3, 48, width)
    }
}
