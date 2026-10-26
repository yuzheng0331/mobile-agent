package dev.phoneagent.core.ext

import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Pixels
import kotlin.math.abs

/**
 * The bottom-most message in a chat screenshot, for apps whose tree is empty (WeChat).
 * Every message has an avatar in a fixed column at the screen edge (left: received, right:
 * sent) and its bubble right beside it; system notices and times are centred, with no avatar,
 * so they are skipped. Returns the bubble, as tall as the avatar (enough to press it).
 * Sizes are fractions of the screen width, so any resolution works. Based on WeChat 2026-10
 * dark-mode chats: text, image, voice, sticker, system notice, both sides.
 * shortcut: an avatar much the colour of the background is not seen; upgrade if one shows up.
 */
internal fun lastBubble(p: Pixels): Box? {
    val w = p.width
    // Avatar columns: WeChat's avatars sit at 3–13% and 87–97% of the width, bubbles start
    // 1–2% further in, so these bands hold avatars only.
    val left = (0.025 * w).toInt() until (0.135 * w).toInt()
    val right = (0.865 * w).toInt() until (0.975 * w).toInt()
    val bg = background(p, left)
    fun differs(x: Int, y: Int) = p[x, y].let { c -> (0..16 step 8).sumOf { s -> abs((c shr s and 0xFF) - (bg shr s and 0xFF)) } } > 24
    val avatars = listOf(true, false).flatMap { onLeft ->
        avatarRuns(p, if (onLeft) left else right, ::differs).map { it to onLeft }
    }
    val (rows, onLeft) = avatars.maxByOrNull { it.first.last } ?: return null
    // The bubble starts within a small gap of the avatar and ends at the first wider gap, which
    // separates it from what follows (a voice message's unread dot and its 转文字 button).
    val reach = (0.06 * w).toInt()
    val gap = (0.012 * w).toInt()
    val edge = if (onLeft) left.last else right.first
    val step = if (onLeft) 1 else -1
    var near = Int.MAX_VALUE
    var far = Int.MIN_VALUE
    for (y in rows step 2) {
        var x = edge + step * (0.01 * w).toInt()
        val start = generateSequence(x) { it + step }.take(reach).firstOrNull { differs(it, y) } ?: continue
        x = start
        var blank = 0
        var end = start
        while (x in 0 until w && blank <= gap) {
            if (differs(x, y)) { end = x; blank = 0 } else blank++
            x += step
        }
        near = minOf(near, minOf(start, end)); far = maxOf(far, maxOf(start, end))
    }
    if (near > far) return null
    return Box(near, rows.first, far + 1, rows.last + 1)
}

/** The most common colour in the avatar column: the chat background. */
private fun background(p: Pixels, column: IntRange): Int {
    val counts = HashMap<Int, Int>()
    for (y in 0 until p.height step 7) for (x in column step 5) counts.merge(p[x, y] and 0xFFFFFF, 1, Int::plus)
    return counts.maxBy { it.value }.key
}

/** Row ranges in [column] covered by a square block about one avatar tall (8–12% of the width). */
private fun avatarRuns(p: Pixels, column: IntRange, differs: (Int, Int) -> Boolean): List<IntRange> {
    val minSide = (0.08 * p.width).toInt()
    val maxSide = (0.12 * p.width).toInt()
    val runs = mutableListOf<IntRange>()
    var start = -1
    for (y in 0..p.height) {
        val filled = y < p.height && column.count { differs(it, y) } >= minSide / 2
        if (filled && start < 0) start = y
        if (!filled && start >= 0) {
            val xs = column.filter { x -> differs(x, (start + y) / 2) }
            val square = xs.isNotEmpty() && xs.last() - xs.first() in minSide..maxSide
            if (y - start in minSide..maxSide && square) runs += start until y
            start = -1
        }
    }
    return runs
}
