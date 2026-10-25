package dev.phoneagent.core

import dev.phoneagent.core.ocr.OcrConfig
import dev.phoneagent.core.ocr.PpOcr
import dev.phoneagent.core.ocr.boxesFrom
import dev.phoneagent.core.ocr.ctcDecode
import dev.phoneagent.core.ocr.detSize
import dev.phoneagent.core.ocr.Label
import dev.phoneagent.core.ocr.LabelledShot
import dev.phoneagent.core.ocr.OcrScore
import dev.phoneagent.core.ocr.Reading
import dev.phoneagent.core.ocr.parseLabels
import dev.phoneagent.core.run.TextHit
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Pixels
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fetched by :app:fetchOcrModels; the model tests are skipped until then. */
private val MODELS = File("../app/src/main/assets/ocr")

private fun loadOcr(): PpOcr {
    assumeTrue(File(MODELS, "rec.onnx").isFile, "OCR models not fetched; run ./gradlew :app:fetchOcrModels")
    return PpOcr(File(MODELS, "det.onnx").readBytes(), File(MODELS, "rec.onnx").readBytes(), File(MODELS, "dict.txt").readLines())
}

private fun pixels(image: BufferedImage) =
    Pixels(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))

class OcrTest {
    @Test
    fun `ctc decoding collapses repeats, drops blanks and averages the kept scores`() {
        val alphabet = listOf("blank", "复", "制", " ")
        // steps: 复 复 blank 制 制 → "复制" from steps 0 and 3
        val scores = floatArrayOf(
            0.1f, 0.8f, 0.1f, 0f,
            0.1f, 0.7f, 0.2f, 0f,
            0.9f, 0.05f, 0.05f, 0f,
            0.2f, 0.1f, 0.6f, 0f,
            0.1f, 0.1f, 0.8f, 0f,
        )
        val (text, confidence) = ctcDecode(scores, 0, 5, 4, alphabet)!!
        assertEquals("复制", text)
        assertEquals(0.7f, confidence, 1e-6f)
        assertNull(ctcDecode(floatArrayOf(0.9f, 0.1f, 0f, 0f), 0, 1, 4, alphabet))
    }

