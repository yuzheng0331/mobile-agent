package dev.phoneagent.core.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A task pack. Packs are data only: they can name registered finders, expectations and actions, nothing else. */
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
    val steps: List<Step>,
)

@Serializable
data class Step(
    @SerialName("do") val action: String,
    val app: String? = null,
    val target: TargetSpec? = null,
    val expect: JsonObject? = null,
    val ifMissing: String? = null,
    val timeoutMs: Long = 5_000,
    val maxRetry: Int = 1,
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
