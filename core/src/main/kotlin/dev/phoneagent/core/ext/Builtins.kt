package dev.phoneagent.core.ext

import dev.phoneagent.core.run.Box
import dev.phoneagent.core.run.Frame
import dev.phoneagent.core.run.Target
import dev.phoneagent.core.run.UiNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The v1 finders, expectations and actions. */
object Builtins {
    val registry = Registry(
        finders = mapOf(
            "text" to byField { it.text },
            "desc" to byField { it.desc },
            "id" to byField { it.id },
            "listItem" to Finder(::listItem),
            "lastMessage" to Finder(::lastMessage),
            // The same for an empty tree, from the screenshot's layout (WeChat).
            "lastBubble" to object : Finder {
                // Reads the screenshot, which runs only take when the pack needs OCR.
                override val needs = setOf("ocr")
                override fun find(frame: Frame, spec: JsonObject, registry: Registry) = frame.screenshot?.let(::lastBubble)?.let { Target(it) }
            },
            "chatRow" to object : Finder {
                override val needs = setOf("ocr")
                override fun find(frame: Frame, spec: JsonObject, registry: Registry) = chatRow(frame, spec)
            },
            "ocrText" to object : Finder {
                override val needs = setOf("ocr")
                override fun find(frame: Frame, spec: JsonObject, registry: Registry) = ocrText(frame, spec)
            },
        ),
        expectations = mapOf(
            "anyText" to Expectation { frame, arg -> texts(arg).let { (words, region) -> words.any { onScreen(frame, it, region) } } },
            "gone" to Expectation { frame, arg -> texts(arg).let { (words, region) -> words.none { onScreen(frame, it, region) } } },
            "toast" to Expectation { frame, arg ->
                val want = arg.jsonPrimitive.content
                frame.toasts.any { want in it }
            },
        ),
        actions = mapOf(
            "launch" to Action(needsTarget = false) { device, step, _ -> device.launch(step.app!!) },
            "tap" to Action(needsTarget = true) { device, _, target -> device.press(target!!, long = false) },
            "long" to Action(needsTarget = true) { device, _, target -> device.press(target!!, long = true) },
            "back" to Action(needsTarget = false) { device, _, _ -> device.back() },
        ),
        capabilities = setOf("ocr"),
    )

    /**
     * The first chat-list row (from the screenshot, for an empty tree) whose name, read by OCR
     * beside the avatar, contains none of `skip`: entries that are not chats (official accounts,
     * news, the team account) are passed over. The target is the whole row.
     */
    private fun chatRow(frame: Frame, spec: JsonObject): Target? {
        val skip = spec["skip"]?.let(::strings).orEmpty().map { it.filterNot(Char::isWhitespace) }
        val shot = frame.screenshot ?: return null
        for ((avatar, name) in chatRows(shot)) {
            val read = frame.ocrLine(name)?.text?.filterNot(Char::isWhitespace) ?: continue
            if (skip.none { it in read }) return Target(Box(avatar.left, avatar.top, (0.95 * shot.width).toInt(), avatar.bottom))
        }
        return null
    }

    /**
     * Text read by OCR, for apps whose tree has no text (WeChat). `region` or `near` (see
     * [area]) limits it to part of the screen. Matching is the same as `text`
     * (whitespace ignored, since OCR spaces vary). The target is a point: no node.
     */
    private fun ocrText(frame: Frame, spec: JsonObject): Target? {
        val want = spec.getValue("ocrText").jsonPrimitive.content.filterNot(Char::isWhitespace)
        val exact = spec["exact"]?.jsonPrimitive?.boolean == true
        val box = area(frame, spec)
        frame.screenshot ?: return null
        val hits = frame.ocr(box).map { it to it.text.filterNot(Char::isWhitespace) }
        val hit = hits.firstOrNull { it.second == want } ?: if (exact) null else hits.firstOrNull { want in it.second }
        return hit?.let { Target(it.first.bounds) }
    }

    /** Exact match first, then the first node containing the text. */
    // `"exact": true` turns off the contains fallback, for short labels like "消息" that longer ones ("精华消息") contain.
    private fun byField(field: (UiNode) -> String?) = Finder { frame, spec, _ ->
        val want = spec.values.first().jsonPrimitive.content
        val exact = spec["exact"]?.jsonPrimitive?.boolean == true
        val nodes = frame.nodes().filter { field(it) != null }.toList()
        val hit = nodes.firstOrNull { field(it)!!.trim() == want }
            ?: if (exact) null else nodes.firstOrNull { want in field(it)!! }
        hit?.let { Target(it.bounds, it) }
    }

