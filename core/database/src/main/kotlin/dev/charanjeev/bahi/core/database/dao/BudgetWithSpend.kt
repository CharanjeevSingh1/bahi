package dev.charanjeev.bahi.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import dev.charanjeev.bahi.core.database.entity.BudgetEntity

/**
 * One row of [BudgetDao.observeBudgetsWithSpend]: a budget together with the
 * total spent against it, summed by SQLite rather than by folding over
 * transactions in Kotlin (docs/budgets-design.md §4.2).
 *
 * [spentMinor] is positive -- the query negates `amount_minor`, which is
 * negative for expenses.
 */
data class BudgetWithSpend(
    @Embedded val budget: BudgetEntity,
    @ColumnInfo(name = "spent_minor") val spentMinor: Long,
)
