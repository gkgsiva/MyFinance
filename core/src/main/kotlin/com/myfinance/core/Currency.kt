package com.myfinance.core

import java.math.BigDecimal
import java.math.RoundingMode

/** An amount in a foreign currency, in its minor units (cents). */
data class ForeignAmount(val code: String, val amountMinor: Long) {
    val text: String get() = "$code " + BigDecimal.valueOf(amountMinor, 2).stripTrailingZeros().toPlainString()
}

/** A foreign amount found in a note, with the rate used to convert it. */
data class ForeignNote(val amount: ForeignAmount, val rate: Double)

/** Foreign-currency spends (trips, USD card charges), converted to rupees. */
object Fx {
    /** Rupees per unit. Approximate; the user can change them under Plan > Foreign currency. */
    val defaultRates: Map<String, Double> = linkedMapOf(
        "USD" to 88.0, "EUR" to 102.0, "GBP" to 117.0, "AED" to 24.0, "SGD" to 68.0, "THB" to 2.7, "MYR" to 21.0,
        "JPY" to 0.58, "AUD" to 58.0, "CAD" to 63.0, "CHF" to 110.0, "HKD" to 11.3, "SAR" to 23.5, "QAR" to 24.2,
        "LKR" to 0.29, "NPR" to 0.62, "NZD" to 51.0, "IDR" to 0.0053, "VND" to 0.0034,
    )

    private val codes = defaultRates.keys.joinToString("|")
    private val codeAmount = Regex("""\b($codes)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b""")
    private val symbolAmount = Regex("""(US\$|\$|€|£)\s?([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val noteRegex = Regex("""\b([A-Z]{3}) ([0-9]+(?:\.[0-9]{1,2})?) at ₹([0-9]+(?:\.[0-9]+)?)""")

    /** The first foreign-currency amount in [text], with where it starts. */
    fun find(text: String): Pair<ForeignAmount, Int>? {
        val a = codeAmount.find(text)?.let { m ->
            parseAmountToMinor(m.groupValues[2])?.let { ForeignAmount(m.groupValues[1].uppercase(), it) to m.range.first }
        }
        val b = symbolAmount.find(text)?.let { m ->
            val code = when (m.groupValues[1]) { "€" -> "EUR"; "£" -> "GBP"; else -> "USD" }
            parseAmountToMinor(m.groupValues[2])?.let { ForeignAmount(code, it) to m.range.first }
        }
        return listOfNotNull(a, b).minByOrNull { it.second }?.takeIf { it.first.amountMinor > 0 }
    }

    fun rate(code: String, rates: Map<String, Double>): Double? = rates[code.uppercase()] ?: defaultRates[code.uppercase()]

    /** Rupee paise for [amount] at [rate] rupees per unit. */
    fun toInrMinor(amount: ForeignAmount, rate: Double): Long =
        BigDecimal.valueOf(amount.amountMinor).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.HALF_UP).toLong()

    /** The note kept with a converted entry, e.g. "USD 12.99 at ₹88.00". */
    fun note(amount: ForeignAmount, rate: Double): String =
        "${amount.text} at ₹" + BigDecimal.valueOf(rate).setScale(if (rate < 1) 4 else 2, RoundingMode.HALF_UP).toPlainString()

    fun parseNote(note: String?): ForeignNote? {
        val m = noteRegex.find(note ?: return null) ?: return null
        val amt = parseAmountToMinor(m.groupValues[2]) ?: return null
        return ForeignNote(ForeignAmount(m.groupValues[1], amt), m.groupValues[3].toDoubleOrNull() ?: return null)
    }

    /** Reads stored rates, "USD=88.0;EUR=102.0". */
    fun parseRates(s: String?): Map<String, Double> =
        s.orEmpty().split(';').mapNotNull { p ->
            val (k, v) = p.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            v.toDoubleOrNull()?.takeIf { it > 0 }?.let { k.trim().uppercase() to it }
        }.toMap()

    fun formatRates(rates: Map<String, Double>): String = rates.entries.joinToString(";") { "${it.key}=${it.value}" }

    data class CurrencyTotal(val code: String, val foreignMinor: Long, val inrMinor: Long, val count: Int)

    /** Totals per currency over entries whose note carries a foreign amount. */
    fun totals(entries: List<Pair<ForeignNote, Long>>): List<CurrencyTotal> =
        entries.groupBy { it.first.amount.code }.map { (code, list) ->
            CurrencyTotal(code, list.sumOf { it.first.amount.amountMinor }, list.sumOf { it.second }, list.size)
        }.sortedByDescending { it.inrMinor }
}
