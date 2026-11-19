package dev.phoneagent.core.pack

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The pack's site: everything a run may call on the network, as named operations. Requests are
 * built by the runtime under [base] (host plus path prefix); packs never hold paths, headers or
 * query strings. Operations are Supabase-style: a table read (`select`), a one-row insert
 * (`insert`), or a cloud function call (`function`).
 */
@Serializable
data class Site(val base: String, val ops: Map<String, SiteOp>)

/**
 * One operation. `params` are what a step must pass in `with`; `returns` are the fields read
 * from each returned row (a select gives a list of rows, the others one object). Types are
 * `"string"`, `"number"`, `"bool"` (`?` allows null) or a list of allowed strings (null allowed
 * if listed). A select's `where` maps columns to a literal or `{param}`, compared for equality.
 */
@Serializable
data class SiteOp(
    val select: String? = null,
    val insert: String? = null,
    val function: String? = null,
    val where: Map<String, String> = emptyMap(),
    val order: String? = null,
    val params: Map<String, JsonElement> = emptyMap(),
    val returns: Map<String, JsonElement> = emptyMap(),
) {
    /** Inserts and functions change the site: never resent when the answer is lost. */
    val writes: Boolean get() = select == null
}

/** The type of a value in expressions, site params and returns. */
sealed interface Type {
    /** `string`, `number` or `bool`; [oneOf] lists a string's allowed values (a literal's is its own value). */
    data class Scalar(val kind: String, val nullable: Boolean = false, val oneOf: Set<String>? = null) : Type
    data class Obj(val fields: Map<String, Type>) : Type
    data class ListOf(val item: Type) : Type
    data object Null : Type
}

val STRING = Type.Scalar("string")
val NUMBER = Type.Scalar("number")
val BOOL = Type.Scalar("bool")

/** A declared type: `"string"`, `"number?"`, `["active", "done"]`, ... */
fun parseType(spec: JsonElement): Type.Scalar {
    if (spec is JsonArray) {
        val values = spec.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString || p is JsonNull } ?: error("allowed values are strings or null") }
        val oneOf = values.filter { it !is JsonNull }.map { it.content }.toSet()
        require(oneOf.isNotEmpty()) { "no allowed values" }
        return Type.Scalar("string", nullable = values.any { it is JsonNull }, oneOf = oneOf)
    }
    val name = (spec as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("a type is a name or a list of allowed values")
    val kind = name.removeSuffix("?")
    require(kind in setOf("string", "number", "bool")) { "unknown type $name" }
    return Type.Scalar(kind, nullable = name.endsWith("?"))
}

/** Whether a value received from the site has this type. */
fun Type.accepts(value: JsonElement): Boolean = when (this) {
    is Type.Scalar -> if (value is JsonNull) nullable else value is JsonPrimitive && when (kind) {
        "string" -> value.isString && (oneOf == null || value.content in oneOf)
        "number" -> !value.isString && value.content.toBigDecimalOrNull() != null
        else -> !value.isString && value.booleanOrNull != null
    }
    else -> false
}

/** Whether every value of [from] is a value of this type (literals carry their value in `oneOf`). */
fun Type.admits(from: Type): Boolean = when {
    from is Type.Null -> this is Type.Scalar && nullable
    this is Type.Scalar && from is Type.Scalar ->
        kind == from.kind && (nullable || !from.nullable) && (oneOf == null || from.oneOf != null && oneOf.containsAll(from.oneOf))
    else -> this == from
}
