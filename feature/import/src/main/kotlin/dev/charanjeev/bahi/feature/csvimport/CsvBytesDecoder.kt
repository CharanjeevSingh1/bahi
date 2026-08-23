package dev.charanjeev.bahi.feature.csvimport

/**
 * docs/csv-import-design.md §7: a CSV row runs roughly 80-150 bytes, so 5 MB
 * is one to two orders of magnitude past any real personal statement, while
 * a worst-case 5 MB String is trivial for any device this app targets.
 */
internal const val MAX_CSV_BYTES = 5 * 1024 * 1024

/** How much of the file to inspect before running the tokenizer at all. */
private const val SNIFF_WINDOW_BYTES = 8 * 1024

/**
 * Pure over [ByteArray] so the cap/sniff/encoding logic is unit-testable
 * without a real ContentResolver -- [CsvFileReader] is the thin Uri-touching
 * wrapper that gets bytes here in the first place.
 */
internal sealed interface CsvDecodeResult {
    data class Success(val csv: String) : CsvDecodeResult
    data object TooLarge : CsvDecodeResult
    data object NotText : CsvDecodeResult
    data object EncodingUnclear : CsvDecodeResult
}

/**
 * §7's three checks, in order: size (before decode), content (a binary file
 * picked by mistake), then encoding (a non-UTF-8 export). [bytes] having more
 * than [MAX_CSV_BYTES] elements is itself the over-cap signal -- see
 * [CsvFileReader] for why the read that produces [bytes] is bounded rather
 * than trusting a provider-reported length.
 */
internal fun decodeCsvBytes(bytes: ByteArray): CsvDecodeResult {
    if (bytes.size > MAX_CSV_BYTES) return CsvDecodeResult.TooLarge

    val sniffWindow = bytes.copyOfRange(0, minOf(bytes.size, SNIFF_WINDOW_BYTES))
    if (sniffWindow.any { it == 0.toByte() }) return CsvDecodeResult.NotText

    // Windows-1252 mis-decoded as UTF-8 substitutes U+FFFD per malformed
    // byte -- a heuristic, not a guarantee (§7: it catches an isolated
    // stray byte, not two adjacent non-ASCII bytes that happen to form a
    // valid-but-wrong UTF-8 sequence), but it's what stops the common case
    // from silently corrupting merchant names instead of failing loudly.
    val text = bytes.toString(Charsets.UTF_8)
    if (text.contains('\uFFFD')) return CsvDecodeResult.EncodingUnclear

    return CsvDecodeResult.Success(text)
}
