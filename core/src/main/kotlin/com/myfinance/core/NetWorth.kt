package com.myfinance.core

enum class AssetKind { BANK, MUTUAL_FUND, SIP, FD, RD, STOCK, PPF, NPS, EPF, GOLD, CASH, PROPERTY, OTHER }
enum class LiabilityKind { CREDIT_CARD, HOME_LOAN, CAR_LOAN, PERSONAL_LOAN, EDUCATION_LOAN, OTHER }

/** One investment or savings holding. [currentMinor] is the latest known value; it defaults to what was invested. */
data class Holding(
    val name: String,
    val kind: AssetKind,
    val investedMinor: Long,
    val currentMinor: Long = investedMinor,
)

data class Liability(val name: String, val kind: LiabilityKind, val outstandingMinor: Long)

data class NetWorthSummary(
    val assetsMinor: Long,
    val liabilitiesMinor: Long,
    val investedMinor: Long,
    val gainMinor: Long,
    val byKind: List<GroupTotal>,
) {
    val netWorthMinor: Long get() = assetsMinor - liabilitiesMinor
}

object NetWorth {
    fun of(holdings: List<Holding>, liabilities: List<Liability>): NetWorthSummary {
        val invested = holdings.filter { it.kind != AssetKind.BANK && it.kind != AssetKind.CASH }
        return NetWorthSummary(
            assetsMinor = holdings.sumOf { it.currentMinor },
            liabilitiesMinor = liabilities.sumOf { it.outstandingMinor },
            investedMinor = invested.sumOf { it.investedMinor },
            gainMinor = invested.sumOf { it.currentMinor - it.investedMinor },
            byKind = holdings.groupBy { it.kind }
                .map { (k, v) -> GroupTotal(k.label, v.sumOf { it.currentMinor }, v.size) }
                .sortedByDescending { it.totalMinor },
        )
    }

    /** Adds investment events (SIP instalments, FD bookings, buys, redemptions) into holdings by name. */
    fun applyEvents(start: List<Holding>, events: List<InvestmentEvent>): List<Holding> {
        val map = LinkedHashMap<Pair<AssetKind, String>, Holding>()
        for (h in start) map[h.kind to h.name.lowercase()] = h
        for (e in events) {
            val key = e.kind to e.name.lowercase()
            val old = map[key] ?: Holding(e.name, e.kind, 0, 0)
            val delta = if (e.redemption) -e.amountMinor else e.amountMinor
            map[key] = old.copy(
                investedMinor = (old.investedMinor + delta).coerceAtLeast(0),
                currentMinor = e.currentValueMinor ?: (old.currentMinor + delta).coerceAtLeast(0),
            )
        }
        return map.values.filter { it.currentMinor > 0 || it.investedMinor > 0 }
    }
}

val AssetKind.label: String
    get() = when (this) {
        AssetKind.BANK -> "Bank balance"; AssetKind.MUTUAL_FUND -> "Mutual funds"; AssetKind.SIP -> "SIPs"
        AssetKind.FD -> "Fixed deposits"; AssetKind.RD -> "Recurring deposits"; AssetKind.STOCK -> "Stocks"
        AssetKind.PPF -> "PPF"; AssetKind.NPS -> "NPS"; AssetKind.EPF -> "EPF / PF"; AssetKind.GOLD -> "Gold"; AssetKind.CASH -> "Cash"
        AssetKind.PROPERTY -> "Property"; AssetKind.OTHER -> "Other"
    }

/** Something an investment SMS told us: money put in or taken out of a holding. */
data class InvestmentEvent(
    val kind: AssetKind,
    val name: String,
    val amountMinor: Long,
    val redemption: Boolean,
    val units: String?,
    /** Some statements (CAMS/KFintech, NSDL) report the current value; when present it replaces our estimate. */
    val currentValueMinor: Long?,
    val timestamp: Long,
)

/**
 * Reads investment SMS from mutual fund registrars (CAMS, KFintech), AMCs, brokers and banks (FD/RD).
 *   "Your SIP of Rs.5,000.00 in Axis Bluechip Fund - Growth has been processed. Units allotted: 98.123"
 *   "Dear Investor, purchase of Rs 10000 in HDFC Flexi Cap Fund allotted 25.5 units at NAV 392.1"
 *   "Your FD No. XX4521 for Rs 1,00,000.00 has been booked @ 7.10% p.a."
 *   "Bought 10 INFY @ 1,520.50 on NSE. Trade value Rs 15,205.00"
 *   "Redemption of Rs 20,000 from SBI Small Cap Fund processed"
 */
