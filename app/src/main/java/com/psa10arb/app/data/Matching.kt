package com.psa10arb.app.data

/**
 * Card-identity matching — ported verbatim from psa10_arb.py / ebay_card_search.py,
 * then generalized from Pikachu-only to a fixed list of top-tier characters.
 *
 * DO NOT loosen same_card()'s "shared number alone isn't enough" rule: promo
 * numbers repeat across unrelated characters/sets, and matching on card number
 * alone previously produced a false-positive match (a Charizard listing paired
 * with a Pikachu candidate, showing a bogus +6552% "profit"). The fix requires
 * (1) one of TOP_TIER_CHARACTERS literally in the title, and (2) at least 2
 * shared significant tokens between titles beyond the bare number.
 */
object Matching {

    /** Same roster this user's existing Python arb scripts already target
     * (charizard_arb.py, pikachu_arb.py, espeon_arb.py, gengar_arb.py,
     * lugia_arb.py, mewtwo_arb.py, umbreon_arb.py) — reused here rather than
     * guessing a new "top tier" list from scratch. */
    val TOP_TIER_CHARACTERS = listOf("Pikachu", "Charizard", "Espeon", "Gengar", "Lugia", "Mewtwo", "Umbreon")

    val GRADE10_RE = Regex("""\b(PSA|CGC)[\s\-]?10\b""", RegexOption.IGNORE_CASE)
    val CARD_ID_RE = Regex("""#\s?(\d{2,4})\b|\b(\d{1,3}/\d{1,3})\b""")
    private val CHARACTER_RE = Regex(
        TOP_TIER_CHARACTERS.joinToString("|") { "\\b${Regex.escape(it)}\\b" },
        RegexOption.IGNORE_CASE,
    )

    /** The matched top-tier character's canonical (capitalized) name, or null. */
    fun detectCharacter(title: String): String? {
        val found = CHARACTER_RE.find(title)?.value ?: return null
        return TOP_TIER_CHARACTERS.first { it.equals(found, ignoreCase = true) }
    }

    val FAKE_RE = Regex(
        """\b(orica|fan\s+art|fan\s+made|fanmade|custom\s+card|proxy""" +
            """|altered\s+art|unofficial|not\s+official|reprint""" +
            """|hand\s+drawn|hand\s+painted|commission(?:ed)?)\b""" +
            """|^fan\s+\w""",
        RegexOption.IGNORE_CASE,
    )

    /** Any grading-company slab marking — used to exclude already-graded
     * listings from the "raw card" search (ported from ebay_card_search.py's
     * _GRADED). */
    val GRADED_RE = Regex(
        """\b(PSA|BGS|CGC|SGC|ACE|GAI|HGA|KSA|GMA|BCCG|MNT)[\s\-]?\d+\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Excludes non-card Pokemon merchandise that sometimes surfaces in the
     * card category (183454) despite the category filter — stickers, coins,
     * pins, etc. Cards only. */
    val NOT_CARD_RE = Regex(
        """\b(stickers?|coins?|pins?|patch(?:es)?|tin|figure|plush|keychain|magnet|poster|pog(?:s)?|medal(?:lion)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    val LOT_RE = Regex(
        """\b(lot|bundle|bulk|set\s+of|x\d+|\d+x""" +
            """|master\s+set|complete\s+set|full\s+set|whole\s+set|entire\s+set""" +
            """|every\s+card|booster\s+box|booster\s+pack""" +
            """|qty\s*[2-9]|quantity\s*[2-9]|[2-9]\s*copies?)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val NOISE_TOKENS = setOf(
        "psa", "cgc", "pokemon", "pokmon", "card", "holo", "nm", "mint", "new", "the",
        "and", "for", "with", "japanese", "promo", "graded", "gem", "rare",
    )

    /**
     * Matchable card identifier from a listing title — "Character #Number" —
     * or "" if the title has no clean set/promo number, or doesn't mention
     * one of TOP_TIER_CHARACTERS. Embedding the character in the key (rather
     * than just the number) is required now that this spans multiple
     * characters: promo/set numbers collide across characters, so grouping
     * or querying by number alone would re-open exactly the false-positive
     * risk same_card() exists to prevent.
     */
    fun cardKey(title: String): String {
        val character = detectCharacter(title) ?: return ""
        val m = CARD_ID_RE.find(title) ?: return ""
        val number = m.groupValues[1]
        val setNumber = m.groupValues[2]
        val numPart = if (number.isNotEmpty()) "#$number" else setNumber
        return "$character $numPart"
    }

    private fun significantTokens(title: String): Set<String> {
        val cleaned = title.lowercase().replace(Regex("""[^\w\s]"""), " ")
        return cleaned.split(Regex("""\s+"""))
            .filter { it.isNotEmpty() }
            .filter { it !in NOISE_TOKENS && !it.all(Char::isDigit) && it.length > 2 }
            .toSet()
    }

    /**
     * True only if the two titles share >=2 identifying tokens beyond the bare
     * card number — a shared number alone is not sufficient evidence.
     */
    fun sameCard(buyTitle: String, psaTitle: String): Boolean {
        val overlap = significantTokens(buyTitle) intersect significantTokens(psaTitle)
        return overlap.size >= 2
    }

    /** cardKey() already returns "Character #Number" / "Character Set/Num" —
     * strip the "#" for a plain-text search query. */
    fun keyToQuery(key: String): String = key.replace("#", "")
}
