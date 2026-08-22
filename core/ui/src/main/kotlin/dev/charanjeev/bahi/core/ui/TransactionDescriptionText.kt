package dev.charanjeev.bahi.core.ui

/**
 * Bank statement text arrives SHOUTING; the stored description has to stay
 * byte-identical to it (import de-duplication hashes it), so this only ever
 * runs at render time, never on write.
 */
private val KNOWN_ACRONYMS = setOf("ATM", "NEFT", "UPI", "EMI")

/**
 * Title-cases a raw, all-caps bank description for display -- "BLUE TOKAI
 * COFFEE" becomes "Blue Tokai Coffee" -- while leaving [KNOWN_ACRONYMS]
 * upper and anything that isn't already all-caps untouched, since mixed-case
 * input (a manually entered "Uber Eats") is already fine as authored.
 */
fun titleCaseTransactionDescription(raw: String): String {
    val letters = raw.filter { it.isLetter() }
    if (letters.isEmpty() || letters.any { it.isLowerCase() }) return raw

    return raw.split(" ").joinToString(" ") { word ->
        if (word in KNOWN_ACRONYMS) word else word.lowercase().replaceFirstChar { it.uppercaseChar() }
    }
}
