package dev.charanjeev.bahi.core.ui

/**
 * Bank statement text arrives SHOUTING; the stored description has to stay
 * byte-identical to it (import de-duplication hashes it), so this only ever
 * runs at render time, never on write.
 */
private val KNOWN_ACRONYMS = setOf("ATM", "NEFT", "UPI", "EMI")

private const val MAX_HEURISTIC_ACRONYM_LENGTH = 5
private val VOWELS = "AEIOU".toSet()

/**
 * A word is treated as an acronym worth preserving if it's in
 * [KNOWN_ACRONYMS], or if it's short and vowel-less -- initialisms like
 * "PVR", "HDFC", "BSNL" and "TCS" are consonant clusters precisely because
 * they're read letter-by-letter, whereas English words that short almost
 * always carry a vowel ("RENT", "GYM" -- "GYM" is the one common exception
 * this rule mis-title-cases to "Gym").
 *
 * The rule is deliberately imperfect in the other direction too: any
 * initialism that happens to contain a vowel reads as an ordinary word --
 * "IRCTC" survives (no vowel), but "HDFC BANK"'s sibling "SBI" or "OYO"
 * would get title-cased since they contain a vowel. Extend [KNOWN_ACRONYMS]
 * for those as they're spotted; this heuristic only covers the common case.
 */
private fun isAcronym(word: String): Boolean {
    if (word in KNOWN_ACRONYMS) return true
    val letters = word.filter { it.isLetter() }
    return letters.length in 1..MAX_HEURISTIC_ACRONYM_LENGTH && letters.none { it in VOWELS }
}

/**
 * Title-cases a raw, all-caps bank description for display -- "BLUE TOKAI
 * COFFEE" becomes "Blue Tokai Coffee" -- while leaving acronyms (see
 * [isAcronym]) upper and anything that isn't already all-caps untouched,
 * since mixed-case input (a manually entered "Uber Eats") is already fine
 * as authored.
 */
fun titleCaseTransactionDescription(raw: String): String {
    val letters = raw.filter { it.isLetter() }
    if (letters.isEmpty() || letters.any { it.isLowerCase() }) return raw

    return raw.split(" ").joinToString(" ") { word ->
        if (isAcronym(word)) word else word.lowercase().replaceFirstChar { it.uppercaseChar() }
    }
}
