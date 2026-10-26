package dev.phoneagent.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Pixels
import dev.phoneagent.core.run.TextHit
import dev.phoneagent.core.run.TextRecognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.stream.IntStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The knobs the accuracy and speed experiments sweep; the defaults are the current choice.
 * Accuracy ones are compared in every combination on the PC, speed ones on the phone.
 */
data class OcrConfig(
    /** Mean probability a detected region needs to be kept (gui-agent 0.6; OneDragon uses 0.45 for v6). */
    val boxThresh: Double = 0.6,
    /** How far a detected region is grown before cropping (DB unclip ratio). */
    val unclipRatio: Double = 1.5,
    /**
     * Longest side of the detection input; larger areas are scaled down. Null keeps full resolution.
     * 2240: as accurate as full size on the labelled screenshots (2026-10-26), where 2048 and below lose labels.
     */
    val detMaxSide: Int? = 2240,
    /** Lines per recognition run; lines in a run are padded to the widest. */
    val recBatch: Int = 6,
    val detThreads: Int = 4,
    val recThreads: Int = 4,
    /** Where each graph runs (the two phases' times add up, so each can take its fastest). XNNPACK and NNAPI exist only in the Android build of onnxruntime. */
    val detProvider: OcrProvider = OcrProvider.CPU,
    val recProvider: OcrProvider = OcrProvider.CPU,
    /**
     * ORT's `session.intra_op_thread_affinities` for both pools: one entry per pool thread
     * (threads - 1; the calling thread is not pinned), 1-based core ids, e.g. "5;6". Null lets
     * the OS place them. For the experiments on vivo's background cpuset (cores 0–5).
     */
    val affinity: String? = null,
)

enum class OcrProvider { CPU, XNNPACK, NNAPI }

/** Where the time of the last call went, in ms; for the speed experiments. */
class OcrTimings {
    var detPrep = 0L; var detRun = 0L; var boxes = 0L; var recPrep = 0L; var recRun = 0L; var decode = 0L
    override fun toString() = "检测预处理 $detPrep / 检测推理 $detRun / 找框 $boxes / 识别预处理 $recPrep / 识别推理 $recRun / 解码 $decode ms"
}

/**
 * PP-OCR detection + recognition on two ONNX Runtime sessions: a port of gui-agent's Python
 * engine (same preprocessing, DB post-processing and CTC greedy decoding), checked against
 * it on real screenshots. One instance per process; calls are serialized.
 */
