package dev.phoneagent.core.run

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

data class TextHit(val text: String, val bounds: Box, val confidence: Float)

/** OCR sits behind this so the model or backend can change (M3). */
fun interface TextRecognizer {
    fun recognize(region: Box?): List<TextHit>
}

/**
 * Everything perceived in one round. Perception layers add to the same frame so a
 * later layer reuses an earlier one: the tree is read first, OCR runs only when a
 * finder asks for it and is cached per region. A frame lives for one round only.
 */
class Frame(
    val packageName: String?,
    val tree: UiNode?,
    val toasts: List<String> = emptyList(),
    val displayId: Int = 0,
    private val recognizer: TextRecognizer? = null,
) {
    private val ocrCache = HashMap<Box?, List<TextHit>>()

    fun nodes(): Sequence<UiNode> = tree?.walk() ?: emptySequence()

    fun ocr(region: Box? = null): List<TextHit> =
        recognizer?.let { ocrCache.getOrPut(region) { it.recognize(region) } } ?: emptyList()
}

/** What a finder resolved to: the node to act on, or only a point for OCR hits. */
data class Target(val bounds: Box, val node: UiNode? = null)
