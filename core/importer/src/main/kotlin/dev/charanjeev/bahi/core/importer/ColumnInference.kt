package dev.charanjeev.bahi.core.importer

import dev.charanjeev.bahi.core.model.Money

/** Bank exports don't change format mid-file; this bounds preview latency on a huge one. */
private const val MAX_ROWS_TO_SAMPLE = 50

/** How much of a column's sampled cells must fit a role before it's trusted, not flagged uncertain. */
private const val ROLE_HIT_RATE_THRESHOLD = 0.8

/**
 * Below this many *checkable* consecutive pairs (§2), there isn't enough
 * evidence to call a column a balance either way -- not enough to confirm it,
 * and not enough to confidently rule it out either.
 */
private const val MIN_CHECKABLE_BALANCE_PAIRS = 3

/**
 * A real balance column won't match every single row -- a dropped or
 * malformed row, or a same-day transaction netted into one displayed step,
 * breaks the exact relation without meaning the column isn't a balance.
 * This is a floor on the match rate among checkable pairs, not a tolerance
 * on any individual comparison, which is exact integer equality (see
 * [balanceDelta]).
 */
private const val BALANCE_MATCH_RATE_THRESHOLD = 0.75

private val DESCRIPTION_HEADER_HINTS = listOf("narration", "particulars", "description", "details")
private val DEBIT_HEADER_HINTS = listOf("debit", "withdrawal", "dr")
private val CREDIT_HEADER_HINTS = listOf("credit", "deposit", "cr")

/**
 * The result of slice 3's inference pass: which column plays which role, and
 * which of those role assignments are confirmed versus a provisional best
 * guess. This is deliberately narrower than [ImportPreview] -- no sample rows,
 * no warnings text -- because assembling those from a [ColumnMapping] is
 * preview-rendering, not inference, and belongs with the [CsvImporter]
 * implementation that calls this.
 */
internal data class InferredMapping(
    val mapping: ColumnMapping?,
    val uncertainFields: Set<MappingField>,
    val unmappedColumns: List<Int>,
)

/**
 * Finds the data rows, the header (if any), and which column plays which
 * role, per the pipeline in docs/csv-import-design.md §2. Every decision here
 * is either backed by a clear majority signal or explicitly marked uncertain
 * in the result -- there is no case where this silently picks a guess and
 * presents it as confirmed. Date *format* disambiguation (day-first vs
 * month-first, §2) is deliberately not attempted here; that's a distinct,
 * separately-reviewed decision. A date column found here that turns out to
 * be format-ambiguous always comes back with [MappingField.DATE_FORMAT]
 * uncertain, using an arbitrary placeholder format string.
 */
internal fun inferColumnMapping(rows: List<CsvRow>): InferredMapping {
    val modalColumnCount = rows.groupingBy { it.cells.size }.eachCount()
        .maxByOrNull { it.value }?.key
        ?: return InferredMapping(null, emptySet(), emptyList())

    // Fewer than 3 columns can't hold date + description + amount at once,
    // regardless of what the data looks like.
    if (modalColumnCount < 3) return InferredMapping(null, emptySet(), emptyList())

    val scanLimit = minOf(rows.size, MAX_ROWS_TO_SAMPLE)
    val firstDataRowIndex = (0 until scanLimit).firstOrNull { i ->
        val row = rows[i]
        row.cells.size == modalColumnCount && looksLikeDataRow(row.cells)
    } ?: return InferredMapping(null, emptySet(), emptyList())

    val headerRowIndex = (firstDataRowIndex - 1).takeIf { it >= 0 }
        ?.takeIf { above -> rows[above].cells.size == modalColumnCount && !looksLikeDataRow(rows[above].cells) }
    val headerCells = headerRowIndex?.let { rows[it].cells }

    val sample = rows.asSequence()
        .drop(firstDataRowIndex)
        .filter { it.cells.size == modalColumnCount }
        .take(MAX_ROWS_TO_SAMPLE)
        .toList()

    val uncertain = mutableSetOf<MappingField>()
    val columns = 0 until modalColumnCount

    val dateColumn = pickDateColumn(sample, columns, uncertain)
    val moneyLikeColumns = columns.filter { it != dateColumn }.filter { c -> isMoneyLikeColumn(sample, c) }

    val amountSide = resolveAmountStructure(sample, moneyLikeColumns, headerCells, uncertain)
        ?: return InferredMapping(null, emptySet(), emptyList())

    val descriptionCandidates = columns.filterNot {
        it == dateColumn || it in moneyLikeColumns
    }
    val descriptionColumn = pickDescriptionColumn(sample, descriptionCandidates, headerCells, uncertain)
        ?: return InferredMapping(null, emptySet(), emptyList())

    val usedColumns = setOfNotNull(
        dateColumn,
        descriptionColumn,
        amountSide.amountColumn,
        amountSide.debitColumn,
        amountSide.creditColumn,
    )
    val unmappedColumns = columns.filterNot { it in usedColumns }

    val mapping = ColumnMapping(
        headerRowIndex = headerRowIndex,
        firstDataRowIndex = firstDataRowIndex,
        dateColumn = dateColumn,
        dateFormat = resolvedDateFormat(sample, dateColumn, uncertain),
        descriptionColumn = descriptionColumn,
        amountColumn = amountSide.amountColumn,
        amountSign = amountSide.amountSign,
        signColumn = null,
        debitColumn = amountSide.debitColumn,
        creditColumn = amountSide.creditColumn,
    )
    return InferredMapping(mapping, uncertain, unmappedColumns)
}

