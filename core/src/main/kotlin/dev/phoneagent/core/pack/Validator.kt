package dev.phoneagent.core.pack

import dev.phoneagent.core.ext.Builtins
import dev.phoneagent.core.ext.Registry
import dev.phoneagent.core.run.Frame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

private val EMPTY_FRAME = Frame(packageName = null, tree = null)

/** The most steps one run may take, counting every `forEach` at its `max`. */
const val MAX_STEPS = 10_000L

private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val FUNCTION = Regex("[A-Za-z0-9_-]+")
private val ORDER = Regex("[A-Za-z_][A-Za-z0-9_]*\\.(asc|desc)")
private val PARAM = Regex("""\{(\w+)}""")

/** `me`, the logged-in user, in packs with a site. */
private val ME = Type.Obj(mapOf("id" to STRING))

/**
 * Checks a pack against what this build can run, before it runs: unknown names,
 * malformed arguments, launches outside `requires`, the site's operations, and every
 * variable and expression (defined before use, assigned once, fields that exist, types
 * that match), and the most steps a run can take. Returns the problems found; an empty
 * list means the pack can be loaded.
 */
fun validate(pack: Pack, registry: Registry = Builtins.registry): List<String> {
    val problems = mutableListOf<String>()
    if (pack.schemaVersion != Packs.SCHEMA_VERSION) problems += "unsupported schemaVersion ${pack.schemaVersion}"
    (pack.needs - registry.capabilities).forEach { problems += "needs unsupported capability $it" }
    if (pack.steps.isEmpty()) problems += "no steps"
    pack.site?.let { checkSite(it, problems) }
    all(pack.steps).mapNotNull { it.id }.groupBy { it }.filterValues { it.size > 1 }.keys
        .forEach { problems += "step id $it is used more than once" }
    Checker(pack, registry, problems).block(pack.steps, if (pack.site != null) mapOf("me" to ME) else emptyMap(), "step ")
    val bound = bound(pack.steps)
    if (bound > MAX_STEPS) problems += "a run may take more than $MAX_STEPS steps: lower a forEach max"
    return problems
}

private class Checker(val pack: Pack, val registry: Registry, val problems: MutableList<String>) {
    fun block(steps: List<Step>, outer: Map<String, Type>, prefix: String) {
        var scope = outer
        steps.forEachIndexed { index, step -> scope = step(step, scope, "$prefix${index + 1}") }
    }

    /** Checks one step; returns the scope after it, with what it saved or let. */
    private fun step(step: Step, scope: Map<String, Type>, at: String): Map<String, Type> {
        val kinds = listOfNotNull(
            step.action?.let { "do" }, step.let?.let { "let" }, step.cond?.let { "when" }, step.forEach?.let { "forEach" },
        )
        if (kinds.size != 1) {
            problems += "$at: a step is exactly one of do, let, when, forEach"
            return scope
        }
        misplaced(step).forEach { problems += "$at: $it does not belong in a ${kinds.single()} step" }
        if (step.onStuck !in setOf("stop", "skip")) problems += "$at: onStuck is stop or skip"
        if (step.timeoutMs <= 0 || step.maxRetry < 0) problems += "$at: timeoutMs and maxRetry must be positive"
        return when {
            step.let != null -> step.let.entries.fold(scope) { inner, (name, text) ->
                check(at) { Expr.parse(text).type(inner) }?.let { bind(inner, name, it, at) } ?: inner
            }
            step.cond != null -> scope.also {
                check(at) { require(Expr.parse(step.cond).type(scope) == BOOL) { "when needs a bool that is never null" } }
                if (step.then.isNullOrEmpty()) problems += "$at: when has no then"
                block(step.then.orEmpty(), scope, "$at.")
            }
            step.forEach != null -> scope.also { forEach(step, scope, at) }
            step.action == "http" -> http(step, scope, at)
            else -> scope.also { action(step, scope, at) }
        }
    }

