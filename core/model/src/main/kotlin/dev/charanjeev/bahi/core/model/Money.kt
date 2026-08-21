package dev.charanjeev.bahi.core.model

import kotlin.math.abs

/**
 * Money is stored as integer minor units (paise, cents) -- never Double.
 * Floating point rounding errors in a finance app are a correctness bug, and
 * this type makes them unrepresentable rather than merely unlikely.
 */
@JvmInline
value class Money(val minorUnits: Long) : Comparable<Money> {

    operator fun plus(other: Money) = Money(minorUnits + other.minorUnits)
    operator fun minus(other: Money) = Money(minorUnits - other.minorUnits)
    operator fun unaryMinus() = Money(-minorUnits)

    val isNegative: Boolean get() = minorUnits < 0
    val absolute: Money get() = Money(abs(minorUnits))

    override fun compareTo(other: Money): Int = minorUnits.compareTo(other.minorUnits)

    companion object {
        val ZERO = Money(0)

        /**
         * Parses "1,234.56", "(1234.56)", "-1234.56", "₹1 234,56" and friends.
         * Bank CSV exports are inconsistent enough that this needs to be one
         * well-tested function rather than a regex at each call site.
         */
        fun parse(raw: String, decimalPlaces: Int = 2): Money? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val parenthesised = trimmed.startsWith("(") && trimmed.endsWith(")")
            var cleaned = trimmed.removeSurrounding("(", ")")

            val explicitlyNegative = cleaned.startsWith("-")
            cleaned = cleaned.removePrefix("-").removePrefix("+")

            // Strip currency symbols, spaces and non-breaking spaces.
            cleaned = cleaned.filter { it.isDigit() || it == '.' || it == ',' }
            if (cleaned.isEmpty()) return null

            // Whichever separator appears last is the decimal separator.
            val lastDot = cleaned.lastIndexOf('.')
            val lastComma = cleaned.lastIndexOf(',')
            val decimalSeparator = when {
                lastDot > lastComma -> '.'
                lastComma > lastDot -> ','
                else -> null
            }

            val normalised = when (decimalSeparator) {
                null -> cleaned.filter { it.isDigit() }
                else -> {
                    val index = cleaned.lastIndexOf(decimalSeparator)
                    val whole = cleaned.substring(0, index).filter { it.isDigit() }
                    val fraction = cleaned.substring(index + 1).filter { it.isDigit() }
                    // A trailing group of exactly 3 digits is a thousands group,
                    // not a fraction: "1,234" is 1234.00, not 1.234
                    if (fraction.length == 3) whole + fraction else "$whole.$fraction"
                }
            }

            if (normalised.isEmpty()) return null

            val parts = normalised.split('.')
            val whole = parts[0].ifEmpty { "0" }
            val fraction = parts.getOrElse(1) { "" }
                .padEnd(decimalPlaces, '0')
                .take(decimalPlaces)

            val magnitude = ("$whole$fraction").toLongOrNull() ?: return null
            val negative = explicitlyNegative || parenthesised
            return Money(if (negative) -magnitude else magnitude)
        }
    }
}