    @Test
    fun `detection boxes keep confident regions, grow them and map them to the image`() {
        val w = 100
        val h = 50
        val prob = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, p: Float) {
            for (y in y0..y1) for (x in x0..x1) prob[y * w + x] = p
        }
        fill(10, 10, 39, 19, 0.9f) // text
        fill(60, 30, 61, 31, 0.9f) // too small
        fill(60, 40, 90, 48, 0.5f) // above the pixel threshold, below the box threshold
        // 29 x 9 grows by 29*9*1.5/76 on each side, then doubles to the 200 x 100 image.
        assertEquals(listOf(Box(10, 10, 88, 48)), boxesFrom(prob, w, h, 200, 100))
    }

    @Test
    fun `detection input is rounded to multiples of 32, halves to even`() {
        assertEquals(1088 to 2400, detSize(1080, 2400))
        assertEquals(1024 to 96, detSize(1040, 100)) // 32.5 → 32
        assertEquals(64 to 640, detSize(30, 300)) // short side raised to 64 first
    }

    @Test
    fun `reads rendered UI words with the real model`() {
        val ocr = loadOcr()
        val image = BufferedImage(600, 240, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color.WHITE
            fillRect(0, 0, 600, 240)
            color = Color.DARK_GRAY
            font = Font(Font.SANS_SERIF, Font.PLAIN, 40)
            drawString("复制", 60, 80)
            drawString("通讯录", 320, 80)
            drawString("转发", 60, 190)
            dispose()
        }
        val screen = pixels(image)
        val words = ocr.recognize(screen, null).map { it.text.trim() }
        assertTrue(words.containsAll(listOf("复制", "通讯录", "转发")), "read $words")
        // Only the lower half: the top words are out of the region.
        assertEquals(listOf("转发"), ocr.recognize(screen, Box(0, 120, 600, 240)).map { it.text.trim() })
        assertEquals("复制", ocr.readLine(screen, Box(50, 30, 160, 100))?.text?.trim())
        ocr.close()
    }

    /**
     * Agreement with gui-agent's Python engine on real screenshots, aggregates only; a guard for
     * OCR speed work. Off unless OCR_REFERENCE names the JSON written by tools/probe/ocr_reference.py
     * (screenshots stay local). Full agreement isn't reachable: Python batches lines in OpenCV contour
     * order, fits rotated boxes and resizes in fixed point, and borderline lines flip on such
     * differences. Scored against v6 medium both ports read equally well (2026-10-26: 98% boxes, 89% same text).
     */
    @Test
    fun `matches the Python engine on real screenshots`() {
        val reference = System.getenv("OCR_REFERENCE")?.let(::File)
        assumeTrue(reference?.isFile == true, "set OCR_REFERENCE to compare with the Python engine")
        val ocr = loadOcr()
        val shots = kotlinx.serialization.json.Json.parseToJsonElement(reference!!.readText()).let { it as kotlinx.serialization.json.JsonArray }
        var expected = 0
        var found = 0
        var same = 0
        var extra = 0
        var millis = 0L
        for (shot in shots) {
            val o = shot as kotlinx.serialization.json.JsonObject
            val image = ImageIO.read(File(o["file"].toString().trim('"')))
            val start = System.nanoTime()
            val got = ocr.recognize(pixels(image), null)
            millis += (System.nanoTime() - start) / 1_000_000
            val want = (o["hits"] as kotlinx.serialization.json.JsonArray).map { hit ->
                val h = hit as kotlinx.serialization.json.JsonObject
                val b = (h["box"] as kotlinx.serialization.json.JsonArray).map { it.toString().toInt() }
                Box(b[0], b[1], b[2], b[3]) to h["text"].toString().let { kotlinx.serialization.json.Json.decodeFromString<String>(it) }
            }
            val used = mutableSetOf<Int>()
            for ((box, text) in want) {
                expected++
                val match = got.indices.filter { it !in used }.maxByOrNull { iou(box, got[it].bounds) } ?: continue
                if (iou(box, got[match].bounds) < 0.5) continue
                used += match
                found++
                if (got[match].text == text) same++
            }
            extra += got.size - used.size
        }
        println("OCR vs Python: ${shots.size} 张，参考框 $expected，找到 $found（${100 * found / expected}%），文字相同 $same（${100 * same / expected}%），多出 $extra，平均 ${millis / shots.size} ms/张（PC）")
        assertTrue(found >= expected * 0.95 && same >= expected * 0.85, "OCR drifted from the Python engine")
        ocr.close()
    }

    /**
     * Accuracy of every combination of the accuracy knobs against ground truth (tree text, hand
     * labels; see tools/probe/ocr_labels.py), so they are chosen together rather than one at a
     * time. Off unless OCR_LABELS lists the label files. A label counts as read when a box over
     * it reads exactly its text; an absent word counts against a setting wherever it is read.
     */
    @Test
    fun `accuracy grid on labelled screenshots`() {
        val files = System.getenv("OCR_LABELS")?.split(File.pathSeparator)?.map(::File)?.filter { it.isFile }.orEmpty()
        assumeTrue(files.isNotEmpty(), "set OCR_LABELS to the label files")
        assumeTrue(File(MODELS, "rec.onnx").isFile, "OCR models not fetched")
        val shots = files.flatMap { parseLabels(it.readText()) }
        val images = shots.map { pixels(ImageIO.read(File(it.file))) }
        val grid = System.getenv("OCR_GRID")?.let { spec -> spec.split(";").map { configOf(it) } } ?: listOf(0.45, 0.6).flatMap { thresh ->
            listOf(1.5, 2.0).flatMap { unclip ->
                listOf<Int?>(null, 1600, 1280, 960).flatMap { side ->
                    listOf(1, 6).map { batch -> OcrConfig(boxThresh = thresh, unclipRatio = unclip, detMaxSide = side, recBatch = batch) }
                }
            }
        }
        val det = File(MODELS, "det.onnx").readBytes()
        val rec = File(MODELS, "rec.onnx").readBytes()
        val dict = File(MODELS, "dict.txt").readLines()
        println("OCR 网格：${shots.size} 张，标注 ${shots.sumOf { it.labels.size }}，其中 ≤${OcrScore.SHORT} 字 ${shots.sumOf { s -> s.labels.count { it.text.length <= OcrScore.SHORT } }}，不该出现 ${shots.sumOf { it.absent.size }}")
        for (config in grid) {
            PpOcr(det, rec, dict, config).use { ocr ->
                val score = OcrScore()
                var millis = 0L
                shots.forEachIndexed { i, shot ->
                    val start = System.nanoTime()
                    val hits = ocr.recognize(images[i], null)
                    millis += (System.nanoTime() - start) / 1_000_000
                    score.add(i, shot, hits)
                }
                println("  $config：${score.summary()}，平均 ${millis / shots.size} ms/张（PC）")
                if (config == grid.first()) {
                    score.byLength().forEach { println("    $it") }
                    System.getenv("OCR_MISSES")?.let { writeMisses(File(it), shots, score) }
                }
            }
        }
    }

    /**
     * Every label that was not read, with what OCR read and the screenshot crop, as a local HTML
     * page (it holds real screen text: write it under tools/probe/out/, never into the repository).
     */
    private fun writeMisses(out: File, shots: List<LabelledShot>, score: OcrScore) {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val rows = score.results.filter { it.reading != Reading.READ }.sortedWith(compareBy({ it.reading }, { it.label.text.length })).joinToString("\n") { r ->
            val b = r.label.box
            val url = File(shots[r.shot].file).toURI()
            val crop = "width:${b.right - b.left}px;height:${b.bottom - b.top}px;background:url('$url') -${b.left}px -${b.top}px"
            "<tr><td>${r.reading.zh}</td><td>${r.shot} ${esc(File(shots[r.shot].file).parentFile.name)}</td><td>${esc(r.label.text)}</td><td>${esc(r.got)}</td><td>${r.distance}</td><td><div style=\"$crop\"></div></td></tr>"
        }
        out.writeText("""<!doctype html><meta charset="utf-8"><title>OCR 没读对的标注</title>
            <style>body{font:14px sans-serif;background:#fff;color:#000}td{border-bottom:1px solid #ccc;padding:4px;vertical-align:top;max-width:420px}div{max-width:1080px;overflow:hidden;border:1px solid red}</style>
            <p>${esc(score.summary())}</p><table><tr><th>原因</th><th>截图</th><th>标注</th><th>OCR 最接近的读法</th><th>差几个字</th><th>截图上的位置</th></tr>
            $rows</table>""".trimIndent())
        println("    没读对的标注写到了 ${out.absolutePath}")
    }

    @Test
    fun `scoring tells why a label was not read`() {
        val shot = LabelledShot("x/wechat/screen.png", listOf(
            Label("复制", Box(0, 0, 100, 40)), Label("转 发", Box(200, 0, 300, 40)), Label("收藏！", Box(400, 0, 500, 40)),
            Label("通讯录", Box(600, 0, 700, 40)), Label("我", Box(800, 0, 840, 40)), Label("第一行第二行", Box(0, 100, 300, 200)),
            Label("删除", Box(0, 300, 100, 340)),
        ), listOf("多选"))
        fun hit(text: String, l: Int, t: Int, r: Int, b: Int) = TextHit(text, Box(l, t, r, b), 0.9f)
        val score = OcrScore()
        score.add(0, shot, listOf(
            hit("复制", 5, 5, 95, 35), hit("转发", 205, 5, 295, 35), hit("收藏!", 405, 5, 495, 35), hit("通讯", 605, 5, 680, 35),
            hit("我的", 800, 5, 880, 35), hit("第二行", 0, 160, 300, 190), hit("第一行", 0, 110, 300, 140), hit("删险", 0, 300, 100, 340),
            hit("多选", 900, 900, 960, 940),
        ))
        assertEquals(listOf(Reading.READ, Reading.READ, Reading.FORMAT, Reading.FEWER, Reading.MORE, Reading.READ, Reading.WRONG), score.results.map { it.reading })
        assertEquals(1, score.misread)
        assertEquals(Reading.NO_BOX, OcrScore().apply { add(0, shot, emptyList()) }.results.first().reading)
    }

    private fun configOf(spec: String): OcrConfig {
        val v = spec.split(",").associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        return OcrConfig(
            boxThresh = v["boxThresh"]?.toDouble() ?: OcrConfig().boxThresh,
            unclipRatio = v["unclip"]?.toDouble() ?: OcrConfig().unclipRatio,
            // side=0 is full size; no side key keeps the default.
            detMaxSide = if ("side" in v) v.getValue("side").toInt().takeIf { it > 0 } else OcrConfig().detMaxSide,
            recBatch = v["batch"]?.toInt() ?: OcrConfig().recBatch,
        )
    }

    private fun iou(a: Box, b: Box): Double {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0.0
        val inter = w.toDouble() * h
        return inter / (a.area + b.area - inter)
    }
}
