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
    fun `keeps a short vowel-less initialism upper without being on the known list`() {
        assertThat(titleCaseTransactionDescription("PVR CINEMAS")).isEqualTo("PVR Cinemas")
        assertThat(titleCaseTransactionDescription("HDFC BANK")).isEqualTo("HDFC Bank")
    }

    @Test
    fun `title-cases ordinary words that happen to have no vowel-bearing sibling nearby`() {
        assertThat(titleCaseTransactionDescription("RENT")).isEqualTo("Rent")
        assertThat(titleCaseTransactionDescription("NETFLIX")).isEqualTo("Netflix")
    }

    @Test
    fun `the vowel heuristic knowingly mis-title-cases a vowel-bearing initialism`() {
        // IRCTC (Indian Railway Catering and Tourism Corporation) contains
        // an "I", so it reads as an ordinary word to the heuristic and loses
        // its casing -- exactly the documented trade-off in isAcronym().
        assertThat(titleCaseTransactionDescription("IRCTC BOOKING")).isEqualTo("Irctc Booking")
    }

    @Test
    fun `the vowel heuristic knowingly mis-title-cases a short vowel-less real word`() {
        // "GYM" has no vowel and is five characters or fewer, so it's
        // indistinguishable from an initialism under this rule.
        assertThat(titleCaseTransactionDescription("GYM MEMBERSHIP")).isEqualTo("GYM Membership")
    }

    @Test
    fun `leaves mixed-case input untouched`() {
        assertThat(titleCaseTransactionDescription("Uber Eats")).isEqualTo("Uber Eats")
        assertThat(titleCaseTransactionDescription("Netflix Subscription")).isEqualTo("Netflix Subscription")
        assertThat(titleCaseTransactionDescription("iPhone Case")).isEqualTo("iPhone Case")
        assertThat(titleCaseTransactionDescription("McDonald's")).isEqualTo("McDonald's")
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
