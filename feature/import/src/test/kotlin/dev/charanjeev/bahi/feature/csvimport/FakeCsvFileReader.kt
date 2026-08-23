package dev.charanjeev.bahi.feature.csvimport

/** Fakes-not-mocks: the result is scripted directly rather than mocking ContentResolver plumbing. */
class FakeCsvFileReader : CsvFileReader {
    var result: CsvFileReadResult = CsvFileReadResult.ReadFailed

    override suspend fun read(uriString: String): CsvFileReadResult = result
}
