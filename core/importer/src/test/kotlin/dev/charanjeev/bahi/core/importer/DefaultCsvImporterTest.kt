package dev.charanjeev.bahi.core.importer

import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.testing.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Before
import org.junit.Test

/**
 * Slice 6 is integration -- wiring tokenizeCsv/inferColumnMapping into the
 * CsvImporter contract -- so what's worth testing here isn't the parsing or
 * inference logic again (covered in their own test classes), it's the seams:
 * a file where some rows map and others don't, and whether duplicatesSkipped
 * reflects the repository's real, count-aware answer rather than something
 * this class derives on its own.
 */
class DefaultCsvImporterTest {

    private lateinit var repository: FakeTransactionRepository
    private lateinit var importer: DefaultCsvImporter

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        repository = FakeTransactionRepository()
        importer = DefaultCsvImporter(
            transactionRepository = repository,
            clock = FixedClock(Instant.fromEpochMilliseconds(0)),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Test
    fun `import maps every clean row and reports no failures`() = runTest {
        val csv = """
            Date,Description,Amount
            2026-01-05,Coffee Shop,-450.00
            2026-01-06,Salary,50000.00
        """.trimIndent()
        repository.importAllReturnValue = 2

        val result = importer.import(csv, singleAmountMapping(), accountId = "acct-1")

        assertThat(result.failedRows).isEmpty()
        assertThat(result.imported).hasSize(2)
        assertThat(result.duplicatesSkipped).isEqualTo(0)
        val coffee = result.imported[0]
        assertThat(coffee.date).isEqualTo(LocalDate(2026, 1, 5))
        assertThat(coffee.description).isEqualTo("Coffee Shop")
        assertThat(coffee.amount).isEqualTo(Money(-45000))
        assertThat(coffee.accountId).isEqualTo("acct-1")
        assertThat(coffee.currencyCode).isEqualTo("INR")
    }

    @Test
    fun `a row with too few columns and a row with an unparseable amount fail without blocking the rest of the file`() = runTest {
        val csv = """
            Date,Description,Amount
            2026-01-05,Coffee Shop,-450.00
            2026-01-06,Bad Row
            2026-01-07,Unparseable Amount,not-a-number
            2026-01-08,Salary,50000.00
        """.trimIndent()
        repository.importAllReturnValue = 2

        val result = importer.import(csv, singleAmountMapping(), accountId = "acct-1")

        assertThat(result.imported).hasSize(2)
        assertThat(result.imported.map { it.description }).containsExactly("Coffee Shop", "Salary")
        assertThat(result.failedRows).hasSize(2)
        // Row index is 0-based from the tokenizer; lineNumber is 1-based, so
        // "Bad Row" (the third physical line, tokenizer index 2) is line 3.
        assertThat(result.failedRows.map { it.lineNumber }).containsExactly(3, 4)
        assertThat(result.failedRows[0].reason).contains("columns")
        assertThat(result.failedRows[1].reason).contains("amount")
    }

    @Test
    fun `duplicatesSkipped is read from the repository's return value, not re-derived from the batch`() = runTest {
        // Five rows with entirely distinct content -- nothing about them
        // looks like a duplicate of anything else. If this class were
        // independently deriving duplicatesSkipped (e.g. by counting
        // matching hashes within the batch itself), it would compute 0
        // here. The fake's return value has no relationship to the batch's
        // content at all, so the only way to get 3 is to have actually used
        // it.
        val csv = """
            Date,Description,Amount
            2026-01-01,Coffee Shop,-450.00
            2026-01-02,Salary,50000.00
            2026-01-03,Ride Share,-320.50
            2026-01-04,Electricity Bill,-1800.00
            2026-01-05,Grocery Store,-1200.00
        """.trimIndent()
        repository.importAllReturnValue = 2

        val result = importer.import(csv, singleAmountMapping(), accountId = "acct-1")

        assertThat(result.imported).hasSize(5)
        assertThat(result.duplicatesSkipped).isEqualTo(3)
        assertThat(repository.lastImportedBatch).hasSize(5)
    }

    @Test
    fun `debit and credit columns resolve to correctly signed amounts`() = runTest {
        val csv = """
            Date,Description,Withdrawal,Deposit
            2026-01-05,Coffee Shop,450.00,
            2026-01-06,Salary,,50000.00
        """.trimIndent()
        val mapping = ColumnMapping(
            headerRowIndex = 0,
            firstDataRowIndex = 1,
            dateColumn = 0,
            dateFormat = "yyyy-MM-dd",
            descriptionColumn = 1,
            amountColumn = null,
            amountSign = null,
            signColumn = null,
            debitColumn = 2,
            creditColumn = 3,
        )
        repository.importAllReturnValue = 2

        val result = importer.import(csv, mapping, accountId = "acct-1")

        assertThat(result.failedRows).isEmpty()
        assertThat(result.imported[0].amount).isEqualTo(Money(-45000))
        assertThat(result.imported[1].amount).isEqualTo(Money(5000000))
    }

    @Test
    fun `a debit-credit row with both or neither column populated fails rather than guessing`() = runTest {
        val csv = """
            Date,Description,Withdrawal,Deposit
            2026-01-05,Both Populated,450.00,50.00
            2026-01-06,Neither Populated,,
            2026-01-07,Salary,,50000.00
        """.trimIndent()
        val mapping = ColumnMapping(
            headerRowIndex = 0,
            firstDataRowIndex = 1,
            dateColumn = 0,
            dateFormat = "yyyy-MM-dd",
            descriptionColumn = 1,
            amountColumn = null,
            amountSign = null,
            signColumn = null,
            debitColumn = 2,
            creditColumn = 3,
        )
        repository.importAllReturnValue = 1

        val result = importer.import(csv, mapping, accountId = "acct-1")

        assertThat(result.imported).hasSize(1)
        assertThat(result.imported.single().description).isEqualTo("Salary")
        assertThat(result.failedRows).hasSize(2)
    }

    @Test
    fun `a sign column resolves both magnitude and polarity, and an unrecognised marker fails that row`() = runTest {
        val csv = """
            Date,Description,Amount,Type
            2026-01-05,Coffee Shop,450.00,Dr
            2026-01-06,Salary,50000.00,Cr
            2026-01-07,Unknown Type,100.00,XX
        """.trimIndent()
        val mapping = ColumnMapping(
            headerRowIndex = 0,
            firstDataRowIndex = 1,
            dateColumn = 0,
            dateFormat = "yyyy-MM-dd",
            descriptionColumn = 1,
            amountColumn = 2,
            amountSign = AmountSign.SIGN_COLUMN,
            signColumn = 3,
            debitColumn = null,
            creditColumn = null,
        )
        repository.importAllReturnValue = 2

        val result = importer.import(csv, mapping, accountId = "acct-1")

        assertThat(result.imported.map { it.amount }).containsExactly(Money(-45000), Money(5000000))
        assertThat(result.failedRows).hasSize(1)
        assertThat(result.failedRows.single().reason).contains("amount")
    }

    @Test
    fun `preview maps every data row, not a capped sample, so a failure anywhere in the file is visible`() = runTest {
        // MAX_ROWS_TO_SAMPLE in ColumnInference.kt is 50 -- that cap is for
        // inference's own role-scoring cost, not for how many rows a
        // preview shows. 60 rows with one malformed row well past that
        // cutoff pins the distinction.
        val header = "Date,Description,Amount"
        val rows = (1..60).map { i ->
            val day = "%02d".format((i % 28) + 1)
            if (i == 55) "2026-01-$day,Malformed Row,not-a-number" else "2026-01-$day,Row $i,-${i * 10}.00"
        }
        val csv = (listOf(header) + rows).joinToString("\n")

        val preview = importer.preview(csv)

        assertThat(preview.sampleRows).hasSize(60)
        val malformed = preview.sampleRows[54]
        assertThat(malformed.date).isNull()
        assertThat(malformed.description).isNull()
        assertThat(malformed.amount).isNull()
        assertThat(malformed.rawCells).contains("Malformed Row")
        val clean = preview.sampleRows[0]
        assertThat(clean.description).isEqualTo("Row 1")
        assertThat(clean.amount).isNotNull()
    }

    @Test
    fun `preview with a supplied mapping re-maps rows without running inference again`() = runTest {
        val csv = """
            Date,Description,Withdrawal,Deposit
            2026-01-05,Coffee Shop,450.00,
            2026-01-06,Salary,,50000.00
        """.trimIndent()
        val mapping = ColumnMapping(
            headerRowIndex = 0,
            firstDataRowIndex = 1,
            dateColumn = 0,
            dateFormat = "yyyy-MM-dd",
            descriptionColumn = 1,
            amountColumn = null,
            amountSign = null,
            signColumn = null,
            debitColumn = 2,
            creditColumn = 3,
        )

        val preview = importer.preview(csv, mapping)

        assertThat(preview.mapping).isEqualTo(mapping)
        assertThat(preview.uncertainFields).isEmpty()
        assertThat(preview.sampleRows).hasSize(2)
        assertThat(preview.sampleRows[0].amount).isEqualTo(Money(-45000))
        assertThat(preview.sampleRows[1].amount).isEqualTo(Money(5000000))
    }

    @Test
    fun `comparing two candidate date formats via preview distinguishes silent ambiguity from a real contradiction`() = runTest {
        // Every value here is silent on the question (both components <= 12),
        // so both candidate formats should map every row cleanly -- this is
        // the case where either choice is safe.
        val silentCsv = """
            Date,Description,Amount
            03/04/2026,Coffee Shop,-450.00
            05/06/2026,Salary,50000.00
        """.trimIndent()
        val dayFirst = candidateMapping(dateFormat = "dd/MM/yyyy")
        val monthFirst = candidateMapping(dateFormat = "MM/dd/yyyy")

        val silentDayFirst = importer.preview(silentCsv, dayFirst)
        val silentMonthFirst = importer.preview(silentCsv, monthFirst)
        assertThat(silentDayFirst.sampleRows.count { it.date == null }).isEqualTo(0)
        assertThat(silentMonthFirst.sampleRows.count { it.date == null }).isEqualTo(0)

        // 13/04/2026 is only valid day-first; 04/13/2026 is only valid
        // month-first -- no single format parses both, which the picker
        // needs to see as a non-zero failure count on *both* candidates,
        // not present it as if either choice cleanly resolves everything.
        val contradictoryCsv = """
            Date,Description,Amount
            13/04/2026,Coffee Shop,-450.00
            04/13/2026,Salary,50000.00
        """.trimIndent()
        val contradictoryDayFirst = importer.preview(contradictoryCsv, dayFirst)
        val contradictoryMonthFirst = importer.preview(contradictoryCsv, monthFirst)
        assertThat(contradictoryDayFirst.sampleRows.count { it.date == null }).isEqualTo(1)
        assertThat(contradictoryMonthFirst.sampleRows.count { it.date == null }).isEqualTo(1)
    }

    @Test
    fun `total inference failure falls back to raw cells for every row instead of a mapping`() = runTest {
        val csv = """
            This is not a bank statement.
            Just some random text.
            Nothing here looks like a transaction at all.
        """.trimIndent()

        val preview = importer.preview(csv)

        assertThat(preview.mapping).isNull()
        assertThat(preview.sampleRows).hasSize(3)
        assertThat(preview.sampleRows.all { it.date == null && it.description == null && it.amount == null }).isTrue()
        assertThat(preview.sampleRows[0].rawCells).containsExactly("This is not a bank statement.")
    }

    private fun singleAmountMapping() = ColumnMapping(
        headerRowIndex = 0,
        firstDataRowIndex = 1,
        dateColumn = 0,
        dateFormat = "yyyy-MM-dd",
        descriptionColumn = 1,
        amountColumn = 2,
        amountSign = AmountSign.NEGATIVE_IS_DEBIT,
        signColumn = null,
        debitColumn = null,
        creditColumn = null,
    )

    private fun candidateMapping(dateFormat: String) = singleAmountMapping().copy(dateFormat = dateFormat)
}
