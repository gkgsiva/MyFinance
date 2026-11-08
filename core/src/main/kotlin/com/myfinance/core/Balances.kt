package com.myfinance.core

/** A balance seen in an SMS: a bank account balance, or the available limit on a credit card. */
data class BalanceReading(
    val bank: String?,
    val accountTail: String?,
    val amountMinor: Long,
    val isCardLimit: Boolean,
    val timestamp: Long,
) {
    val accountKey: String get() = "${bank.orEmpty()}|${accountTail.orEmpty()}|$isCardLimit"

    val label: String
        get() = when {
            bank != null && accountTail != null -> "$bank ••$accountTail"
            accountTail != null -> "••$accountTail"
            else -> bank ?: "Account"
        }
}

object BalanceParser {
    private const val AMOUNT = """(?:rs\.?|inr|₹)\s*(-?[0-9][0-9,]*(?:\.[0-9]{1,2})?)"""

    private val balance = Regex(
        """(?:\b(?:avl|avail(?:able)?|clear|closing|total|net)\.?\s*)?\bbal(?:ance)?\b\.?\s*(?:in\s+(?:your\s+)?(?:a/c|ac|acct|account)\s*\S*\s*)?(?:is|:|-|of|=|as\s+on\s+\S+)?\s*(?:is|:|-)?\s*$AMOUNT""",
        RegexOption.IGNORE_CASE,
    )
    private val limit = Regex(
        """(?:avl\.?|avail(?:able)?\.?)\s*(?:credit\s+)?(?:lmt|limit)\.?\s*(?:is|:|-|of|=)?\s*$AMOUNT""",
        RegexOption.IGNORE_CASE,
    )
    private val ignore = Regex("""\botp\b|\boffer\b|pre-?approved|\bminimum\s+balance\b|\bmin\.?\s+bal""", RegexOption.IGNORE_CASE)

    fun parse(sender: String, body: String, timestamp: Long): BalanceReading? {
        val text = body.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || ignore.containsMatchIn(text)) return null
        val bank = SmsParser.bank(sender, text)
        val tail = SmsParser.accountTail(text)
        balance.find(text)?.let { m ->
            val amount = parseSigned(m.groupValues[1]) ?: return null
            return BalanceReading(bank, tail, amount, isCardLimit = false, timestamp = timestamp)
        }
        limit.find(text)?.let { m ->
            val amount = parseSigned(m.groupValues[1]) ?: return null
            return BalanceReading(bank, tail, amount, isCardLimit = true, timestamp = timestamp)
        }
        return null
    }

    private fun parseSigned(s: String): Long? =
        if (s.startsWith("-")) parseAmountToMinor(s.drop(1))?.let { -it } else parseAmountToMinor(s)
}

object Balances {
    /** The newest reading for each account, bank accounts first. */
    fun latest(readings: List<BalanceReading>): List<BalanceReading> =
        readings.groupBy { it.accountKey }
            .map { (_, list) -> list.maxBy { it.timestamp } }
            .sortedWith(compareBy<BalanceReading> { it.isCardLimit }.thenBy { it.label })

    /** Sum of the latest bank balances (card limits are not money you have). */
    fun totalBankMinor(latest: List<BalanceReading>): Long = latest.filter { !it.isCardLimit }.sumOf { it.amountMinor }
}
