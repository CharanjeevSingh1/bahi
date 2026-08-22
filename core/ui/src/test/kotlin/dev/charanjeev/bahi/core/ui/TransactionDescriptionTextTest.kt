package dev.charanjeev.bahi.core.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransactionDescriptionTextTest {

    @Test
    fun `title-cases a shouting-case description`() {
        assertThat(titleCaseTransactionDescription("BLUE TOKAI COFFEE")).isEqualTo("Blue Tokai Coffee")
    }

    @Test
    fun `keeps a known acronym upper while title-casing the rest of the words`() {
        assertThat(titleCaseTransactionDescription("ATM WITHDRAWAL")).isEqualTo("ATM Withdrawal")
        assertThat(titleCaseTransactionDescription("UPI PAYMENT TO JOHN DOE")).isEqualTo("UPI Payment To John Doe")
        assertThat(titleCaseTransactionDescription("NEFT TRANSFER")).isEqualTo("NEFT Transfer")
        assertThat(titleCaseTransactionDescription("EMI - HOME LOAN")).isEqualTo("EMI - Home Loan")
    }

    @Test
    fun `a lone acronym stays as-is`() {
        assertThat(titleCaseTransactionDescription("ATM")).isEqualTo("ATM")
    }

    @Test
    fun `leaves mixed-case input untouched`() {
        assertThat(titleCaseTransactionDescription("Uber Eats")).isEqualTo("Uber Eats")
        assertThat(titleCaseTransactionDescription("Netflix Subscription")).isEqualTo("Netflix Subscription")
    }

    @Test
    fun `leaves lowercase input untouched`() {
        assertThat(titleCaseTransactionDescription("coffee shop")).isEqualTo("coffee shop")
    }

    @Test
    fun `leaves text with no letters untouched`() {
        assertThat(titleCaseTransactionDescription("12345")).isEqualTo("12345")
        assertThat(titleCaseTransactionDescription("")).isEqualTo("")
    }
}
