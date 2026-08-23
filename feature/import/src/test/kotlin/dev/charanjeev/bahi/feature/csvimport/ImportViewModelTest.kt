package dev.charanjeev.bahi.feature.csvimport

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.charanjeev.bahi.core.importer.AmountSign
import dev.charanjeev.bahi.core.importer.ColumnMapping
import dev.charanjeev.bahi.core.importer.FailedRow
import dev.charanjeev.bahi.core.importer.ImportPreview
import dev.charanjeev.bahi.core.importer.ImportResult
import dev.charanjeev.bahi.core.importer.MappingField
import dev.charanjeev.bahi.core.importer.PreviewRow
import dev.charanjeev.bahi.core.model.Money
import dev.charanjeev.bahi.core.testing.MainDispatcherRule
import dev.charanjeev.bahi.core.testing.TestData
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test

/** A plain string, not android.net.Uri -- see CsvFileReader's own doc on why. */
private const val fakeUri = "content://fake"

class ImportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val csvImporter = FakeCsvImporter()
    private val csvFileReader = FakeCsvFileReader()
    private val viewModel = ImportViewModel(csvImporter, csvFileReader)

    private val mapping = ColumnMapping(
        headerRowIndex = 0,
        firstDataRowIndex = 1,
        dateColumn = 0,
        dateFormat = "dd/MM/yyyy",
        descriptionColumn = 1,
        amountColumn = 2,
        amountSign = AmountSign.NEGATIVE_IS_DEBIT,
        signColumn = null,
        debitColumn = null,
        creditColumn = null,
    )

    private fun previewWith(
        uncertainFields: Set<MappingField> = emptySet(),
        unmappedColumns: List<Int> = emptyList(),
        headerCells: List<String>? = listOf("Date", "Description", "Amount"),
    ) = ImportPreview(
        mapping = mapping,
        uncertainFields = uncertainFields,
        sampleRows = listOf(
            PreviewRow(rawCells = listOf("01/03/2026", "COFFEE", "-450.00"), date = LocalDate(2026, 3, 1), description = "COFFEE", amount = Money(-45000)),
        ),
        unmappedColumns = unmappedColumns,
        headerCells = headerCells,
        warnings = emptyList(),
    )

    @Test
    fun `picking a file moves through Reading into Preview on success`() = runTest {
        csvFileReader.result = CsvFileReadResult.Success("statement.csv", "raw csv")
        csvImporter.previewResult = previewWith()

        viewModel.uiState.test {
            assertThat(awaitItem()).isEqualTo(ImportUiState.Idle)

            viewModel.onFilePicked(fakeUri)

            assertThat(awaitItem()).isEqualTo(ImportUiState.Reading)
            val state = awaitItem() as ImportUiState.Preview
            assertThat(state.fileName).isEqualTo("statement.csv")
            assertThat(state.preview.mapping).isEqualTo(mapping)
        }
    }

    @Test
    fun `too large file read surfaces the TOO_LARGE failure reason, not a generic one`() = runTest {
        csvFileReader.result = CsvFileReadResult.TooLarge

        viewModel.uiState.test {
            skipItems(1) // Idle
            viewModel.onFilePicked(fakeUri)
            skipItems(1) // Reading
            assertThat(awaitItem()).isEqualTo(ImportUiState.Failed(FailureReason.TOO_LARGE))
        }
    }

    @Test
    fun `encoding failure surfaces the ENCODING reason distinctly from NOT_TEXT`() = runTest {
        csvFileReader.result = CsvFileReadResult.EncodingUnclear

        viewModel.uiState.test {
            skipItems(1)
            viewModel.onFilePicked(fakeUri)
            skipItems(1)
            assertThat(awaitItem()).isEqualTo(ImportUiState.Failed(FailureReason.ENCODING))
        }
    }

    @Test
    fun `confirming import reports newCount as imported minus duplicates, not the raw imported size`() = runTest {
        csvFileReader.result = CsvFileReadResult.Success("statement.csv", "raw csv")
        csvImporter.previewResult = previewWith()
        // 5 mapped rows, 2 of which were duplicates -- ImportResult.imported holds all 5 (see its own doc),
        // so a naive "imported.size" reading would claim 5 new when only 3 actually were.
        csvImporter.importResult = ImportResult(
            imported = List(5) { TestData.transaction(id = "txn-$it") },
            duplicatesSkipped = 2,
            failedRows = listOf(FailedRow(lineNumber = 9, raw = "bad,row", reason = "unparseable amount")),
        )

        viewModel.uiState.test {
            skipItems(1) // Idle
            viewModel.onFilePicked(fakeUri)
            skipItems(2) // Reading, Preview

            viewModel.onImportConfirmed()

            assertThat(awaitItem()).isEqualTo(ImportUiState.Importing)
            val result = awaitItem() as ImportUiState.Result
            assertThat(result.newCount).isEqualTo(3)
            assertThat(result.duplicatesSkipped).isEqualTo(2)
            assertThat(result.failedRows).hasSize(1)
        }
    }

    @Test
    fun `date format correction offers two candidates with independent failure counts`() = runTest {
        csvFileReader.result = CsvFileReadResult.Success("statement.csv", "raw csv")
        val initialPreview = previewWith(uncertainFields = setOf(MappingField.DATE_FORMAT))
        csvImporter.previewResult = initialPreview
        // Contradictory column (§2): day-first fails on 1 row, month-first fails on a different row --
        // neither option is a clean answer, which is exactly what the sheet needs to show.
        csvImporter.previewForMapping = { candidate ->
            when {
                candidate.dateFormat == "dd/MM/yyyy" -> initialPreview.copy(sampleRows = listOf(sampleRowWithDate(null)))
                candidate.dateFormat == "MM/dd/yyyy" -> initialPreview.copy(sampleRows = listOf(sampleRowWithDate(LocalDate(2026, 4, 3))))
                else -> initialPreview
            }
        }

        viewModel.uiState.test {
            skipItems(1) // Idle
            viewModel.onFilePicked(fakeUri)
            skipItems(2) // Reading, Preview

            viewModel.onCorrectionRequested(MappingField.DATE_FORMAT)

            val state = awaitItem() as ImportUiState.Preview
            val target = state.activeCorrection as CorrectionTarget.DateFormat
            assertThat(target.optionA.dateFormat).isEqualTo("dd/MM/yyyy")
            assertThat(target.optionA.failingRowCount).isEqualTo(1)
            assertThat(target.optionB.dateFormat).isEqualTo("MM/dd/yyyy")
            assertThat(target.optionB.failingRowCount).isEqualTo(0)
        }
    }

    @Test
    fun `unmapped columns survive into the preview state for the UI to show`() = runTest {
        csvFileReader.result = CsvFileReadResult.Success("statement.csv", "raw csv")
        csvImporter.previewResult = previewWith(unmappedColumns = listOf(3))

        viewModel.uiState.test {
            skipItems(1)
            viewModel.onFilePicked(fakeUri)
            skipItems(1)
            val state = awaitItem() as ImportUiState.Preview
            assertThat(state.preview.unmappedColumns).containsExactly(3)
        }
    }

    @Test
    fun `onDone emits a Finished event for the route to navigate back on`() = runTest {
        viewModel.eventFlow.test {
            viewModel.onDone()
            assertThat(awaitItem()).isEqualTo(ImportEvent.Finished)
        }
    }

    private fun sampleRowWithDate(date: LocalDate?) =
        PreviewRow(rawCells = listOf("03/04/2026", "COFFEE", "-450.00"), date = date, description = "COFFEE", amount = Money(-45000))
}