class PpOcr(
    det: ByteArray,
    rec: ByteArray,
    dictionary: List<String>,
    private val config: OcrConfig = OcrConfig(),
) : TextRecognizer, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val detOptions = options(config.detThreads, config.detProvider, config.affinity)
    private val recOptions = options(config.recThreads, config.recProvider, config.affinity)
    private val det = env.createSession(det, detOptions)
    private val rec = env.createSession(rec, recOptions)
    val timings = OcrTimings()
    private val detInput = this.det.inputNames.first()
    private val recInput = this.rec.inputNames.first()
    private val alphabet = listOf("blank") + dictionary + " "

    // A full-screen detection input is ~30 MB; allocating it per call costs more than inference setup.
    private var detBuffer = floatBuffer(0)

    @Synchronized
    override fun recognize(pixels: Pixels, region: Box?): List<TextHit> {
        timings.apply { detPrep = 0; detRun = 0; boxes = 0; recPrep = 0; recRun = 0; decode = 0 }
        val area = clip(region ?: Box(0, 0, pixels.width, pixels.height), pixels) ?: return emptyList()
        val boxes = detect(pixels, area)
        return boxes.zip(read(pixels, boxes)).mapNotNull { (box, line) -> line?.let { TextHit(it.first, box, it.second) } }
    }

    @Synchronized
    override fun readLine(pixels: Pixels, region: Box): TextHit? {
        val area = clip(region, pixels) ?: return null
        return read(pixels, listOf(area)).single()?.let { TextHit(it.first, area, it.second) }
    }

    /** Runs both graphs once at the given screen size, so the first real call doesn't pay for setup. */
    @Synchronized
    fun warmUp(width: Int, height: Int) {
        val (w, h) = detSize(width, height, config.detMaxSide)
        run(det, detInput, floatBuffer(3 * w * h), longArrayOf(1, 3, h.toLong(), w.toLong())) {}
        run(rec, recInput, floatBuffer(config.recBatch * 3 * REC_HEIGHT * REC_BASE_WIDTH),
            longArrayOf(config.recBatch.toLong(), 3, REC_HEIGHT.toLong(), REC_BASE_WIDTH.toLong())) {}
    }

    override fun close() {
        det.close()
        rec.close()
        detOptions.close()
        recOptions.close()
    }

    private fun detect(src: Pixels, area: Box): List<Box> {
        val width = area.right - area.left
        val height = area.bottom - area.top
        val (w, h) = detSize(width, height, config.detMaxSide)
        val size = 3 * w * h
        if (detBuffer.capacity() < size) detBuffer = floatBuffer(size)
        val input = detBuffer.duplicate().apply { position(0); limit(size) }.slice()
        var t = System.nanoTime()
        resize(src, area, w, h, input, 0, w, w * h, DET_MEAN, DET_STD)
        timings.detPrep += lap(t).also { t = System.nanoTime() }
        val prob = run(det, detInput, input, longArrayOf(1, 3, h.toLong(), w.toLong())) { it }
        timings.detRun += lap(t).also { t = System.nanoTime() }
        return boxesFrom(prob, w, h, width, height, config.boxThresh, config.unclipRatio)
            .map { Box(it.left + area.left, it.top + area.top, it.right + area.left, it.bottom + area.top) }
            .also { timings.boxes += lap(t) }
    }

    /** Text and mean confidence per box (null when nothing was read), batched by aspect ratio. */
    private fun read(src: Pixels, boxes: List<Box>): List<Pair<String, Float>?> {
        var t = System.nanoTime()
        val crops = boxes.map { crop(src, it) }
        timings.recPrep += lap(t)
        val ratio = { i: Int -> crops[i].width.toDouble() / crops[i].height }
        val lines = arrayOfNulls<Pair<String, Float>>(crops.size)
        for (batch in crops.indices.sortedBy(ratio).chunked(config.recBatch)) {
            t = System.nanoTime()
            val batchW = (REC_HEIGHT * max(REC_BASE_WIDTH.toDouble() / REC_HEIGHT, batch.maxOf(ratio))).toInt()
            val plane = REC_HEIGHT * batchW
            // Zero padding to the right of narrower crops, as in PaddleOCR.
            val input = floatBuffer(batch.size * 3 * plane)
            batch.forEachIndexed { slot, i ->
                val c = crops[i]
                val w = min(ceil(REC_HEIGHT * ratio(i)).toInt(), batchW)
                resize(c, Box(0, 0, c.width, c.height), w, REC_HEIGHT, input, slot * 3 * plane, batchW, plane, REC_MEAN, REC_STD)
            }
            val shape = longArrayOf(batch.size.toLong(), 3, REC_HEIGHT.toLong(), batchW.toLong())
            timings.recPrep += lap(t).also { t = System.nanoTime() }
            run(rec, recInput, input, shape) { scores ->
                timings.recRun += lap(t).also { t = System.nanoTime() }
                val perSlot = scores.size / batch.size
                check(perSlot % alphabet.size == 0) { "dictionary does not match the recognition model" }
                // Each step is an argmax over the whole dictionary; lines are independent.
                IntStream.range(0, batch.size).parallel().forEach { slot ->
                    lines[batch[slot]] = ctcDecode(scores, slot * perSlot, perSlot / alphabet.size, alphabet.size, alphabet)
                }
                timings.decode += lap(t)
            }
        }
        return lines.toList()
    }

    private inline fun <T> run(session: OrtSession, name: String, input: FloatBuffer, shape: LongArray, use: (FloatArray) -> T): T =
        OnnxTensor.createTensor(env, input, shape).use { tensor ->
            session.run(mapOf(name to tensor)).use { result ->
                // floatBuffer is already a copy into a heap array (a recognition batch can be tens of MB); use it as is.
                val out = (result[0] as OnnxTensor).floatBuffer
                use(if (out.hasArray() && out.arrayOffset() == 0) out.array() else FloatArray(out.remaining()).also { out.get(it) })
            }
        }

    private companion object {
        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
        const val REC_HEIGHT = 48
        const val REC_BASE_WIDTH = 320

        fun options(threads: Int, provider: OcrProvider, affinity: String?) = OrtSession.SessionOptions().apply {
            // XNNPACK brings its own pool; ORT's then only runs the ops XNNPACK lacks (ORT docs: keep it at 1).
            setIntraOpNumThreads(if (provider == OcrProvider.XNNPACK) 1 else threads)
            // An idle pool that spins steals cores from the app being driven; sleeping measured faster.
            addConfigEntry("session.intra_op.allow_spinning", "0")
            affinity?.let { addConfigEntry("session.intra_op_thread_affinities", it) }
            when (provider) {
                OcrProvider.CPU -> {}
                OcrProvider.XNNPACK -> addXnnpack(mapOf("intra_op_num_threads" to "$threads"))
                OcrProvider.NNAPI -> addNnapi()
            }
        }

        fun lap(start: Long) = (System.nanoTime() - start) / 1_000_000
    }
}

