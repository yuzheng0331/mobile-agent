package dev.phoneagent.core.ext

import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Pixels
import kotlin.math.abs

// Chat screens read from the screenshot's layout, for apps whose tree is empty (WeChat). Every
// message and every chat-list row has an avatar in a fixed column at the screen edge. Sizes are
// fractions of the screen width, so any resolution works. Based on WeChat 2026-10 dark mode.
// shortcut: an avatar much the colour of its row is not seen; upgrade if one shows up.

/**
 * The bottom-most message: the bubble beside the lowest avatar (left: received, right: sent),
 * as tall as the avatar (8–11% of the width), enough to press it. System notices and times are centred with no
 * avatar, so they are skipped. Checked on text, image, voice, sticker, system notice, both sides.
 */
internal fun lastBubble(p: Pixels): Box? {
    val w = p.width
    // Avatars sit at 3–13% and 87–97% of the width; bubbles start 1–2% further in.
    val left = (0.025 * w).toInt() until (0.135 * w).toInt()
    val right = (0.865 * w).toInt() until (0.975 * w).toInt()
    val avatars = listOf(true, false).flatMap { onLeft ->
        avatars(p, if (onLeft) left else right, 0.08, 0.11).map { it to onLeft }
    }
    val (avatar, onLeft) = avatars.maxByOrNull { it.first.bottom } ?: return null
    // The bubble starts within a small gap of the avatar and ends at the first wider gap, which
    // separates it from what follows (a voice message's unread dot and its 转文字 button).
    val reach = (0.06 * w).toInt()
    val gap = (0.012 * w).toInt()
    val edge = if (onLeft) left.last else right.first
    val step = if (onLeft) 1 else -1
    var near = Int.MAX_VALUE
    var far = Int.MIN_VALUE
    for (y in avatar.top until avatar.bottom step 2) {
        val bg = p.rowBackground(y)
        val start = generateSequence(edge + step * (0.01 * w).toInt()) { it + step }.take(reach).firstOrNull { p.differs(it, y, bg) } ?: continue
        var x = start
        var blank = 0
        var end = start
        while (x in 0 until w && blank <= gap) {
            if (p.differs(x, y, bg)) { end = x; blank = 0 } else blank++
            x += step
        }
        near = minOf(near, start, end); far = maxOf(far, start, end)
    }
    if (near > far) return null
    return Box(near, avatar.top, far + 1, avatar.bottom)
}

/**
 * Chat-list rows top to bottom: each row's avatar (at 3–17% of the width, 11–15% tall; chat
 * avatars are 10%, so a chat screen has no rows and a list has no bubble) and the
 * strip beside its upper half where the chat's name is. Pinned rows have a lighter background;
 * comparing with each row's own margin keeps them.
 */
internal fun chatRows(p: Pixels): List<Pair<Box, Box>> {
    val w = p.width
    return avatars(p, (0.03 * w).toInt() until (0.17 * w).toInt(), 0.11, 0.15).sortedBy { it.top }.map { a ->
        a to Box(a.right + (0.02 * w).toInt(), a.top, (0.8 * w).toInt(), a.top + (a.bottom - a.top) / 2)
    }
}

/** The colour at the row's left margin: the background behind whatever is in that row. */
private fun Pixels.rowBackground(y: Int) = this[(0.01 * width).toInt(), y]

private fun Pixels.differs(x: Int, y: Int, bg: Int) =
    this[x, y].let { c -> (0..16 step 8).sumOf { s -> abs((c shr s and 0xFF) - (bg shr s and 0xFF)) } } > 24

/** Square blocks in [column] whose side is [minFraction]–[maxFraction] of the width. */
private fun avatars(p: Pixels, column: IntRange, minFraction: Double, maxFraction: Double): List<Box> {
    val minSide = (minFraction * p.width).toInt()
    val maxSide = (maxFraction * p.width).toInt()
    val found = mutableListOf<Box>()
    var start = -1
    for (y in 0..p.height) {
        val filled = y < p.height && p.rowBackground(y).let { bg -> column.count { p.differs(it, y, bg) } } >= minSide / 2
        if (filled && start < 0) start = y
        if (!filled && start >= 0) {
            val mid = (start + y) / 2
            val bg = p.rowBackground(mid)
            val xs = column.filter { p.differs(it, mid, bg) }
            if (y - start in minSide..maxSide && xs.isNotEmpty() && xs.last() - xs.first() in minSide..maxSide) {
                found += Box(xs.first(), start, xs.last() + 1, y)
            }
            start = -1
        }
    }
    return found
}
