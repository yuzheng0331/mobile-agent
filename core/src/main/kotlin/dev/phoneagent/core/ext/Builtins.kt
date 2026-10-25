package dev.phoneagent.core.ext

import dev.phoneagent.core.run.Frame
import dev.phoneagent.core.run.Target
import dev.phoneagent.core.run.UiNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
        ),
        expectations = mapOf(
            "anyText" to Expectation { frame, arg -> strings(arg).any { onScreen(frame, it) } },
            "gone" to Expectation { frame, arg -> strings(arg).none { onScreen(frame, it) } },
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
    )

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

    private fun onScreen(frame: Frame, text: String): Boolean =
        frame.nodes().any { node -> listOfNotNull(node.text, node.desc).any { text in it } } ||
            frame.ocr().any { text in it.text }
}
