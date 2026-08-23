package dev.charanjeev.bahi.feature.csvimport

import dev.charanjeev.bahi.core.importer.ColumnMapping
import dev.charanjeev.bahi.core.importer.CsvImporter
import dev.charanjeev.bahi.core.importer.ImportPreview
import dev.charanjeev.bahi.core.importer.ImportResult

/**
 * Fakes-not-mocks (CLAUDE.md): behaviour is scripted per test via [previewResult]/
 * [previewForMapping]/[importResult] rather than asserting a call happened.
 */
class FakeCsvImporter : CsvImporter {
    var previewResult: ImportPreview? = null

    /** Keyed on the mapping passed in, so a test can script different re-derived previews per correction. */
    var previewForMapping: (ColumnMapping) -> ImportPreview = { requireNotNull(previewResult) }

    var importResult: ImportResult? = null

    val previewedMappings = mutableListOf<ColumnMapping>()

    override suspend fun preview(csv: String): ImportPreview = requireNotNull(previewResult) {
        "FakeCsvImporter.previewResult not set"
    }

    override suspend fun preview(csv: String, mapping: ColumnMapping): ImportPreview {
        previewedMappings += mapping
        return previewForMapping(mapping)
    }

    override suspend fun import(csv: String, mapping: ColumnMapping, accountId: String): ImportResult =
        requireNotNull(importResult) { "FakeCsvImporter.importResult not set" }
}
