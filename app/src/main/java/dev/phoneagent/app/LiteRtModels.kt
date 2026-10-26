package dev.phoneagent.app

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import dev.phoneagent.core.ocr.OcrConfig
import dev.phoneagent.core.ocr.OcrModels
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * PpOcr's models on LiteRT's GPU at full precision, which reads the same as onnxruntime on the
 * CPU (docs/ocr-experiments.md 4.6): the fixed-shape conversions in `assets/ocr/litert/` that
 * tools/probe/ocr_litert_assets.sh makes, one recognition model per width in [CONFIG]. Throws
 * when they are missing or the GPU refuses them. LiteRT wants a model created, run and closed
 * on one thread, so every call goes through [thread].
 */
class LiteRtModels(context: Context) : OcrModels {
    override val channelsLast = true
    private val thread = Executors.newSingleThreadExecutor()
    private val models = mutableMapOf<String, Model>()
    private lateinit var env: Environment

    init {
        // Compiled GPU programs are cached here, so only the first start compiles (seconds per model).
        val cache = File(context.cacheDir, "litert").apply { mkdirs() }.path
        try {
            on {
                env = Environment.create()
                for (name in listOf("det") + CONFIG.recWidths!!.map { "rec$it" }) models[name] = Model(context, name, cache)
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override fun detect(input: FloatBuffer, shape: LongArray) = on { models.getValue("det").run(input) }
    override fun read(input: FloatBuffer, shape: LongArray) = on { models.getValue("rec${shape[3]}").run(input) }

    override fun close() {
        runCatching { on { models.values.forEach { it.close() }; if (::env.isInitialized) env.close() } }
        thread.shutdown()
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
        /** The shapes the bundled conversions were made for. */
        val CONFIG = OcrConfig(detCanvas = 1024 to 2240, recWidths = listOf(320, 640, 960, 1280))
    }
}
