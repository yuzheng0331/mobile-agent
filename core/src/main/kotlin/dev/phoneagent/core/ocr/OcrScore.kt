package dev.phoneagent.core.ocr

import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.TextHit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min

/**
 * Ground truth for one screenshot (see tools/probe/ocr_labels.py): what must be read where, and
 * words that must not be read anywhere. For the OCR experiments on the PC and the phone; the
 * labels hold real screen text, so they stay out of the repository and out of logs.
 */
class LabelledShot(val file: String, val labels: List<Label>, val absent: List<String>) {
    val wechat get() = "wechat" in file
}

class Label(val text: String, val box: Box)

fun parseLabels(json: String): List<LabelledShot> = Json.parseToJsonElement(json).jsonArray.map { s ->
    val o = s.jsonObject
    LabelledShot(
        o.getValue("file").jsonPrimitive.content,
        o.getValue("labels").jsonArray.map { l ->
            val b = l.jsonObject.getValue("box").jsonArray.map { it.jsonPrimitive.int }
            Label(l.jsonObject.getValue("text").jsonPrimitive.content, Box(b[0], b[1], b[2], b[3]))
        },
        o.getValue("absent").jsonArray.map { it.jsonPrimitive.content },
    )
}

/** Why a label was not read; [READ] when it was. */
enum class Reading(val zh: String) {
    READ("读对"),
    NO_BOX("没框"),
    FORMAT("只差标点、空格或全半角"),
    FEWER("少字"),
    MORE("多字"),
    WRONG("错字"),
}

/** One label's outcome: the closest reading OCR gave and how many characters it is off. */
class Scored(val shot: Int, val label: Label, val reading: Reading, val got: String, val distance: Int, val wechat: Boolean)

/**
 * Scores OCR against labels. A label is read when a box over it (label padded by [PAD] px)
 * reads exactly its text, whitespace ignored, or when the boxes centred inside it, joined in
 * reading order, do (OCR splits lines and wide gaps). Otherwise the closest of those readings
 * says why it failed. Labels of at most [SHORT] characters are what packs look for (buttons,
 * tabs); long ones are chat text and must match character for character.
 */
class OcrScore {
    val results = mutableListOf<Scored>()
    var misread = 0
        private set

    /**
     * Boxes over no label, and those of one character (icons and avatars read as a character
     * look like this). Labels miss some real text, so only compare these between settings.
     */
    var unlabelled = 0
        private set
    var unlabelledSingle = 0
        private set

    fun add(index: Int, shot: LabelledShot, hits: List<TextHit>) {
        for (label in shot.labels) results += score(index, label, hits, shot.wechat)
        misread += shot.absent.count { word -> hits.any { it.text.trim() == word } }
        val stray = hits.filter { hit -> shot.labels.none { overlaps(hit.bounds, padded(it.box)) } }
        unlabelled += stray.size
        unlabelledSingle += stray.count { squash(it.text).length == 1 }
    }

    private fun padded(b: Box) = Box(b.left - PAD, b.top - PAD, b.right + PAD, b.bottom + PAD)

    private fun score(index: Int, label: Label, hits: List<TextHit>, wechat: Boolean): Scored {
        val box = padded(label.box)
        val want = squash(label.text)
        val over = hits.filter { overlaps(it.bounds, box) }
        val inside = over.filter { box.left <= it.bounds.centerX && it.bounds.centerX <= box.right && box.top <= it.bounds.centerY && it.bounds.centerY <= box.bottom }
        val joined = inside.sortedWith(compareBy({ it.bounds.top / ROW }, { it.bounds.left })).joinToString("") { it.text }
        val got = (over.map { it.text } + joined).map(::squash).filter { it.isNotEmpty() }.minByOrNull { distance(it, want) }
            ?: return Scored(index, label, Reading.NO_BOX, "", want.length, wechat)
        val reading = when {
            got == want -> Reading.READ
            plain(got) == plain(want) -> Reading.FORMAT
            got in want -> Reading.FEWER
            want in got -> Reading.MORE
            else -> Reading.WRONG
        }
        return Scored(index, label, reading, got, distance(got, want), wechat)
    }

    /** One line: short labels (with why the rest failed), all labels, character accuracy, WeChat, misreads, unlabelled boxes. No label text. */
    fun summary(): String {
        val short = results.filter { it.label.text.length <= SHORT }
        val why = Reading.entries.drop(1).joinToString(" / ") { r -> "${r.zh} ${short.count { it.reading == r }}" }
        val chars = results.sumOf { squash(it.label.text).length }
        val charsRight = results.sumOf { max(0, squash(it.label.text).length - it.distance) }
        val wechat = results.filter { it.wechat }
        return "≤$SHORT 字 ${pct(short.count { it.reading == Reading.READ }, short.size)}（$why），" +
            "全部 ${pct(results.count { it.reading == Reading.READ }, results.size)}，字符 ${pct(charsRight, chars)}，" +
            "微信 ${pct(wechat.count { it.reading == Reading.READ }, wechat.size)}，误读 $misread，标注外的框 $unlabelled（单字 $unlabelledSingle）"
    }

    /** Read rate, character accuracy and failure reasons per label length; counts only. */
    fun byLength(): List<String> = listOf(1..SHORT, SHORT + 1..10, 11..Int.MAX_VALUE).map { range ->
        val group = results.filter { squash(it.label.text).length in range }
        val chars = group.sumOf { squash(it.label.text).length }
        val why = Reading.entries.drop(1).joinToString(" / ") { r -> "${r.zh} ${group.count { it.reading == r }}" }
        val name = if (range.last == Int.MAX_VALUE) "${range.first} 字以上" else "${range.first}–${range.last} 字"
        "$name：读对 ${pct(group.count { it.reading == Reading.READ }, group.size)}，字符 ${pct(group.sumOf { max(0, squash(it.label.text).length - it.distance) }, chars)}（$why）"
    }

    companion object {
        const val PAD = 10
        const val SHORT = 4
        private const val ROW = 20

        fun pct(a: Int, b: Int) = "%d/%d=%.1f%%".format(a, b, 100.0 * a / max(b, 1))

        private fun squash(s: String) = s.filterNot(Char::isWhitespace)

        /** Letters and digits only, full-width folded to half-width. */
        private fun plain(s: String) = s.map { if (it in '！'..'～') it - 0xFEE0 else it }.filter(Char::isLetterOrDigit).joinToString("").lowercase()

        private fun overlaps(a: Box, b: Box) = min(a.right, b.right) > max(a.left, b.left) && min(a.bottom, b.bottom) > max(a.top, b.top)

        /** Levenshtein distance in characters. */
        fun distance(a: String, b: String): Int {
            var row = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val next = IntArray(b.length + 1).also { it[0] = i }
                for (j in 1..b.length) next[j] = min(min(next[j - 1], row[j]) + 1, row[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                row = next
            }
            return row[b.length]
        }
    }
}