private const val DET_MIN_SIDE = 64
private const val DET_MAX_SIDE = 4000
private const val DB_THRESH = 0.3f
private const val DB_MAX_CANDIDATES = 1000
private const val DB_MIN_SIZE = 3.0
// The Neo5's four fast cores.
private const val PREP_PARTS = 4

/**
 * Detection input size: the area itself, scaled down to [maxSide] when longer, else small sides
 * raised to 64; rounded to multiples of 32 (half to even), as gui-agent's engine does.
 */
internal fun detSize(width: Int, height: Int, maxSide: Int? = null): Pair<Int, Int> {
    var ratio = 1.0
    if (maxSide != null && max(width, height) > maxSide) ratio = maxSide.toDouble() / max(width, height)
    else if (min(width, height) < DET_MIN_SIDE) ratio = DET_MIN_SIDE.toDouble() / min(width, height)
    var w = (width * ratio).toInt()
    var h = (height * ratio).toInt()
    if (max(w, h) > DET_MAX_SIDE) {
        val down = DET_MAX_SIDE.toDouble() / max(w, h)
        w = (w * down).toInt()
        h = (h * down).toInt()
    }
    return max(Math.rint(w / 32.0).toInt() * 32, 32) to max(Math.rint(h / 32.0).toInt() * 32, 32)
}

/**
 * DB post-processing: 8-connected regions above the threshold, kept when their mean
 * probability is high enough, grown by the DB unclip distance, mapped to [outW] x [outH].
 * shortcut: axis-aligned boxes (PaddleOCR fits rotated ones); fine for horizontal UI text,
 * upgrade if slanted text ever matters.
 */
internal fun boxesFrom(
    prob: FloatArray, mapW: Int, mapH: Int, outW: Int, outH: Int,
    boxThresh: Double = OcrConfig().boxThresh, unclipRatio: Double = OcrConfig().unclipRatio,
): List<Box> {
    val seen = BooleanArray(prob.size)
    val stack = IntArray(prob.size)
    val boxes = mutableListOf<Box>()
    var candidates = 0
    for (start in prob.indices) {
        if (seen[start] || prob[start] <= DB_THRESH) continue
        if (++candidates > DB_MAX_CANDIDATES) break
        var minX = mapW; var maxX = -1; var minY = mapH; var maxY = -1
        var top = 0
        stack[top++] = start
        seen[start] = true
        while (top > 0) {
            val p = stack[--top]
            val x = p % mapW
            val y = p / mapW
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            for (ny in max(y - 1, 0)..min(y + 1, mapH - 1)) for (nx in max(x - 1, 0)..min(x + 1, mapW - 1)) {
                val q = ny * mapW + nx
                if (!seen[q] && prob[q] > DB_THRESH) {
                    seen[q] = true
                    stack[top++] = q
                }
            }
        }
        val w = (maxX - minX).toDouble()
        val h = (maxY - minY).toDouble()
        if (min(w, h) < DB_MIN_SIZE) continue
        var sum = 0.0
        for (y in minY..maxY) for (x in minX..maxX) sum += prob[y * mapW + x]
        if (sum / ((maxX - minX + 1) * (maxY - minY + 1)) < boxThresh) continue
        val grow = w * h * unclipRatio / (2 * (w + h))
        val gw = w + 2 * grow
        val gh = h + 2 * grow
        if (min(gw, gh) < DB_MIN_SIZE + 2) continue
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        fun sx(x: Double) = Math.rint(x / mapW * outW).toInt().coerceIn(0, outW)
        fun sy(y: Double) = Math.rint(y / mapH * outH).toInt().coerceIn(0, outH)
        boxes += Box(sx(cx - gw / 2), sy(cy - gh / 2), sx(cx + gw / 2), sy(cy + gh / 2))
    }
    return boxes
}