    private fun forEach(step: Step, scope: Map<String, Type>, at: String) {
        val item = check(at) { Expr.parse(step.forEach!!).type(scope) as? Type.ListOf ?: error("forEach needs a list") }?.item
        if (step.max == null || step.max <= 0) problems += "$at: forEach needs a max above 0"
        if (step.item == null) problems += "$at: forEach needs as"
        if (step.body.isNullOrEmpty()) problems += "$at: forEach has no body"
        if (item == null || step.item == null) return
        val inner = bind(scope, step.item, item, at)
        step.key?.let { key ->
            check(at) { require(Expr.parse(key).type(inner).let { it is Type.Scalar && !it.nullable }) { "key is a single value that is never null" } }
        }
        block(step.body.orEmpty(), inner, "$at.")
    }

    /**
     * A site call, checked against the pack's own operations: the parameters it needs are
     * present and typed, and what it returns is bound like a `let`.
     */
    private fun http(step: Step, scope: Map<String, Type>, at: String): Map<String, Type> {
        if ("http" !in registry.capabilities) problems += "$at: this build cannot call sites"
        val op = step.call?.let { pack.site?.ops?.get(it) }
        when {
            pack.site == null -> problems += "$at: http needs the pack's site"
            step.call == null -> problems += "$at: http needs call"
            op == null -> problems += "$at: unknown site operation ${step.call}"
        }
        if (op == null) return scope
        // A malformed type is reported with the site.
        val params = runCatching { op.params.mapValues { (_, type) -> parseType(type) } }.getOrNull() ?: return scope
        (params.keys - step.with.keys).forEach { problems += "$at: ${step.call} needs $it" }
        (step.with.keys - params.keys).forEach { problems += "$at: ${step.call} has no param $it" }
        step.with.forEach { (name, value) ->
            val want = params[name] ?: return@forEach
            check(at) { require(want.admits(argType(value, scope))) { "$name of ${step.call} takes ${describe(want)}" } }
        }
        val row = runCatching { Type.Obj(op.returns.mapValues { (_, type) -> parseType(type) }) }.getOrNull() ?: return scope
        return step.save?.let { bind(scope, it, if (op.select != null) Type.ListOf(row) else row, at) } ?: scope
    }

