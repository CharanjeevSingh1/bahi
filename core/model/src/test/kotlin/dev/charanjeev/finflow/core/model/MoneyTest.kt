package dev.charanjeev.finflow.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * These cases are taken from real bank CSV exports. Each one is a format that
 * broke a naive `toDouble()` parse.
 */
class MoneyTest {

    @Test
    fun `parses plain decimal`() {
        assertThat(Money.parse("1234.56")).isEqualTo(Money(123456))
    }

    @Test
    fun `parses thousands separators`() {
        assertThat(Money.parse("1,234.56")).isEqualTo(Money(123456))
        assertThat(Money.parse("12,34,567.89")).isEqualTo(Money(123456789)) // Indian grouping
    }

    @Test
    fun `treats parentheses as negative`() {
        assertThat(Money.parse("(1,234.56)")).isEqualTo(Money(-123456))
    }

    @Test
    fun `parses european separators`() {
        assertThat(Money.parse("1.234,56")).isEqualTo(Money(123456))
    }

    @Test
    fun `strips currency symbols`() {
        assertThat(Money.parse("₹ 1,234.56")).isEqualTo(Money(123456))
        assertThat(Money.parse("$1234.56")).isEqualTo(Money(123456))
    }

    @Test
    fun `treats trailing three digit group as thousands not fraction`() {
        assertThat(Money.parse("1,234")).isEqualTo(Money(123400))
    }

    @Test
    fun `pads short fractions`() {
        assertThat(Money.parse("12.5")).isEqualTo(Money(1250))
    }

    @Test
    fun `returns null for unparseable input`() {
        assertThat(Money.parse("")).isNull()
        assertThat(Money.parse("N/A")).isNull()
    }

    @Test
    fun `arithmetic has no floating point drift`() {
        val tenth = Money(10)
        val sum = (1..10).fold(Money.ZERO) { acc, _ -> acc + tenth }
        assertThat(sum).isEqualTo(Money(100))
    }
}
