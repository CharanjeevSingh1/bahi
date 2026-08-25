package dev.charanjeev.bahi.core.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import dev.charanjeev.bahi.core.model.Money
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * One place that decides how money is rendered, so an expense is red in every
 * screen and the grouping follows the user's locale rather than the developer's.
 */
@Composable
fun MoneyText(
    money: Money,
    currencyCode: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    Text(
        text = formatMoney(money, currencyCode),
        color = if (money.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = style,
        modifier = modifier,
    )
}

/**
 * [MoneyText]'s formatter, exposed because some callers need the string
 * rather than a composable -- a budget row interpolates two amounts into one
 * sentence ("₹4,300 of ₹8,000"), which two MoneyTexts can't express. Public
 * so those callers use this instead of growing a second formatter that
 * rounds or groups differently.
 */
fun formatMoney(
    money: Money,
    currencyCode: String,
    locale: Locale = Locale.getDefault(),
): String {
    val format = NumberFormat.getCurrencyInstance(locale).apply {
        currency = Currency.getInstance(currencyCode)
    }
    val fractionDigits = format.currency?.defaultFractionDigits ?: 2
    val divisor = generateSequence(1L) { it * 10 }.elementAt(fractionDigits)
    return format.format(money.minorUnits.toDouble() / divisor)
}
