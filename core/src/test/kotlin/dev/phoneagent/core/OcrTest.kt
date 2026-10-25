package dev.phoneagent.core

import dev.phoneagent.core.ocr.PpOcr
import dev.phoneagent.core.ocr.boxesFrom
import dev.phoneagent.core.ocr.ctcDecode
import dev.phoneagent.core.ocr.detSize
import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Pixels
import org.junit.jupiter.api.Assumptions.assumeTrue
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

    private fun iou(a: Box, b: Box): Double {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0 || h <= 0) return 0.0
        val inter = w.toDouble() * h
        return inter / (a.area + b.area - inter)
    }
}
