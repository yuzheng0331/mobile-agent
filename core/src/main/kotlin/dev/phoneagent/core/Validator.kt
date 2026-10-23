package dev.phoneagent.core

import kotlinx.serialization.json.JsonObject

private val EMPTY_FRAME = Frame(packageName = null, tree = null)

/**
 * Checks a pack against what this build can run, before it runs: unknown names,
 * malformed arguments, launches outside `requires`. Returns the problems found;
 * an empty list means the pack can be loaded.
 */
fun validate(pack: Pack, registry: Registry = Builtins.registry): List<String> {
    val problems = mutableListOf<String>()
    if (pack.schemaVersion != Packs.SCHEMA_VERSION) problems += "unsupported schemaVersion ${pack.schemaVersion}"
    (pack.needs - registry.capabilities).forEach { problems += "needs unsupported capability $it" }
    if (pack.steps.isEmpty()) problems += "no steps"
    pack.steps.forEachIndexed { index, step ->
        val at = "step ${index + 1}"
        val action = registry.actions[step.action]
        if (action == null) problems += "$at: unknown action ${step.action}"
        if (action?.needsTarget == true && step.target == null) problems += "$at: ${step.action} needs a target"
        if (step.action == "launch" && step.app !in pack.requires) {
            problems += "$at: launches ${step.app}, which is not in requires"
        }
        if (step.timeoutMs <= 0 || step.maxRetry < 0) problems += "$at: timeoutMs and maxRetry must be positive"
        step.target?.let { target ->
            if (target.find.isEmpty()) problems += "$at: target has no finders"
            target.find.forEach { checkFinder(it, registry, at, problems) }
        }
        step.expect?.forEach { (name, arg) ->
            val expectation = registry.expectations[name]
            if (expectation == null) {
                problems += "$at: unknown expectation $name"
            } else {
                // A dry run on an empty frame surfaces a malformed argument now, not mid-task.
                runCatching { expectation.holds(EMPTY_FRAME, arg) }
                    .onFailure { problems += "$at: bad argument for $name: ${it.message}" }
            }
        }
    }
    return problems
}

private fun checkFinder(spec: JsonObject, registry: Registry, at: String, problems: MutableList<String>) {
    val name = spec.keys.firstOrNull()
    if (name == null || name !in registry.finders) {
        problems += "$at: unknown finder $name"
        return
    }
    (spec["in"] as? JsonObject)?.let { checkFinder(it, registry, at, problems) }
    runCatching { registry.find(EMPTY_FRAME, spec) }
        .onFailure { problems += "$at: bad argument for $name: ${it.message}" }
}
