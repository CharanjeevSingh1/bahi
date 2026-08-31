package dev.charanjeev.bahi.core.database.dao

import androidx.room.ColumnInfo

/** One row of [TransactionDao.observeCategorySpend]: a live category and its expense total for the month. */
data class CategorySpendRow(
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "spent_minor") val spentMinor: Long,
)

/** One row of [TransactionDao.observeMonthlySpend]: a calendar month and its expense total. */
data class MonthlySpendRow(
    @ColumnInfo(name = "year_month") val yearMonth: String,
    @ColumnInfo(name = "spent_minor") val spentMinor: Long,
)