    /**
     * Item N (negative counts from the end) of a list: the direct children of the container
     * found by `in`, or of the scrollable node or list view with the most such children,
     * that hold something actionable, ordered top to bottom. The target is the item's largest
     * actionable node, innermost on a tie: QQ handles taps on an inner same-size row, and in
     * chats the message bubble rather than the (also long-clickable) avatar.
     * shortcut: tuned on QQ and Quark 2026-10 trees only; recheck for WeChat (M3).
     */
    private fun listItem(frame: Frame, spec: JsonObject, registry: Registry): Target? {
        val index = spec.getValue("listItem").jsonPrimitive.int
        val container = spec["in"]?.let { registry.find(frame, it.jsonObject)?.node }
            // Not just the largest scrollable: QQ and Quark wrap the real list in a same-size pager,
            // and a list that fits one screen is not scrollable (QQ with few chats).
            ?: frame.nodes().filter { it.scrollable || it.className?.endsWith("RecyclerView") == true || it.className?.endsWith("ListView") == true }
                .maxWithOrNull(compareBy<UiNode> { list -> list.children.count { it.walk().any(::actionable) } }.thenBy { it.bounds.area })
            ?: return null
        val items = container.children.filter { item -> item.walk().any(::actionable) }.sortedBy { it.bounds.top }
        val item = items.getOrNull(if (index < 0) items.size + index else index) ?: return null
        val actionables = item.walk().filter(::actionable).toList()
        val largest = actionables.maxOf { it.bounds.area }
        val node = actionables.last { it.bounds.area == largest }
        return Target(node.bounds, node)
    }

    /**
     * The bottom-most message bubble in the chat (the largest scrollable list). A message has a
     * long-pressable bubble narrower than the list; system notices (recalls, joins, 精华) are
     * full-width rows, so they are skipped. Based on QQ chat trees for text, image, video, voice.
     */
    private fun lastMessage(frame: Frame, spec: JsonObject, registry: Registry): Target? {
        val list = frame.nodes().filter { it.scrollable }.maxByOrNull { it.bounds.area } ?: return null
        val width = list.bounds.right - list.bounds.left
        for (item in list.children.sortedByDescending { it.bounds.top }) {
            val bubble = item.walk().filter { it.longClickable && it.bounds.right - it.bounds.left < width }
                .maxByOrNull { it.bounds.area } ?: continue
            return Target(bubble.bounds, bubble)
        }
        return null
    }

    private fun actionable(node: UiNode) = node.clickable || node.longClickable

    private fun strings(arg: JsonElement): List<String> = arg.jsonArray.map { it.jsonPrimitive.content }

    /** `[left, top, right, bottom]` as fractions of the screen, checked when the pack loads. */
    private fun region(arg: JsonElement): List<Double> = arg.jsonArray.map { it.jsonPrimitive.double }.also { r ->
        require(r.size == 4 && r.all { it in 0.0..1.0 } && r[0] < r[2] && r[1] < r[3]) { "region is [left, top, right, bottom], fractions 0..1" }
    }

    /**
     * The part of the screen to OCR (null: all of it; a whole-screen OCR takes 3–5 s on the phone):
     * - `"region": [left, top, right, bottom]`, fractions of the screen;
     * - `"near": f`, a full-width band reaching f of the screen height above and below what the run
     *   last acted on ([Frame.focus]), e.g. the menu a long-press opens beside a message. A find on
     *   the frame an expectation just held on gets the same band, so it reuses that OCR.
     * Checked when the pack loads (the dry run has no screenshot, so only the arguments are read).
     */
    private fun area(frame: Frame, spec: JsonObject): Box? {
        val region = spec["region"]?.let(::region)
        val near = spec["near"]?.jsonPrimitive?.double?.also { require(it > 0 && it <= 1) { "near is a fraction of the screen height, 0..1" } }
        val shot = frame.screenshot ?: return null
        val (w, h) = shot.width to shot.height
        region?.let { r -> return Box((r[0] * w).toInt(), (r[1] * h).toInt(), (r[2] * w).toInt(), (r[3] * h).toInt()) }
        val focus = frame.focus ?: return null
        return near?.let { Box(0, maxOf(0, focus.top - (it * h).toInt()), w, minOf(h, focus.bottom + (it * h).toInt())) }
    }

    /** Words to look for: a list, or `{"text": [...]}` with `region` or `near` (see [area]) to OCR only part of the screen. */
    private fun texts(arg: JsonElement): Pair<List<String>, JsonObject?> =
        if (arg is JsonObject) strings(arg.getValue("text")) to arg else strings(arg) to null

    // The tree is still searched whole: it is cheap.
    private fun onScreen(frame: Frame, text: String, where: JsonObject? = null): Boolean =
        frame.nodes().any { node -> listOfNotNull(node.text, node.desc).any { text in it } } ||
            frame.ocr(where?.let { area(frame, it) }).any { text in it.text }
}