/** Never a real format string -- a marker that DATE_FORMAT is in uncertainFields and slice 4 hasn't run yet. */
private const val AMBIGUOUS_DATE_FORMAT_PLACEHOLDER = "AMBIGUOUS"

private fun looksLikeDataRow(cells: List<String>): Boolean {
    val dateIndex = cells.indexOfFirst { looksLikeDate(it) }
    if (dateIndex == -1) return false
    return cells.indices.any { it != dateIndex && looksMoneyShaped(cells[it]) }
}

/**
 * `Money.parse` is deliberately lenient once a cell is already known to hold
 * an amount -- it strips currency symbols and formatting noise, which means
 * it also succeeds on ordinary text that merely contains a digit somewhere
 * ("Row 1", "Invoice 42", "Store #7"), extracting whatever digits it finds
 * as if they were a value. That's fine for extraction; it's wrong for
 * *deciding* whether a column holds amounts in the first place -- a
 * description column with an incidental digit would otherwise get counted
 * as a second amount-shaped candidate, and starve the real description
 * column of anywhere left to be assigned (§2's "whatever's left"). A
 * plausible amount has no letters in it at all.
 */
private fun looksMoneyShaped(cell: String): Boolean = cell.none { it.isLetter() } && Money.parse(cell) != null

// --- date shape (column identification vs. format disambiguation, see below) ---

private val ISO_DATE = Regex("""\d{4}-(\d{2})-(\d{2})""")

/** Separator captured in group 2 so a resolved numeric format can echo it back, e.g. "dd-MM-yyyy". */
private val NUMERIC_DATE = Regex("""(\d{1,2})([/\-.])(\d{1,2})[/\-.]\d{4}""")
private val MONTH_NAME_DATE = Regex("""(\d{1,2})-([A-Za-z]{3})-\d{4}""")
private val MONTH_ABBREVIATIONS = setOf(
    "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
)

/**
 * Three structurally distinct shapes, not three formats to choose between --
 * this only answers "does this look date-shaped at all," which is enough to
 * find the date column. Which of the two numeric orderings applies is left
 * unresolved here on purpose (see the class doc).
 */
private fun looksLikeDate(cell: String): Boolean {
    val trimmed = cell.trim()
    ISO_DATE.matchEntire(trimmed)?.let { m ->
        val (month, day) = m.destructured
        return month.toInt() in 1..12 && day.toInt() in 1..31
    }
    MONTH_NAME_DATE.matchEntire(trimmed)?.let { m ->
        val (day, month) = m.destructured
        return day.toInt() in 1..31 && month.lowercase() in MONTH_ABBREVIATIONS
    }
    NUMERIC_DATE.matchEntire(trimmed)?.let { m ->
        val ai = m.groupValues[1].toInt()
        val bi = m.groupValues[3].toInt()
        return (ai in 1..31 && bi in 1..12) || (ai in 1..12 && bi in 1..31)
    }
    return false
}

