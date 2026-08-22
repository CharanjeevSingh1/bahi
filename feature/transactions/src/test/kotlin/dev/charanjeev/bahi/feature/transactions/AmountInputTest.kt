package dev.charanjeev.bahi.feature.transactions

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Money
import org.junit.Test

class AmountInputTest {

    @Test
    fun `sanitize keeps digits and a single decimal point`() {
        assertThat(sanitizeAmountInput("1234")).isEqualTo("1234")
        assertThat(sanitizeAmountInput("12.34")).isEqualTo("12.34")
        assertThat(sanitizeAmountInput("12.3.4")).isEqualTo("12.34")
    }

    @Test
    fun `sanitize drops thousands separators instead of interpreting them`() {
        // "1,2" typed by hand isn't yet "1.20" or "1,200" -- dropping the
        // separator sidesteps guessing, unlike Money.parse's CSV heuristic.
        assertThat(sanitizeAmountInput("1,2")).isEqualTo("12")
        assertThat(sanitizeAmountInput("12,345")).isEqualTo("12345")
    }

    @Test
    fun `sanitize strips a pasted currency symbol and grouping`() {
        assertThat(sanitizeAmountInput("₹1,234.56")).isEqualTo("1234.56")
    }

    @Test
    fun `pasting into a non-empty field sanitizes the full resulting string, not just the paste`() {
        // Compose delivers a paste as one onValueChange with the whole new
        // value -- "45" already in the field, pasting "₹1,234.56" at the end
        // arrives here as "45₹1,234.56" before filtering, not the pasted
        // text alone. This is the case an "optimized" filter could break.
        assertThat(sanitizeAmountInput("45₹1,234.56")).isEqualTo("451234.56")
    }

    @Test
    fun `sanitize never produces a minus sign -- sign comes from the type toggle, not the keyboard`() {
        assertThat(sanitizeAmountInput("-12.5")).isEqualTo("12.5")
    }

    @Test
    fun `parses a sanitized magnitude`() {
        assertThat(parseAmountMagnitude("12.5", decimalPlaces = 2)).isEqualTo(Money(1250))
        assertThat(parseAmountMagnitude("1234", decimalPlaces = 2)).isEqualTo(Money(123400))
    }

    @Test
    fun `a lone decimal point has no digits and does not parse as zero`() {
        assertThat(parseAmountMagnitude(".", decimalPlaces = 2)).isNull()
    }

    @Test
    fun `empty input does not parse`() {
        assertThat(parseAmountMagnitude("", decimalPlaces = 2)).isNull()
    }

    @Test
    fun `formats a magnitude back to plain decimal text for editing`() {
        assertThat(formatAmountForEditing(Money(123456), decimalPlaces = 2)).isEqualTo("1234.56")
        assertThat(formatAmountForEditing(Money(1200), decimalPlaces = 2)).isEqualTo("12.00")
    }

    @Test
    fun `formats with zero decimal places for a currency like JPY`() {
        assertThat(formatAmountForEditing(Money(1234), decimalPlaces = 0)).isEqualTo("1234")
    }

    @Test
    fun `decimal places come from the currency, not a hardcoded default`() {
        assertThat(decimalPlacesFor("INR")).isEqualTo(2)
        assertThat(decimalPlacesFor("JPY")).isEqualTo(0)
        assertThat(decimalPlacesFor("KWD")).isEqualTo(3)
    }
}