object InvestmentSmsParser {
    private val amount = Regex("""(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val units = Regex("""(?:units?\s*(?:allotted|alloted|credited|redeemed)?\s*[:\-]?\s*)([0-9]+(?:\.[0-9]+)?)|([0-9]+(?:\.[0-9]+)?)\s*units""", RegexOption.IGNORE_CASE)
    private val fundName = Regex("""\b(?:in|of|from|for|to)\s+(?:the\s+)?(?:scheme\s+)?(?!(?:rs\.?|inr|₹)\s*[0-9])([A-Z][A-Za-z0-9&().\- ]{2,60}?(?:fund|scheme|etf|plan)(?:\s*-\s*[A-Za-z ]+?)?)(?=\s+(?:has|is|was|for|at|on|folio|units|allotted|processed|nav|with)\b|[.,;]|$)""", RegexOption.IGNORE_CASE)
    private val stockTrade = Regex("""\b(bought|sold|buy|sell)\s+([0-9]+)\s+(?:shares?\s+of\s+)?([A-Z][A-Z0-9&\-]{1,20})\b""", RegexOption.IGNORE_CASE)
    private val currentValue = Regex("""(?:current|market|valuation)\s+value\s*(?:is|:|of)?\s*(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val epfBalance = Regex("""balance[^0-9₹]{0,60}?(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val epfContribution = Regex("""contribution\s+of\s+(?:rs\.?|inr|₹)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
    private val ignore = Regex("""\botp\b|\bfailed\b|\brejected\b|\bunsuccessful\b|will be debited|is due|\breminder\b|\bmandate\s+registered""", RegexOption.IGNORE_CASE)

    fun parse(body: String, timestamp: Long): InvestmentEvent? {
        val text = body.replace(Regex("\\s+"), " ").trim()
        if (ignore.containsMatchIn(text)) return null
        val lower = text.lowercase()

        // EPFO passbook SMS: "Contribution of Rs. 3,600/- for due month 102026 has been received.
        // Passbook balance against TN/MAS/... is Rs. 4,56,789/-". The balance becomes the EPF value.
        if (Regex("""\bepfo?\b|provident fund|\buan\b""").containsMatchIn(lower)) {
            val balance = epfBalance.find(text)?.let { parseAmountToMinor(it.groupValues[1].trimEnd('.', ',')) }
            val contribution = epfContribution.find(text)?.let { parseAmountToMinor(it.groupValues[1].trimEnd('.', ',')) }
            if (balance == null && contribution == null) return null
            return InvestmentEvent(AssetKind.EPF, "EPF", contribution ?: 0, false, null, balance, timestamp)
        }

        stockTrade.find(text)?.let { m ->
            val value = amount.findAll(text).lastOrNull()?.let { parseAmountToMinor(it.groupValues[1]) } ?: return null
            val sold = m.groupValues[1].lowercase().startsWith("s")
            return InvestmentEvent(AssetKind.STOCK, m.groupValues[3].uppercase(), value, sold, m.groupValues[2], null, timestamp)
        }

        val amt = amount.findAll(text).firstOrNull { !it.isValueMention(text) }?.let { parseAmountToMinor(it.groupValues[1]) } ?: return null
        val current = currentValue.find(text)?.let { parseAmountToMinor(it.groupValues[1]) }
        val unitText = units.find(text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

        val kind = when {
            Regex("""\b(fixed deposit|\bfd\b|term deposit)""").containsMatchIn(lower) -> AssetKind.FD
            Regex("""\b(recurring deposit|\brd\b)""").containsMatchIn(lower) -> AssetKind.RD
            Regex("""\bppf\b""").containsMatchIn(lower) -> AssetKind.PPF
            Regex("""\bnps\b|pran""").containsMatchIn(lower) -> AssetKind.NPS
            Regex("""\bsip\b""").containsMatchIn(lower) -> AssetKind.SIP
            Regex("""mutual fund|\bfund\b|\bnav\b|units|folio|cams|kfintech|amc""").containsMatchIn(lower) -> AssetKind.MUTUAL_FUND
            Regex("""\bgold\b|sgb""").containsMatchIn(lower) -> AssetKind.GOLD
            else -> return null
        }
        val booked = Regex("""processed|allotted|alloted|booked|opened|created|invested|purchase|credited|successful|confirmed|deposited|redeemed|redemption|matured|closed""").containsMatchIn(lower)
        if (!booked) return null
        val redemption = Regex("""redeem|redemption|withdraw|matured|closed|premature""").containsMatchIn(lower)

        val name = fundName.find(text)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
            ?: when (kind) {
                AssetKind.FD -> "Fixed deposit" + (Regex("""(?:no\.?|number|a/c)\s*[xX*]*([0-9]{3,})""", RegexOption.IGNORE_CASE).find(text)?.let { " ••" + it.groupValues[1].takeLast(4) } ?: "")
                AssetKind.RD -> "Recurring deposit"
                else -> kind.label
            }
        return InvestmentEvent(kind, name, amt, redemption, unitText, current, timestamp)
    }

    private fun MatchResult.isValueMention(text: String): Boolean {
        val before = text.substring(maxOf(0, range.first - 20), range.first).lowercase()
        return Regex("""(current|market|valuation)\s+value\s*(is|:|of)?\s*$|nav\s*(of|:)?\s*$""").containsMatchIn(before)
    }
}