private fun looksLikeIsoDate(cell: String): Boolean = ISO_DATE.matchEntire(cell.trim())?.let { m ->
    val (month, day) = m.destructured
    month.toInt() in 1..12 && day.toInt() in 1..31
} ?: false

private fun pickDateColumn(sample: List<CsvRow>, columns: IntRange, uncertain: MutableSet<MappingField>): Int {
    val hitRates = columns.associateWith { c ->
        sample.count { looksLikeDate(it.cells[c]) }.toDouble() / sample.size
    }
    val bestRate = hitRates.values.max()
    val bestColumns = hitRates.filterValues { it == bestRate }.keys
    if (bestRate < ROLE_HIT_RATE_THRESHOLD || bestColumns.size > 1) {
        uncertain += MappingField.DATE_COLUMN
    }
    return bestColumns.min()
}

/**
 * ISO and month-name dates are unambiguous by construction -- resolved
 * confidently here with no day-first/month-first question to ask. Anything
 * else reaching this point is the numeric dd/mm-vs-mm/dd family, handed to
 * [resolveNumericDateFormat]. Either way, [AMBIGUOUS_DATE_FORMAT_PLACEHOLDER]
 * is never returned without [MappingField.DATE_FORMAT] also landing in
 * [uncertain] -- nothing downstream can mistake the placeholder for a
 * confirmed format without also seeing the uncertainty flag.
 */
private fun resolvedDateFormat(sample: List<CsvRow>, dateColumn: Int, uncertain: MutableSet<MappingField>): String {
    val values = sample.map { it.cells[dateColumn] }
    return when {
        values.all { looksLikeIsoDate(it) } -> "yyyy-MM-dd"
        values.all { MONTH_NAME_DATE.matchEntire(it.trim()) != null } -> "dd-MMM-yyyy"
        else -> resolveNumericDateFormat(values, uncertain)
    }
}

/**
 * The rule from §2: a value with a component over 12 in the day position
 * can only be day-first; over 12 in the month position, only month-first.
 * Applied over the whole sampled column rather than one value, there are
 * three outcomes, not two:
 *
 * - Every value parses validly as day-first, and at least one value rules
 *   out month-first (its second component is over 12) -- day-first, confident.
 * - The mirror image -- month-first, confident.
 * - Every value parses validly under *both* orderings (no value's second
 *   component ever exceeds 12 in either position -- genuinely possible for
 *   a statement covering under 13 days of a month), or, less commonly,
 *   values disagree with each other (one value is only valid day-first,
 *   another only valid month-first, so no single format parses the whole
 *   column) -- either way, this is undecidable and says so. There is no
 *   locale fallback and no device-default tiebreak: a wrong guess here
 *   silently shuffles every date in the file, and that's worse than asking.
 */
private fun resolveNumericDateFormat(values: List<String>, uncertain: MutableSet<MappingField>): String {
    val matches = values.mapNotNull { NUMERIC_DATE.matchEntire(it.trim()) }
    val separator = matches.firstOrNull()?.groupValues?.get(2) ?: "/"

    fun component(m: MatchResult, index: Int) = m.groupValues[index].toInt()
    val dayFirstValidForAll = matches.isNotEmpty() &&
        matches.all { component(it, 1) in 1..31 && component(it, 3) in 1..12 }
    val monthFirstValidForAll = matches.isNotEmpty() &&
        matches.all { component(it, 3) in 1..31 && component(it, 1) in 1..12 }

    return when {
        dayFirstValidForAll && !monthFirstValidForAll -> "dd${separator}MM${separator}yyyy"
        monthFirstValidForAll && !dayFirstValidForAll -> "MM${separator}dd${separator}yyyy"
        else -> {
            uncertain += MappingField.DATE_FORMAT
            AMBIGUOUS_DATE_FORMAT_PLACEHOLDER
        }
    }
}

// --- amount structure ---

/**
 * Blank cells are excluded from the hit-rate denominator, not counted as
 * misses: a debit/credit column is *supposed* to be blank on the rows where
 * the other side applies, and a balance column starting partway through the
 * file is blank before that -- neither is a parse failure. A column that's
 * blank on every sampled row (nonBlank == 0) isn't money-shaped by vacuous
 * 0/0 truth; it's excluded explicitly.
 */
