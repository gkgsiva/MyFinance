package com.myfinance.core

/** Direction of money movement relative to the user. */
enum class TxnType { DEBIT, CREDIT }

/**
 * A transaction extracted from a bank / UPI / card SMS.
 *
 * Amounts are stored in minor units (paise) to avoid floating point errors.
 */
data class ParsedTransaction(
    val amountMinor: Long,
    val type: TxnType,
    val merchant: String?,
    val accountTail: String?,
    val bank: String?,
    val reference: String?,
    val balanceMinor: Long?,
    val timestamp: Long,
)

/** Formats minor units as an Indian-rupee string, e.g. 123456 -> "₹1,234.56". */
fun formatInr(amountMinor: Long, withSymbol: Boolean = true): String {
    val negative = amountMinor < 0
    val abs = kotlin.math.abs(amountMinor)
    val rupees = abs / 100
    val paise = abs % 100
    val digits = rupees.toString()
    // Indian grouping: last 3 digits, then groups of 2.
    val grouped = if (digits.length <= 3) {
        digits
    } else {
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
    }
    val body = if (paise == 0L) grouped else "$grouped.${paise.toString().padStart(2, '0')}"
    return (if (negative) "-" else "") + (if (withSymbol) "₹" else "") + body
}

/** Parses a user-entered amount like "1,234.5" into minor units. Returns null when invalid. */
fun parseAmountToMinor(text: String): Long? {
    val cleaned = text.replace(",", "").replace("₹", "").trim()
    if (cleaned.isEmpty()) return null
    val bd = cleaned.toBigDecimalOrNull() ?: return null
    if (bd.signum() < 0) return null
    return bd.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
}
