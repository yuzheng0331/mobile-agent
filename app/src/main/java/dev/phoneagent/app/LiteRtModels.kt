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
 * PpOcr's recognition on LiteRT: on the GPU at full precision, or on LiteRT's CPU when the GPU
 * refuses the models (or [gpu] is false); both read the same as onnxruntime on the CPU
 * (docs/ocr-experiments.md 4.6). The models are the fixed-shape conversions in
 * `assets/ocr/litert/` that tools/probe/ocr_litert_assets.sh makes, one per shape in [CONFIG].
 * LiteRT wants a model created, run and closed on one thread, so every call goes through [thread].
 *
 * Detection stays on onnxruntime on the CPU. On the GPU it only takes the whole-screen size
 * (padding a region into it reads differently: the model pools over its whole input) and holds
 * 292 MB of GPU memory, while packs so far detect in regions (2026-10-26).
 */
class LiteRtModels(context: Context, gpu: Boolean = true) : OcrModels {
    /** Where recognition runs, GPU or CPU. */
    lateinit var accelerator: Accelerator
        private set
    private val cpu = OrtModels(context.assets.open("ocr/det.onnx").use { it.readBytes() }, null)
    private val thread = Executors.newSingleThreadExecutor()
    private val models = mutableMapOf<String, Model>()
    private lateinit var env: Environment

    init {
        // Compiled GPU programs are cached, so only the first start after an install compiles (seconds
        // per model). One folder per install: a new conversion can keep the name and size (all four
        // widths have the same size), so older programs must not be picked up; they are deleted.
        val install = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val caches = File(context.cacheDir, "litert")
        caches.listFiles()?.filter { it.name != install }?.forEach { it.deleteRecursively() }
        val cache = File(caches, install).apply { mkdirs() }.path
        try {
            on {
                env = Environment.create()
                for (candidate in listOfNotNull(Accelerator.GPU.takeIf { gpu }, Accelerator.CPU)) {
                    accelerator = candidate
                    try {
                        for (width in CONFIG.recRuns!!.keys) models["rec$width"] = Model(context, "rec$width", cache, candidate)
                        break
                    } catch (e: Exception) {
                        models.values.forEach { it.close() }
                        models.clear()
                        if (candidate == Accelerator.CPU) throw e
                    }
                }
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

    private inner class Model(context: Context, name: String, cache: String, accelerator: Accelerator) {
        private val compiled = CompiledModel.create(context.assets, "ocr/litert/$name.tflite", CompiledModel.Options(accelerator).apply {
            if (accelerator == Accelerator.CPU) cpuOptions = CompiledModel.CpuOptions(numThreads = 4)
            else gpuOptions = CompiledModel.GpuOptions(
                precision = CompiledModel.GpuOptions.Precision.FP32,
                serializationDir = cache,
                modelCacheKey = name,
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
