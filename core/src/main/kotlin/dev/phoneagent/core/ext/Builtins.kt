package dev.phoneagent.core.ext

import dev.phoneagent.core.run.Frame
import dev.phoneagent.core.run.Target
import dev.phoneagent.core.run.UiNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    private fun byField(field: (UiNode) -> String?) = Finder { frame, spec, _ ->
        val want = spec.values.first().jsonPrimitive.content
        val nodes = frame.nodes().filter { field(it) != null }.toList()
        val hit = nodes.firstOrNull { field(it)!!.trim() == want } ?: nodes.firstOrNull { want in field(it)!! }
        hit?.let { Target(it.bounds, it) }
    }

    /**
     * Item N (negative counts from the end) of a list: the direct children of the container
     * found by `in`, or of the largest scrollable node (else the list view with the most rows),
     * that hold something actionable, ordered top to bottom. The target is the item's largest
     * actionable node, innermost on a tie: QQ handles taps on an inner same-size row, and in
     * chats the message bubble rather than the (also long-clickable) avatar.
     * shortcut: tuned on QQ 2026-10 trees only; recheck for WeChat (M3).
     */
    private fun listItem(frame: Frame, spec: JsonObject, registry: Registry): Target? {
        val index = spec.getValue("listItem").jsonPrimitive.int
        val container = spec["in"]?.let { registry.find(frame, it.jsonObject)?.node }
            ?: frame.nodes().filter { it.scrollable }.maxByOrNull { it.bounds.area }
            // A list that fits one screen is not scrollable (QQ with few chats); QQ also nests a
            // same-size page RecyclerView around it, so pick the list view with the most rows.
            ?: frame.nodes().filter { it.className?.endsWith("RecyclerView") == true || it.className?.endsWith("ListView") == true }
                .maxByOrNull { list -> list.children.count { it.clickable || it.longClickable } }
            ?: return null
        val items = container.children.filter { item -> item.walk().any(::actionable) }.sortedBy { it.bounds.top }
        val item = items.getOrNull(if (index < 0) items.size + index else index) ?: return null
        val actionables = item.walk().filter(::actionable).toList()
        val largest = actionables.maxOf { it.bounds.area }
        val node = actionables.last { it.bounds.area == largest }
        return Target(node.bounds, node)
    }

    private fun actionable(node: UiNode) = node.clickable || node.longClickable

    private fun strings(arg: JsonElement): List<String> = arg.jsonArray.map { it.jsonPrimitive.content }

    private fun onScreen(frame: Frame, text: String): Boolean =
        frame.nodes().any { node -> listOfNotNull(node.text, node.desc).any { text in it } } ||
            frame.ocr().any { text in it.text }
}
