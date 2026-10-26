package dev.phoneagent.core.run

import java.util.concurrent.atomic.AtomicLong

/** Screen rectangle in pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Long get() = (right - left).toLong() * (bottom - top)
}

/** One accessibility node, independent of Android. */
data class UiNode(
    val text: String? = null,
    val desc: String? = null,
    val id: String? = null,
    val className: String? = null,
    val bounds: Box = Box(0, 0, 0, 0),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val children: List<UiNode> = emptyList(),
) {
    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.walk()) }
    }

    /** Own text or description, else the first one found below this node. */
    val label: String?
        get() = walk().firstNotNullOfOrNull { node ->
            node.text?.takeIf { it.isNotBlank() } ?: node.desc?.takeIf { it.isNotBlank() }
        }
}

/** A screenshot as ARGB pixels, row by row. Kept in memory for one round only, never written out. */
class Pixels(val width: Int, val height: Int, val argb: IntArray) {
    operator fun get(x: Int, y: Int): Int = argb[y * width + x]
}

data class TextHit(val text: String, val bounds: Box, val confidence: Float)

/** OCR sits behind this so the model or backend can change. Boxes are in screen pixels. */
interface TextRecognizer {
    /** Detects and reads every line in [region] (the whole screenshot when null). */
    fun recognize(pixels: Pixels, region: Box?): List<TextHit>

    /** Reads one line already located at [region]: recognition only, no detection. */
    fun readLine(pixels: Pixels, region: Box): TextHit?
}

/**
 * Everything perceived in one round. Perception layers add to the same frame so a
 * later layer reuses an earlier one: the tree is read first, the screenshot is taken
 * and OCR runs only when a finder asks for them, OCR cached per region. A frame lives
 * for one round only.
 */
class Frame(
    val packageName: String?,
    val tree: UiNode?,
    val toasts: List<String> = emptyList(),
    val displayId: Int = 0,
    private val recognizer: TextRecognizer? = null,
    shooter: (() -> Pixels?)? = null,
) {
    /** Tells rounds apart (traces, caches); grows with every capture. */
    val id: Long = ids.incrementAndGet()

    /** Taken on first use: packs that only read the tree never pay for a screenshot. */
    val screenshot: Pixels? by lazy { shooter?.invoke() }

    private val ocrCache = HashMap<Box?, List<TextHit>>()
    private val lineCache = HashMap<Box, TextHit?>()

    fun nodes(): Sequence<UiNode> = tree?.walk() ?: emptySequence()

    /** Takes the screenshot now in runs that compare screens by it ([sameScreen]); it is otherwise lazy. */
    fun freeze(): Frame = apply { if (recognizer != null) screenshot }

    /**
     * Whether [other] shows the same screen. The tree decides, except in runs with OCR: there
     * the tree may be empty (WeChat), so equal trees are also compared by their screenshots.
     * Without both screenshots, equal trees count as the same.
     */
    fun sameScreen(other: Frame): Boolean {
        if (tree != other.tree) return false
        if (recognizer == null) return true
        val a = screenshot ?: return true
        val b = other.screenshot ?: return true
        return looksAlike(a, b)
    }

    /** OCR of [region], or nothing when this run has no OCR (packs that don't need it). */
    fun ocr(region: Box? = null): List<TextHit> {
        val reader = recognizer ?: return emptyList()
        val pixels = screenshot ?: return emptyList()
        return ocrCache.getOrPut(region) { reader.recognize(pixels, region) }
    }

    /** The one line of text at [region], recognition only. */
    fun ocrLine(region: Box): TextHit? {
        val reader = recognizer ?: return null
        val pixels = screenshot ?: return null
        if (region !in lineCache) lineCache[region] = reader.readLine(pixels, region)
        return lineCache[region]
    }
}

private val ids = AtomicLong()

/**
 * Same size and at most 1% of a coarse grid of points changed noticeably: the clock and a
 * blinking cursor stay under that, a page change or an opened menu does not.
 */
private fun looksAlike(a: Pixels, b: Pixels): Boolean {
    if (a.width != b.width || a.height != b.height) return false
    var points = 0
    var changed = 0
    for (y in 0 until a.height step 24) for (x in 0 until a.width step 24) {
        points++
        val p = a[x, y]
        val q = b[x, y]
        if ((0..16 step 8).sumOf { s -> Math.abs((p shr s and 0xFF) - (q shr s and 0xFF)) } > 48) changed++
    }
    return changed * 100 <= points
}

/** What a finder resolved to: the node to act on, or only a point for OCR hits. */
data class Target(val bounds: Box, val node: UiNode? = null)
