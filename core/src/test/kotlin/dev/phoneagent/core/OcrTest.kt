package dev.phoneagent.core

import dev.phoneagent.core.ocr.OcrConfig
import dev.phoneagent.core.ocr.PpOcr
import dev.phoneagent.core.ocr.boxesFrom
import dev.phoneagent.core.ocr.ctcDecode
import dev.phoneagent.core.ocr.detSize
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
        class Shot(val pixels: Pixels, val labels: List<Pair<String, Box>>, val absent: List<String>, val wechat: Boolean)
        val shots = files.flatMap { file ->
            Json.parseToJsonElement(file.readText()).jsonArray.map { s ->
                val o = s.jsonObject
                val path = o.getValue("file").jsonPrimitive.content
                val labels = o.getValue("labels").jsonArray.map { l ->
                    val b = l.jsonObject.getValue("box").jsonArray.map { it.jsonPrimitive.int }
                    l.jsonObject.getValue("text").jsonPrimitive.content to Box(b[0] - 10, b[1] - 10, b[2] + 10, b[3] + 10)
                }
                Shot(pixels(ImageIO.read(File(path))), labels, o.getValue("absent").jsonArray.map { it.jsonPrimitive.content }, "wechat" in path)
            }
        }
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
        println("OCR 网格：${shots.size} 张，标注 ${shots.sumOf { it.labels.size }}，其中 ≤4 字 ${shots.sumOf { s -> s.labels.count { it.first.length <= 4 } }}，不该出现 ${shots.sumOf { it.absent.size }}")
        for (config in grid) {
            PpOcr(det, rec, dict, config).use { ocr ->
                var all = 0; var allRead = 0; var short = 0; var shortRead = 0; var wx = 0; var wxRead = 0; var wrong = 0; var missed = 0; var misread = 0
                var millis = 0L
                for (shot in shots) {
                    val start = System.nanoTime()
                    val hits = ocr.recognize(shot.pixels, null)
                    millis += (System.nanoTime() - start) / 1_000_000
                    for ((text, box) in shot.labels) {
                        val want = text.filterNot(Char::isWhitespace)
                        val over = hits.filter { overlaps(it.bounds, box) }
                        // One box, or a text OCR split into several boxes inside the label (lines, wide gaps).
                        val inside = over.filter { box.left <= it.bounds.centerX && it.bounds.centerX <= box.right && box.top <= it.bounds.centerY && it.bounds.centerY <= box.bottom }
                        val read = over.any { it.text.filterNot(Char::isWhitespace) == want } ||
                            inside.sortedWith(compareBy({ it.bounds.top / 20 }, { it.bounds.left })).joinToString("") { it.text }.filterNot(Char::isWhitespace) == want
                        all++; if (read) allRead++
                        if (text.length <= 4) { short++; if (read) shortRead++ else if (over.isEmpty()) missed++ else misread++ }
                        if (shot.wechat) { wx++; if (read) wxRead++ }
                    }
                    wrong += shot.absent.count { word -> hits.any { it.text.trim() == word } }
                }
                println("  $config：≤4 字 ${pct(shortRead, short)}（没框 $missed / 认错 $misread），全部 ${pct(allRead, all)}，微信 ${pct(wxRead, wx)}，误读 $wrong，平均 ${millis / shots.size} ms/张（PC）")
            }
        }
    }

    private fun configOf(spec: String): OcrConfig {
        val v = spec.split(",").associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        return OcrConfig(
            boxThresh = v["boxThresh"]?.toDouble() ?: OcrConfig().boxThresh,
            unclipRatio = v["unclip"]?.toDouble() ?: OcrConfig().unclipRatio,
            detMaxSide = v["side"]?.toIntOrNull(),
            recBatch = v["batch"]?.toInt() ?: OcrConfig().recBatch,
        )
    }

    private fun pct(a: Int, b: Int) = "%d/%d=%.1f%%".format(a, b, 100.0 * a / maxOf(b, 1))

    private fun overlaps(a: Box, b: Box) = minOf(a.right, b.right) > maxOf(a.left, b.left) && minOf(a.bottom, b.bottom) > maxOf(a.top, b.top)

    private fun iou(a: Box, b: Box): Double {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0.0
        val inter = w.toDouble() * h
        return inter / (a.area + b.area - inter)
    }
}
