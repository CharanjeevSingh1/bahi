package dev.charanjeev.bahi.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpendTrendTest {

    private val august = YearMonth.of(2026, 8)
    private val july = YearMonth.of(2026, 7)

    @Test
    fun `a single month has no comparison`() {
        assertThat(SpendTrend(listOf(MonthlyTotal(august, Money.ZERO))).hasComparison).isFalse()
    }

    @Test
    fun `two months is enough for a comparison`() {
        val trend = SpendTrend(listOf(MonthlyTotal(july, Money(100_000)), MonthlyTotal(august, Money(200_000))))
        assertThat(trend.hasComparison).isTrue()
    }

    @Test
    fun `an empty month list has no comparison`() {
        assertThat(SpendTrend(emptyList()).hasComparison).isFalse()
    }
}
