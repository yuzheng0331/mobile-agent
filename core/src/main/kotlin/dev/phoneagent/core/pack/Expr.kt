package dev.phoneagent.core.pack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The pack's expressions: a fixed subset in the spirit of CEL, not Turing-complete, so evaluation
 * time is linear in the expression's length. Literals (`'text'`, `12`, `true`, `null`), paths
 * (`t.points`), `== != < <= > >=`, `in` (substring, or member of a list), `&& || !`, `+` (numbers or
 * strings), `-`, `size(x)`, `string(x)`, parentheses. Types are checked when the pack loads ([type]),
 * so evaluation ([eval]) trusts them.
 */
sealed interface Expr {
    data class Lit(val value: JsonElement) : Expr
    data class Path(val parts: List<String>) : Expr
    data class Not(val arg: Expr) : Expr
    data class Op(val op: String, val left: Expr, val right: Expr) : Expr
    data class Fn(val name: String, val arg: Expr) : Expr

    companion object {
        fun parse(text: String): Expr = Parser(text).run { or().also { require(peek() == null) { "unexpected ${peek()} in $text" } } }
    }
}

private val TOKEN = Regex("""\s*(?:(\d+(?:\.\d+)?)|'((?:[^'\\]|\\.)*)'|([A-Za-z_]\w*)|(==|!=|<=|>=|&&|\|\||[<>!+\-().]))""")

private class Parser(private val text: String) {
    private val tokens = buildList {
        var at = 0
        while (text.substring(at).isNotBlank()) {
            val m = TOKEN.matchAt(text, at) ?: throw IllegalArgumentException("cannot read ${text.substring(at).trim()}")
            add(m.groups[1]?.let { JsonPrimitive(it.value.toBigDecimal()) }
                ?: m.groups[2]?.let { JsonPrimitive(it.value.replace(Regex("""\\(.)"""), "$1")) }
                ?: m.groupValues[3].ifEmpty { m.groupValues[4] })
            at = m.range.last + 1
        }
    }
    private var next = 0

    fun peek(): Any? = tokens.getOrNull(next)
    private fun take(): Any? = tokens.getOrNull(next++)
    private fun expect(token: String) = require(take() == token) { "expected $token in $text" }

    fun or(): Expr = binary(listOf("||"), ::and)
    private fun and(): Expr = binary(listOf("&&"), ::compare)
    private fun compare(): Expr {
        val left = add()
        val op = peek() as? String ?: return left
        if (op !in setOf("==", "!=", "<", "<=", ">", ">=", "in")) return left
        take()
        return Expr.Op(op, left, add())
    }
    private fun add(): Expr = binary(listOf("+", "-"), ::unary)
    private fun unary(): Expr = if (peek() == "!") { take(); Expr.Not(unary()) } else primary()

    private fun binary(ops: List<String>, operand: () -> Expr): Expr {
        var left = operand()
        while (peek() in ops) left = Expr.Op(take() as String, left, operand())
        return left
    }

    private fun primary(): Expr = when (val token = take()) {
        is JsonPrimitive -> Expr.Lit(token)
        "(" -> or().also { expect(")") }
        "true", "false" -> Expr.Lit(JsonPrimitive(token == "true"))
        "null" -> Expr.Lit(JsonNull)
        "size", "string" -> { expect("("); Expr.Fn(token as String, or()).also { expect(")") } }
        is String -> if (token.first().isLetter() || token.first() == '_') {
            val parts = mutableListOf(token)
            while (peek() == ".") { take(); parts += take() as? String ?: throw IllegalArgumentException("expected a field name in $text") }
            Expr.Path(parts)
        } else throw IllegalArgumentException("unexpected $token in $text")
        else -> throw IllegalArgumentException("$text ends too early")
    }
}

/** The expression's type in [scope]; throws, naming the problem, if it does not type-check. */
fun Expr.type(scope: Map<String, Type>): Type = when (this) {
    is Expr.Lit -> when {
        value is JsonNull -> Type.Null
        value.jsonPrimitive.isString -> Type.Scalar("string", oneOf = setOf(value.jsonPrimitive.content))
        value.jsonPrimitive.content in setOf("true", "false") -> BOOL
        else -> NUMBER
    }
    is Expr.Path -> parts.drop(1).fold(scope[parts[0]] ?: throw IllegalArgumentException("${parts[0]} is not defined")) { type, field ->
        (type as? Type.Obj)?.fields?.get(field) ?: throw IllegalArgumentException("${parts.joinToString(".")}: no field $field")
    }
    is Expr.Not -> BOOL.also { need(arg, scope, "bool") }
    is Expr.Fn -> when (name) {
        "size" -> NUMBER.also { require(arg.type(scope).let { it is Type.ListOf || it.isScalar("string") }) { "size takes a string or a list" } }
        else -> STRING.also { require(arg.type(scope) is Type.Scalar && !arg.type(scope).nullable()) { "string takes a value that is never null" } }
    }
    is Expr.Op -> {
        val l = left.type(scope)
        val r = right.type(scope)
        when (op) {
            "&&", "||" -> BOOL.also { need(left, scope, "bool"); need(right, scope, "bool") }
            "==", "!=" -> BOOL.also { require(comparable(l, r)) { "${show(left)} and ${show(right)} can never be equal" } }
            "in" -> BOOL.also {
                val ok = if (r is Type.ListOf) comparable(l, r.item) else l.isScalar("string") && r.isScalar("string")
                require(ok) { "in takes a string in a string, or an item in a list" }
            }
            "+" -> listOf("number", "string").firstOrNull { l.isScalar(it) && r.isScalar(it) }?.let { Type.Scalar(it) }
                ?: throw IllegalArgumentException("+ takes two numbers or two strings that are never null")
            else -> (if (op == "-") listOf("number") else listOf("number", "string"))
                .firstOrNull { l.isScalar(it) && r.isScalar(it) }?.let { if (op == "-") NUMBER else BOOL }
                ?: throw IllegalArgumentException("$op takes two numbers${if (op == "-") "" else " or two strings"} that are never null")
        }
    }
}

