package com.myfinance.core

/** A stored transaction as the rule tester sees it. */
data class RuleTestRow(
    val id: Long,
    val smsText: String?,
    val merchant: String?,
    val type: TxnType,
    val amountMinor: Long,
    val category: String,
    val timestamp: Long,
    /** Edited by hand or typed in: rules never change these. */
    val locked: Boolean,
)

data class RuleTestChange(
    val row: RuleTestRow,
    val newCategory: String?,
    val newMerchant: String?,
    val excluded: Boolean,
    val ignored: Boolean,
) {
    val changesSomething: Boolean
        get() = (newCategory != null && newCategory != row.category) || (newMerchant != null && newMerchant != row.merchant) || excluded || ignored
}

data class RuleTestResult(
    val changes: List<RuleTestChange>,
    /** Matched, but an earlier rule wins for these. */
    val wonByOtherRule: Int,
    /** Matched, but edited by hand or typed in, so left alone. */
    val locked: Int,
    val matched: Int,
)

/** Shows which past transactions a rule would change before it is saved. */
object RuleTester {
    fun test(rule: CustomRule, otherRules: List<CustomRule>, rows: List<RuleTestRow>): RuleTestResult {
        val all = (otherRules.filter { it.id != rule.id } + rule.copy(enabled = true)).sortedBy { it.priority }
        val changes = ArrayList<RuleTestChange>()
        var other = 0
        var locked = 0
        var matched = 0
        for (r in rows) {
            val input = RuleInput(null, r.smsText, r.merchant, r.type, r.amountMinor)
            if (!CustomRules.matches(rule.copy(enabled = true), input)) continue
            matched++
            if (r.locked) { locked++; continue }
            val winner = CustomRules.apply(all, input)
            if (winner == null || winner.rule.id != rule.id) { other++; continue }
            val c = RuleTestChange(r, rule.setCategory, rule.renameMerchant, rule.exclude, rule.ignore)
            if (c.changesSomething) changes += c
        }
        return RuleTestResult(changes.sortedByDescending { it.row.timestamp }, other, locked, matched)
    }
}