    private fun action(step: Step, scope: Map<String, Type>, at: String) {
        val action = registry.actions[step.action]
        if (action == null) problems += "$at: unknown action ${step.action}"
        if (action?.needsTarget == true && step.target == null) problems += "$at: ${step.action} needs a target"
        if (step.action == "launch" && step.app !in pack.requires) {
            problems += "$at: launches ${step.app}, which is not in requires"
        }
        // Values from the site may fill a finder's or expectation's text: `{t.name}`.
        (step.target?.find.orEmpty() + listOfNotNull(step.expect)).flatMap(::strings)
            .forEach { text -> check(at) { templateType(text, scope) } }
        step.target?.let { target ->
            if (target.find.isEmpty()) problems += "$at: target has no finders"
            target.find.forEach { checkFinder(it, registry, pack.needs, at, problems) }
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

    /** Variables are assigned once: a name already in scope is refused. */
    private fun bind(scope: Map<String, Type>, name: String, type: Type, at: String): Map<String, Type> {
        if (!NAME.matches(name)) problems += "$at: $name is not a name"
        if (name in scope) problems += "$at: $name is already defined"
        return scope + (name to type)
    }

    private fun <T> check(at: String, block: () -> T): T? =
        runCatching(block).onFailure { problems += "$at: ${it.message}" }.getOrNull()
}

/** A `with` value: a literal, or a string with `{path}` templates. */
private fun argType(value: JsonElement, scope: Map<String, Type>): Type = when {
    value is JsonNull -> Type.Null
    value is JsonPrimitive && value.isString -> templateType(value.content, scope)
    value is JsonPrimitive -> if (value.content in setOf("true", "false")) BOOL else NUMBER
    else -> error("a param takes a single value")
}

private fun describe(type: Type.Scalar) = (type.oneOf?.joinToString("/") ?: type.kind) + if (type.nullable) " or null" else ""

private fun strings(arg: JsonElement): List<String> = when (arg) {
    is JsonObject -> arg.values.flatMap(::strings)
    is JsonArray -> arg.flatMap(::strings)
    is JsonPrimitive -> if (arg.isString) listOf(arg.content) else emptyList()
}

private fun misplaced(step: Step): List<String> = buildList {
    if (step.action == null || step.action == "http") {
        listOf("app" to step.app, "target" to step.target, "expect" to step.expect, "ifMissing" to step.ifMissing)
            .filter { it.second != null }.forEach { add(it.first) }
    }
    if (step.action != "http") {
        listOf("call" to step.call, "with" to step.with.ifEmpty { null }, "save" to step.save).filter { it.second != null }.forEach { add(it.first) }
    }
    if (step.cond == null && step.then != null) add("then")
    if (step.forEach == null) {
        listOf("as" to step.item, "key" to step.key, "max" to step.max, "body" to step.body).filter { it.second != null }.forEach { add(it.first) }
    }
}

private fun checkSite(site: Site, problems: MutableList<String>) {
    val uri = runCatching { URI(site.base) }.getOrNull()
    val local = uri?.host in setOf("127.0.0.1", "localhost")
    val ok = uri != null && uri.host != null && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null &&
        !site.base.endsWith("/") && (uri.scheme == "https" || uri.scheme == "http" && local)
    // Every request is built under base, so base is the whole of what the pack may reach.
    if (!ok) problems += "site base is https://host/prefix (http only for this computer), with no query or trailing /"
    if (site.ops.isEmpty()) problems += "site has no operations"
    site.ops.forEach { (name, op) ->
        val at = "site op $name"
        if (!NAME.matches(name)) problems += "$at: not a name"
        if (listOfNotNull(op.select, op.insert, op.function).size != 1) problems += "$at: exactly one of select, insert, function"
        listOfNotNull(op.select, op.insert).filterNot(NAME::matches).forEach { problems += "$at: table $it is not a name" }
        op.function?.takeUnless(FUNCTION::matches)?.let { problems += "$at: function $it is not a name" }
        (op.params.entries + op.returns.entries).forEach { (field, type) ->
            if (!NAME.matches(field)) problems += "$at: $field is not a name"
            runCatching { parseType(type) }.onFailure { problems += "$at: $field: ${it.message}" }
        }
        if (op.function == null && op.returns.isEmpty()) problems += "$at: returns names the fields to read"
        if (op.select == null && (op.where.isNotEmpty() || op.order != null)) problems += "$at: where and order are for select"
        op.where.forEach { (column, value) ->
            if (!NAME.matches(column)) problems += "$at: where column $column is not a name"
            val param = PARAM.matchEntire(value)?.groupValues?.get(1)
            if (param == null && '{' in value) problems += "$at: a where value is a literal or one {param}"
            if (param != null && param !in op.params) problems += "$at: where uses {$param}, which is not in params"
        }
        op.order?.takeUnless(ORDER::matches)?.let { problems += "$at: order is column.asc or column.desc" }
    }
}

private fun all(steps: List<Step>): List<Step> = steps.flatMap { listOf(it) + all(it.then.orEmpty()) + all(it.body.orEmpty()) }

private fun bound(steps: List<Step>): Long = steps.sumOf { step ->
    when {
        step.forEach != null -> (step.max ?: 0).coerceAtLeast(0).toLong() * bound(step.body.orEmpty())
        step.cond != null -> bound(step.then.orEmpty())
        step.action != null -> 1L
        else -> 0L
    }.coerceAtMost(MAX_STEPS + 1)
}

private fun checkFinder(spec: JsonObject, registry: Registry, needs: List<String>, at: String, problems: MutableList<String>) {
    val name = spec.keys.firstOrNull()
    val finder = registry.finders[name]
    if (name == null || finder == null) {
        problems += "$at: unknown finder $name"
        return
    }
    // Without the capability the run has no recognizer and the finder would never hit.
    (finder.needs - needs.toSet()).forEach { problems += "$at: $name needs \"$it\" in the pack's needs" }
    (spec["in"] as? JsonObject)?.let { checkFinder(it, registry, needs, at, problems) }
    runCatching { registry.find(EMPTY_FRAME, spec) }
        .onFailure { problems += "$at: bad argument for $name: ${it.message}" }
}
