package dev.phoneagent.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Resolves a find spec such as `{"text": "复制"}` against one frame. */
fun interface Finder {
    fun find(frame: Frame, spec: JsonObject, registry: Registry): Target?
}

/** Checks an expectation such as `{"anyText": ["复制"]}` against one frame. */
fun interface Expectation {
    fun holds(frame: Frame, arg: JsonElement): Boolean
}

/** Actions are added in app code only, after a safety review; packs can only name them. */
class Action(val needsTarget: Boolean, val perform: (Device, Step, Target?) -> Boolean)

/**
 * The extension points. Everything a pack may reference is registered here by name;
 * an unknown name is rejected when the pack is loaded (see [validate]).
 */
class Registry(
    val finders: Map<String, Finder>,
    val expectations: Map<String, Expectation>,
    val actions: Map<String, Action>,
    val capabilities: Set<String> = emptySet(),
) {
    fun find(frame: Frame, spec: JsonObject): Target? =
        finders[spec.keys.first()]?.find(frame, spec, this)

    fun holds(frame: Frame, expect: JsonObject): Boolean =
        expect.all { (name, arg) -> expectations.getValue(name).holds(frame, arg) }
}
