package dev.phoneagent.core

/**
 * Picks the pack whose alias the sentence mentions (or that mentions the sentence),
 * ignoring spaces and punctuation. Returns null when nothing or more than one matches,
 * so the UI asks instead of guessing.
 */
fun route(sentence: String, packs: List<Pack>): Pack? {
    val said = normalize(sentence)
    if (said.isEmpty()) return null
    return packs.filter { pack ->
        pack.aliases.map(::normalize).any { it.isNotEmpty() && (it in said || said in it) }
    }.singleOrNull()
}

private fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()
