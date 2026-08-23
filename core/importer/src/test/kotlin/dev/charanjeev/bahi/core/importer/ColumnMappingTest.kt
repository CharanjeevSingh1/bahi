package dev.charanjeev.bahi.core.importer

import org.junit.Assert.assertThrows
import org.junit.Test

class ColumnMappingTest {

    @Test
    fun `single amount column with a sign is valid`() {
        mapping(amountColumn = 2, amountSign = AmountSign.NEGATIVE_IS_DEBIT)
    }

    @Test
    fun `debit and credit pair with no amount column is valid`() {
        mapping(amountColumn = null, amountSign = null, debitColumn = 2, creditColumn = 3)
    }

    @Test
    fun `single amount column with a sign column is valid`() {
        mapping(amountColumn = 2, amountSign = AmountSign.SIGN_COLUMN, signColumn = 3)
    }

    @Test
    fun `rejects a debit column with no matching credit column`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapping(amountColumn = null, amountSign = null, debitColumn = 2, creditColumn = null)
        }
    }

    @Test
    fun `rejects a credit column with no matching debit column`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapping(amountColumn = null, amountSign = null, debitColumn = null, creditColumn = 3)
        }
    }

    @Test
    fun `rejects both an amount column and a debit-credit pair together`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapping(amountColumn = 2, amountSign = AmountSign.NEGATIVE_IS_DEBIT, debitColumn = 3, creditColumn = 4)
        }
    }

    @Test
    fun `rejects neither an amount column nor a debit-credit pair`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapping(amountColumn = null, amountSign = null)
        }
    }

    @Test
    fun `rejects an amount column with no sign`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapping(amountColumn = 2, amountSign = null)
        }
    }

    @Test
    fun `rejects sign column set without amount sign being SIGN_COLUMN`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapping(amountColumn = 2, amountSign = AmountSign.NEGATIVE_IS_DEBIT, signColumn = 3)
        }
    }

    @Test
    fun `rejects amount sign SIGN_COLUMN with no sign column`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapping(amountColumn = 2, amountSign = AmountSign.SIGN_COLUMN, signColumn = null)
        }
    }

    @Test
    fun `rejects a debit-credit pair carrying an amount sign`() {
        assertThrows(IllegalArgumentException::class.java) {
            mapping(
                amountColumn = null,
                amountSign = AmountSign.NEGATIVE_IS_DEBIT,
                debitColumn = 2,
                creditColumn = 3,
            )
        }
    }

    /** A valid single-amount-column mapping by default; override per case. */
    private fun mapping(
        headerRowIndex: Int? = 0,
        firstDataRowIndex: Int = 1,
        dateColumn: Int = 0,
        dateFormat: String = "yyyy-MM-dd",
        descriptionColumn: Int = 1,
        amountColumn: Int? = 2,
        amountSign: AmountSign? = AmountSign.NEGATIVE_IS_DEBIT,
        signColumn: Int? = null,
        debitColumn: Int? = null,
        creditColumn: Int? = null,
    ) = ColumnMapping(
        headerRowIndex = headerRowIndex,
        firstDataRowIndex = firstDataRowIndex,
        dateColumn = dateColumn,
        dateFormat = dateFormat,
        descriptionColumn = descriptionColumn,
        amountColumn = amountColumn,
        amountSign = amountSign,
        signColumn = signColumn,
        debitColumn = debitColumn,
        creditColumn = creditColumn,
    )
}
