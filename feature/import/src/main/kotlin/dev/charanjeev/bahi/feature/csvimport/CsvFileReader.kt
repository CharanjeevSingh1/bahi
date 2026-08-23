package dev.charanjeev.bahi.feature.csvimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import javax.inject.Inject

/** Result of getting a picked document's bytes off its content Uri and through [decodeCsvBytes]. */
sealed interface CsvFileReadResult {
    data class Success(val fileName: String, val csv: String) : CsvFileReadResult
    data object TooLarge : CsvFileReadResult
    data object NotText : CsvFileReadResult
    data object EncodingUnclear : CsvFileReadResult
    data object ReadFailed : CsvFileReadResult
}

/**
 * Interface takes the picked document's Uri as its string form, not
 * [android.net.Uri] itself -- AGP's mockable android.jar nulls out
 * `Uri.EMPTY`/`Uri.parse(...)` for plain JVM unit tests, and `Uri`'s own
 * constructor is package-private so test code can't hand-roll an instance
 * either. A String round-trips through [Uri.parse]/[Uri.toString] losslessly
 * and needs nothing more than a literal to fake, which is what lets
 * ImportViewModel's tests exercise onFilePicked without Robolectric.
 */
interface CsvFileReader {
    suspend fun read(uriString: String): CsvFileReadResult
}

/**
 * docs/csv-import-design.md §7: pick-then-read-immediately, no persisted URI
 * permission -- there's nothing here that reopens the Uri later, so
 * takePersistableUriPermission would only leave a grant with nothing to ever
 * release it. The stream is read once, inside this one call.
 */
class AndroidCsvFileReader @Inject constructor(
    // @param: pins the qualifier to the constructor parameter, which is what Hilt
    // reads. Kotlin 2.2 warns that the default target is changing in a future
    // release; being explicit keeps injection working either way.
    @param:ApplicationContext private val context: Context,
) : CsvFileReader {
    override suspend fun read(uriString: String): CsvFileReadResult {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver

        // Some providers report a usable length; where they don't (-1 is
        // common), the read below is bounded defensively instead of trusting
        // this. Either way this is a cheap way to reject an obviously-too-
        // large file before opening a stream at all.
        val declaredLength = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declaredLength != null && declaredLength > MAX_CSV_BYTES) return CsvFileReadResult.TooLarge

        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readAtMostBytes(MAX_CSV_BYTES + 1) }
        }.getOrNull() ?: return CsvFileReadResult.ReadFailed

        if (bytes.size > MAX_CSV_BYTES) return CsvFileReadResult.TooLarge

        val fileName = displayNameOf(resolver, uri)
        return when (val decoded = decodeCsvBytes(bytes)) {
            is CsvDecodeResult.Success -> CsvFileReadResult.Success(fileName, decoded.csv)
            CsvDecodeResult.TooLarge -> CsvFileReadResult.TooLarge
            CsvDecodeResult.NotText -> CsvFileReadResult.NotText
            CsvDecodeResult.EncodingUnclear -> CsvFileReadResult.EncodingUnclear
        }
    }

    /** Best-effort: a picker not reporting a display name isn't a reason to fail the whole read. */
    private fun displayNameOf(resolver: android.content.ContentResolver, uri: Uri): String {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: "Selected file"
    }
}

/**
 * [InputStream.readBytes] has no size limit -- reading an unexpectedly huge
 * file into memory is exactly the risk §7's cap exists to prevent, so the
 * read itself has to stop at [limit] rather than trust the declared length
 * and read everything anyway.
 */
private fun InputStream.readAtMostBytes(limit: Int): ByteArray {
    val buffer = ByteArray(8192)
    val output = java.io.ByteArrayOutputStream()
    var total = 0
    while (total <= limit) {
        val read = read(buffer)
        if (read == -1) break
        output.write(buffer, 0, read)
        total += read
        if (total > limit) break
    }
    return output.toByteArray()
}
