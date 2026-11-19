package dev.phoneagent.core.run

import dev.phoneagent.core.pack.Site
import dev.phoneagent.core.pack.SiteOp
import dev.phoneagent.core.pack.Type
import dev.phoneagent.core.pack.accepts
import dev.phoneagent.core.pack.parseType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Longer answers are refused: a site gone wrong must not fill the phone's memory. */
const val MAX_RESPONSE_CHARS = 1_000_000

data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

/** [status] 0: no answer (could not connect, or timed out after sending). */
data class HttpResponse(val status: Int, val body: String)

/**
 * The pack's site, as the logged-in user. The app's implementation is Phone Agent's only network
 * code (invariant 1): it adds the user's credentials, renews them once on 401, never follows a
 * redirect, and stops reading a body past [MAX_RESPONSE_CHARS]. Tests use a scripted fake.
 */
interface Http {
    /** The logged-in user's id on the site: `me.id` in packs. */
    val userId: String

    fun send(request: HttpRequest): HttpResponse
}

/** What one site call gave: the declared fields only, or why it failed ([detail]: the site's message). */
class Answer(val outcome: Outcome, val value: JsonElement? = null, val detail: String? = null)

private val SCHEMA_CODES = setOf("42703", "42P01", "PGRST200", "PGRST204", "PGRST205")

/** The request for [op] under the site's base: PostgREST for tables, edge functions for functions. */
fun request(site: Site, op: SiteOp, args: Map<String, JsonElement>): HttpRequest {
    val select = "select=" + op.returns.keys.joinToString(",")
    op.select?.let { table ->
        val filters = op.where.map { (column, value) ->
            val v = Regex("""\{(\w+)}""").matchEntire(value)?.let { args.getValue(it.groupValues[1]) } ?: JsonPrimitive(value)
            // Values are encoded and always behind eq.: no data can add another filter or operator.
            column + "=" + if (v is JsonNull) "is.null" else "eq." + encode(v.jsonPrimitive.content)
        }
        val query = (listOf(select) + filters + listOfNotNull(op.order?.let { "order=$it" })).joinToString("&")
        return HttpRequest("GET", "${site.base}/rest/v1/$table?$query", mapOf("Accept" to "application/json"), null)
    }
    val json = mapOf("Accept" to "application/json", "Content-Type" to "application/json")
    val body = JsonObject(args).toString()
    op.insert?.let { table ->
        return HttpRequest("POST", "${site.base}/rest/v1/$table?$select", json + ("Prefer" to "return=representation"), body)
    }
    return HttpRequest("POST", "${site.base}/functions/v1/${op.function}", json, body)
}

/** Reads [response] to [op]: every declared field must be there with its type, or the site has changed. */
fun answer(op: SiteOp, response: HttpResponse): Answer {
    val status = response.status
    if (status !in 200..299) {
        val error = runCatching { Json.parseToJsonElement(response.body) as JsonObject }.getOrNull()
        val message = listOf("message", "error", "msg").firstNotNullOfOrNull { (error?.get(it) as? JsonPrimitive)?.contentOrNull }?.take(200)
        val outcome = when {
            status == 401 -> Outcome.SITE_AUTH
            status == 403 -> Outcome.SITE_DENIED
            status == 429 -> Outcome.SITE_BUSY
            // A write may have been done before the answer was lost: never resend it.
            status == 0 || status >= 500 -> if (op.writes) Outcome.SITE_UNKNOWN else Outcome.SITE_DOWN
            status == 404 || status in 300..399 || (error?.get("code") as? JsonPrimitive)?.contentOrNull in SCHEMA_CODES -> Outcome.SITE_CHANGED
            else -> Outcome.SITE_REFUSED
        }
        return Answer(outcome, detail = message)
    }
    if (response.body.length > MAX_RESPONSE_CHARS) return Answer(Outcome.SITE_CHANGED, detail = "返回超过 $MAX_RESPONSE_CHARS 字符")
    val json = runCatching { Json.parseToJsonElement(response.body) }.getOrNull()
        ?: return Answer(Outcome.SITE_CHANGED, detail = "返回的不是 JSON")
    val rows = when {
        op.select != null -> json as? JsonArray
        op.insert != null -> (json as? JsonArray)?.takeIf { it.size == 1 }
        else -> JsonArray(listOf(json))
    } ?: return Answer(Outcome.SITE_CHANGED, detail = "返回的结构不对")
    val fields = op.returns.mapValues { parseType(it.value) }
    val picked = rows.map { row ->
        val obj = row as? JsonObject ?: return Answer(Outcome.SITE_CHANGED, detail = "返回的结构不对")
        // Only the declared fields are kept: nothing else the site sends reaches the pack.
        JsonObject(fields.mapValues { (name, type) ->
            val value = obj[name]
            if (value == null || !type.accepts(value)) return Answer(Outcome.SITE_CHANGED, detail = mismatch(name, type, value))
            value
        })
    }
    return Answer(Outcome.SUCCESS, if (op.select != null) JsonArray(picked) else picked.single())
}

private fun mismatch(name: String, type: Type, value: JsonElement?): String = when {
    value == null -> "没有字段 $name"
    type is Type.Scalar && type.oneOf != null && value is JsonPrimitive && value.isString -> "$name 的值“${value.content.take(20)}”不在可选值里"
    else -> "$name 的类型不对"
}

// The String overload: the Charset one needs Android 13.
private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