private fun isMoneyLikeColumn(sample: List<CsvRow>, column: Int): Boolean {
    val nonBlank = sample.count { it.cells[column].isNotBlank() }
    if (nonBlank == 0) return false
    val parsed = sample.count { looksMoneyShaped(it.cells[column]) }
    return parsed.toDouble() / nonBlank >= ROLE_HIT_RATE_THRESHOLD
}

private data class AmountStructure(
    val amountColumn: Int?,
    val amountSign: AmountSign?,
    val debitColumn: Int?,
    val creditColumn: Int?,
)

/**
 * Implements the balance-then-pair pipeline from §2. Returns null only when
 * there isn't even one money-shaped column to work with, which the caller
 * treats as total inference failure (docs/csv-import-design.md §2's "gives
 * up entirely" case) since a mapping needs an amount side to mean anything.
 *
 * Known gap, not attempted here: three or more money-shaped candidate
 * columns (e.g. debit + credit + balance together). The pairwise balance
 * check below tests each candidate against one other candidate at a time,
 * which can't recognise a balance column against a *net* of a debit/credit
 * pair. That three-column case falls through to the uncertain path rather
 * than being silently misclassified -- correct, but not the properly-solved
 * case the two-column balance detection is.
 */
private fun resolveAmountStructure(
    sample: List<CsvRow>,
    candidates: List<Int>,
    headerCells: List<String>?,
    uncertain: MutableSet<MappingField>,
): AmountStructure? {
    if (candidates.isEmpty()) return null

    val balanceMatch = findBalanceColumn(sample, candidates)
    val remaining = if (balanceMatch != null) candidates - balanceMatch.balanceColumn else candidates

    return when (remaining.size) {
        1 -> {
            val amountColumn = remaining.single()
            val sign = balanceMatch?.amountSign
            if (sign == null) uncertain += MappingField.AMOUNT_SIGN
            AmountStructure(amountColumn, sign ?: AmountSign.NEGATIVE_IS_DEBIT, null, null)
        }

        2 -> resolveDebitCreditOrFallback(sample, remaining[0], remaining[1], headerCells, uncertain)

        else -> {
            // 0 candidates after balance removal can't happen (removal takes
            // at most one); 3+ is the documented gap above.
            uncertain += MappingField.AMOUNT_COLUMNS
            uncertain += MappingField.AMOUNT_SIGN
            AmountStructure(remaining.first(), AmountSign.NEGATIVE_IS_DEBIT, null, null)
        }
    }
}

private data class BalanceMatch(val balanceColumn: Int, val amountColumn: Int, val amountSign: AmountSign)

/**
 * Tests every ordered pair of candidates as (balance, amount); a match means
 * `balance[i] - balance[i-1]` equals `amount[i]` or `-amount[i]`, in exact
 * integer minor units -- there is nothing to be approximate about once both
 * sides are already Money, so "≈" in the design doc means "matches on most
 * checkable rows," never fuzzy per-comparison tolerance. Rows where either
 * side of a pair doesn't parse (a blank balance cell, e.g. the column only
 * starting partway through the file) are skipped, not counted as a miss --
 * see [balanceDelta].
 *
 * Ambiguous on its own terms if more than one pair matches: that's treated
 * as no match at all, deferring to the uncertain fallback rather than
 * guessing which candidate is really the balance.
 */
private fun findBalanceColumn(sample: List<CsvRow>, candidates: List<Int>): BalanceMatch? {
    if (candidates.size < 2) return null
    val matches = candidates.flatMap { balanceCol ->
        candidates.filter { it != balanceCol }.mapNotNull { amountCol ->
            balanceDelta(sample, balanceCol, amountCol)?.let { sign -> BalanceMatch(balanceCol, amountCol, sign) }
        }
    }
    return matches.singleOrNull()
}

