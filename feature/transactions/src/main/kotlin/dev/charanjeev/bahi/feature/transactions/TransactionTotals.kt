package dev.charanjeev.bahi.feature.transactions

import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.model.Transaction
import kotlinx.datetime.LocalDate

/**
 * Net (income minus expenses) across a single calendar month, not every
 * transaction ever loaded -- an all-time net is dominated by whichever
 * paycheck happens to be in the list and reads as a bug more than a number
 * worth trusting. [month] only matters for its year/month; the day is
 * ignored, so callers can pass "today".
 */
fun netTotalForMonth(transactions: List<Transaction>, month: LocalDate): Money =
    transactions
        .filter { it.date.year == month.year && it.date.monthNumber == month.monthNumber }
        .fold(Money.ZERO) { total, transaction -> total + transaction.amount }
