package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.model.Money
import java.util.Currency

/**
 * Keeps digits and at most one decimal point, dropping everything else --
 * thousands separators, currency symbols, whitespace, a second '.'. Applied
 * identically to typed and pasted input, since Compose delivers both through
 * the same onValueChange callback carrying the full resulting string.
 *
 * Deliberately doesn't treat ',' as a possible decimal separator the way
 * Money.parse does for CSV cells: that heuristic resolves which separator a
 * *complete* string used, which is the wrong question for a field built up
 * one keystroke at a time -- "1,2" typed by hand isn't yet "1.20" or
 * "1,200". Grouping separators are dropped instead of guessed at.
 */
internal fun sanitizeAmountInput(raw: String): String {
    val sanitized = StringBuilder(raw.length)
    var sawDecimalPoint = false
    for (char in raw) {
        when {
            char.isDigit() -> sanitized.append(char)
            char == '.' && !sawDecimalPoint -> {
                sanitized.append(char)
                sawDecimalPoint = true
            }
        }
    }
    return sanitized.toString()
}

/**
 * The magnitude a sanitized amount string represents, or null if it has no
 * digits at all. A lone "." sanitizes to a non-empty string but has nothing
 * to parse -- Money.parse(".") alone returns Money(0), which would silently
 * turn that typo into a real zero-value transaction, so the digit check
 * guards it rather than trusting Money.parse's own emptiness check.
 */
internal fun parseAmountMagnitude(sanitized: String, decimalPlaces: Int): Money? {
    if (sanitized.none { it.isDigit() }) return null
    return Money.parse(sanitized, decimalPlaces)
}

/** Plain decimal text, no grouping or currency symbol -- what the field shows once the user leaves it. */
internal fun formatAmountForEditing(magnitude: Money, decimalPlaces: Int): String {
    val divisor = generateSequence(1L) { it * 10 }.elementAt(decimalPlaces)
    val whole = magnitude.minorUnits / divisor
    if (decimalPlaces == 0) return whole.toString()
    val fraction = (magnitude.minorUnits % divisor).toString().padStart(decimalPlaces, '0')
    return "$whole.$fraction"
}

/** JPY has 0, KWD has 3 -- reading this off Currency rather than hardcoding 2 is what makes it correct for either. */
internal fun decimalPlacesFor(currencyCode: String): Int =
    Currency.getInstance(currencyCode).defaultFractionDigits