private fun need(expr: Expr, scope: Map<String, Type>, kind: String) =
    require(expr.type(scope).isScalar(kind)) { "${show(expr)} is not a $kind that is never null" }

private fun Type.isScalar(kind: String) = this is Type.Scalar && this.kind == kind && !nullable

private fun Type.nullable() = this is Type.Null || this is Type.Scalar && nullable

/** Same kind (or null against a nullable), and allowed values that overlap: `t.status == 'actve'` is a typo. */
private fun comparable(a: Type, b: Type): Boolean = when {
    a is Type.Null || b is Type.Null -> a.nullable() && b.nullable()
    a is Type.Scalar && b is Type.Scalar -> a.kind == b.kind && (a.oneOf == null || b.oneOf == null || a.oneOf.any { it in b.oneOf })
    else -> false
}

private fun show(expr: Expr): String = when (expr) {
    is Expr.Path -> expr.parts.joinToString(".")
    is Expr.Lit -> expr.value.toString()
    else -> "the expression"
}

fun Expr.eval(vars: Map<String, JsonElement>): JsonElement = when (this) {
    is Expr.Lit -> value
    is Expr.Path -> parts.drop(1).fold(vars.getValue(parts[0])) { value, field -> value.jsonObject.getValue(field) }
    is Expr.Not -> JsonPrimitive(!arg.eval(vars).jsonPrimitive.boolean)
    is Expr.Fn -> when (val v = arg.eval(vars)) {
        is JsonArray -> JsonPrimitive(v.size)
        else -> if (name == "size") JsonPrimitive(v.jsonPrimitive.content.length) else JsonPrimitive(v.jsonPrimitive.content)
    }
    is Expr.Op -> when (op) {
        "&&" -> JsonPrimitive(left.eval(vars).jsonPrimitive.boolean && right.eval(vars).jsonPrimitive.boolean)
        "||" -> JsonPrimitive(left.eval(vars).jsonPrimitive.boolean || right.eval(vars).jsonPrimitive.boolean)
        else -> {
            val l = left.eval(vars)
            val r = right.eval(vars)
            when (op) {
                "==" -> JsonPrimitive(same(l, r))
                "!=" -> JsonPrimitive(!same(l, r))
                "in" -> JsonPrimitive(if (r is JsonArray) r.any { same(l, it) } else l.jsonPrimitive.content in r.jsonPrimitive.content)
                "+" -> if (l.jsonPrimitive.isString) JsonPrimitive(l.jsonPrimitive.content + r.jsonPrimitive.content) else JsonPrimitive(num(l) + num(r))
                "-" -> JsonPrimitive(num(l) - num(r))
                else -> {
                    val order = if (l.jsonPrimitive.isString) l.jsonPrimitive.content.compareTo(r.jsonPrimitive.content) else num(l).compareTo(num(r))
                    JsonPrimitive(when (op) { "<" -> order < 0; "<=" -> order <= 0; ">" -> order > 0; else -> order >= 0 })
                }
            }
        }
    }
}

private fun num(value: JsonElement) = value.jsonPrimitive.content.toBigDecimal()

private fun same(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
    a.jsonPrimitive.isString -> b.jsonPrimitive.isString && a.jsonPrimitive.content == b.jsonPrimitive.content
    else -> !b.jsonPrimitive.isString && a.jsonPrimitive.content.toBigDecimalOrNull()?.let { x ->
        b.jsonPrimitive.content.toBigDecimalOrNull()?.let { x.compareTo(it) == 0 }
    } ?: (a.jsonPrimitive.content == b.jsonPrimitive.content)
}

private val TEMPLATE = Regex("""\{([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)}""")

/** Paths named by `{...}` in a string argument. */
fun templates(text: String): List<Expr.Path> = TEMPLATE.findAll(text).map { Expr.Path(it.groupValues[1].split(".")) }.toList()

/** The type a string argument with templates gives: exactly one template keeps its value's type; mixed text is a string. */
fun templateType(text: String, scope: Map<String, Type>): Type {
    val paths = templates(text)
    paths.forEach { path ->
        val type = path.type(scope)
        require(type is Type.Scalar && !type.nullable) { "{${path.parts.joinToString(".")}} may be null or is not a single value" }
    }
    if (paths.isEmpty()) return Type.Scalar("string", oneOf = setOf(text))
    return if (TEMPLATE.matchEntire(text) != null) paths.single().type(scope) else STRING
}

/** Replaces `{path}` in every string of [arg] (keys stay as they are); a string that is one template keeps the value's type. */
fun fill(arg: JsonElement, vars: Map<String, JsonElement>): JsonElement = when (arg) {
    is JsonObject -> JsonObject(arg.mapValues { fill(it.value, vars) })
    is JsonArray -> JsonArray(arg.map { fill(it, vars) })
    is JsonNull -> arg
    is JsonPrimitive -> if (!arg.isString || templates(arg.content).isEmpty()) arg else {
        TEMPLATE.matchEntire(arg.content)?.let { Expr.Path(it.groupValues[1].split(".")).eval(vars) }
            ?: JsonPrimitive(TEMPLATE.replace(arg.content) { Expr.Path(it.groupValues[1].split(".")).eval(vars).jsonPrimitive.content })
    }
}