/** CTC greedy decoding: best class per step, repeats collapsed, blank (0) dropped; null when empty. */
internal fun ctcDecode(scores: FloatArray, offset: Int, steps: Int, classes: Int, alphabet: List<String>): Pair<String, Float>? {
    val text = StringBuilder()
    var sum = 0.0
    var kept = 0
    var previous = -1
    for (t in 0 until steps) {
        val row = offset + t * classes
        var best = 0
        for (c in 1 until classes) if (scores[row + c] > scores[row + best]) best = c
        if (best != 0 && best != previous) {
            text.append(alphabet[best])
            sum += scores[row + best]
            kept++
        }
        previous = best
    }
    return if (kept == 0) null else text.toString() to (sum / kept).toFloat()
}

private fun clip(box: Box, pixels: Pixels): Box? {
    val clipped = Box(max(box.left, 0), max(box.top, 0), min(box.right, pixels.width), min(box.bottom, pixels.height))
    return clipped.takeIf { it.right > it.left && it.bottom > it.top }
}

/** The box's pixels; a tall crop (height >= 1.5 x width) is turned a quarter counter-clockwise, as PaddleOCR does. */
private fun crop(src: Pixels, box: Box): Pixels {
    val w = box.right - box.left
    val h = box.bottom - box.top
    if (h < 1.5 * w) return Pixels(w, h, IntArray(w * h) { src[box.left + it % w, box.top + it / w] })
    return Pixels(h, w, IntArray(w * h) { val x = it % h; val y = it / h; src[box.left + w - 1 - y, box.top + x] })
}

/**
 * Bilinear resize of [rect] of [src] to [w] x [h] (OpenCV INTER_LINEAR sampling, edges
 * clamped to the rect) into three BGR planes of [out], each `(v / 255 - mean) / std`.
 * Plane c starts at `offset + c * plane`; rows are [stride] apart.
 */
private fun resize(
    src: Pixels, rect: Box, w: Int, h: Int, out: FloatBuffer,
    offset: Int, stride: Int, plane: Int, mean: FloatArray, std: FloatArray,
) {
    val rw = rect.right - rect.left
    val rh = rect.bottom - rect.top
    val x0 = IntArray(w); val x1 = IntArray(w); val ax = FloatArray(w)
    for (x in 0 until w) sample(x, rw, w).let { (a, b, f) -> x0[x] = rect.left + a; x1[x] = rect.left + b; ax[x] = f }
    val scale = FloatArray(3) { 1f / (255f * std[it]) }
    val shift = FloatArray(3) { mean[it] / std[it] }
    // Rows split into parts done in parallel, each row written with bulk puts: single puts into
    // a direct buffer cost ~100 ns each on the phone (a full screen took ~800 ms).
    val parts = min(h, PREP_PARTS)
    IntStream.range(0, parts).parallel().forEach { part ->
        val dst = out.duplicate()
        val row = FloatArray(3 * w)
        for (y in part * h / parts until (part + 1) * h / parts) {
            val (ya, yb, ay) = sample(y, rh, h)
            val rowA = (rect.top + ya) * src.width
            val rowB = (rect.top + yb) * src.width
            for (x in 0 until w) {
                val p00 = src.argb[rowA + x0[x]]; val p01 = src.argb[rowA + x1[x]]
                val p10 = src.argb[rowB + x0[x]]; val p11 = src.argb[rowB + x1[x]]
                val fx = ax[x]
                for (c in 0 until 3) {
                    val s = c * 8 // B, G, R: the channel order the models were trained on (OpenCV)
                    val top = (p00 shr s and 0xFF) + ((p01 shr s and 0xFF) - (p00 shr s and 0xFF)) * fx
                    val bottom = (p10 shr s and 0xFF) + ((p11 shr s and 0xFF) - (p10 shr s and 0xFF)) * fx
                    row[c * w + x] = Math.round(top + (bottom - top) * ay) * scale[c] - shift[c]
                }
            }
            for (c in 0 until 3) {
                dst.position(offset + y * stride + c * plane)
                dst.put(row, c * w, w)
            }
        }
    }
}

/** Source pixels and weight for output index [i] when scaling [srcSize] to [dstSize], OpenCV-style half-pixel centres. */
private fun sample(i: Int, srcSize: Int, dstSize: Int): Triple<Int, Int, Float> {
    val f = (i + 0.5) * srcSize / dstSize - 0.5
    var a = floor(f).toInt()
    var weight = (f - a).toFloat()
    if (a < 0) { a = 0; weight = 0f }
    if (a >= srcSize - 1) { a = srcSize - 1; weight = 0f }
    return Triple(a, min(a + 1, srcSize - 1), weight)
}

private fun floatBuffer(size: Int): FloatBuffer =
    ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
