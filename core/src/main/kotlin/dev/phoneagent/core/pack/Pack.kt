package dev.phoneagent.core.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A task pack. Packs are data only: they can name registered finders, expectations and actions, and their own site's operations, nothing else. */
@Serializable
data class Pack(
    val schemaVersion: Int,
    val id: String,
    val version: String = "0",
    val author: String = "",
    val aliases: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val needs: List<String> = emptyList(),
    val testedWith: Map<String, String> = emptyMap(),
    val site: Site? = null,
    val steps: List<Step>,
)

/**
 * One step, exactly one of: `do` (an action; `http` calls a site operation), `let` (new variables),
 * `when` (run `then` if true), `forEach` (run `body` once per item). A failed step stops the run, or with
 * `"onStuck": "skip"` is skipped and reported; a skipped `forEach` round goes on to the next.
 */
@Serializable
data class Step(
    val id: String? = null,
    @SerialName("do") val action: String? = null,
    val app: String? = null,
    val target: TargetSpec? = null,
    val expect: JsonObject? = null,
    val ifMissing: String? = null,
    val timeoutMs: Long = 5_000,
    val maxRetry: Int = 1,
    val onStuck: String = "stop",
    val call: String? = null,
    val with: Map<String, JsonElement> = emptyMap(),
    val save: String? = null,
    val let: Map<String, String>? = null,
    @SerialName("when") val cond: String? = null,
    val then: List<Step>? = null,
    val forEach: String? = null,
    @SerialName("as") val item: String? = null,
    val key: String? = null,
    val max: Int? = null,
    val body: List<Step>? = null,
)

/** `find` entries are tried in order, cheapest first; each is `{"<finder>": arg, ...options}`. */
@Serializable
data class TargetSpec(val intent: String, val find: List<JsonObject>)

object Packs {
    const val SCHEMA_VERSION = 1

    // Strict on purpose: a field this build does not know is an error, not ignored.
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): Pack = json.decodeFromString(Pack.serializer(), text)
}