private fun balanceDelta(sample: List<CsvRow>, balanceColumn: Int, amountColumn: Int): AmountSign? {
    var checkable = 0
    var matchesPositive = 0
    var matchesNegative = 0
    for (i in 1 until sample.size) {
        val previousBalance = Money.parse(sample[i - 1].cells[balanceColumn]) ?: continue
        val currentBalance = Money.parse(sample[i].cells[balanceColumn]) ?: continue
        val amount = Money.parse(sample[i].cells[amountColumn]) ?: continue
        checkable++
        val delta = currentBalance.minorUnits - previousBalance.minorUnits
        when (delta) {
            amount.minorUnits -> matchesPositive++
            -amount.minorUnits -> matchesNegative++
        }
    }
    if (checkable < MIN_CHECKABLE_BALANCE_PAIRS) return null
    val positiveRate = matchesPositive.toDouble() / checkable
    val negativeRate = matchesNegative.toDouble() / checkable
    return when {
        positiveRate >= BALANCE_MATCH_RATE_THRESHOLD -> AmountSign.NEGATIVE_IS_DEBIT
        negativeRate >= BALANCE_MATCH_RATE_THRESHOLD -> AmountSign.POSITIVE_IS_DEBIT
        else -> null
    }
}

/**
 * Two money-shaped columns that aren't a resolved balance/amount pair: either
 * a debit/credit pair (per row, exactly one of the two is populated), or
 * genuinely unresolved. Header text, when it exists, is what turns a
 * confirmed pair into a confident debit/credit *assignment*; without it,
 * §2's "weak left-to-right prior" is exactly what this refuses to trust
 * silently.
 */
private fun resolveDebitCreditOrFallback(
    sample: List<CsvRow>,
    columnA: Int,
    columnB: Int,
    headerCells: List<String>?,
    uncertain: MutableSet<MappingField>,
): AmountStructure {
    val exclusiveRate = sample.count { row ->
        val aPresent = Money.parse(row.cells[columnA]) != null
        val bPresent = Money.parse(row.cells[columnB]) != null
        aPresent != bPresent
    }.toDouble() / sample.size

    if (exclusiveRate < ROLE_HIT_RATE_THRESHOLD) {
        // Neither a debit/credit pair (not exclusive) nor a resolved balance
        // (findBalanceColumn already tried and failed) -- genuinely unclear.
        uncertain += MappingField.AMOUNT_COLUMNS
        uncertain += MappingField.AMOUNT_SIGN
        return AmountStructure(columnA, AmountSign.NEGATIVE_IS_DEBIT, null, null)
    }

    val headerA = headerCells?.getOrNull(columnA)?.lowercase()
    val headerB = headerCells?.getOrNull(columnB)?.lowercase()
    val aIsDebit = headerA != null && DEBIT_HEADER_HINTS.any { headerA.contains(it) }
    val aIsCredit = headerA != null && CREDIT_HEADER_HINTS.any { headerA.contains(it) }
    val bIsDebit = headerB != null && DEBIT_HEADER_HINTS.any { headerB.contains(it) }
    val bIsCredit = headerB != null && CREDIT_HEADER_HINTS.any { headerB.contains(it) }

    return when {
        aIsDebit && bIsCredit -> AmountStructure(null, null, columnA, columnB)
        bIsDebit && aIsCredit -> AmountStructure(null, null, columnB, columnA)
        else -> {
            uncertain += MappingField.AMOUNT_COLUMNS
            AmountStructure(null, null, columnA, columnB)
        }
    }
}

// --- description column ---

private fun pickDescriptionColumn(
    sample: List<CsvRow>,
    candidates: List<Int>,
    headerCells: List<String>?,
    uncertain: MutableSet<MappingField>,
): Int? {
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return candidates.single()

    val headerHinted = headerCells?.let { headers ->
        candidates.filter { c ->
            val header = headers.getOrNull(c)?.lowercase().orEmpty()
            DESCRIPTION_HEADER_HINTS.any { header.contains(it) }
        }
    }.orEmpty()
    if (headerHinted.size == 1) return headerHinted.single()

    val averageLength = candidates.associateWith { c ->
        sample.map { it.cells[c].trim().length }.average()
    }
    val ranked = averageLength.entries.sortedByDescending { it.value }
    val best = ranked[0]
    val runnerUp = ranked.getOrNull(1)
    // A clear winner is one no close second could plausibly have been picked
    // instead of; anything closer than that is a guess dressed up as one.
    if (runnerUp != null && best.value > 0 && runnerUp.value / best.value > 0.85) {
        uncertain += MappingField.DESCRIPTION_COLUMN
    }
    return best.key
}
