package com.myfinance.core

/** Which text a rule looks at. */
enum class RuleField { ANY, MERCHANT, SMS_TEXT, SENDER }

/**
 * A user rule such as "any SMS with SWIGGY goes to Food". Rules run in [priority] order (lowest first)
 * before the learnt merchant rules and built-in keywords; the first matching rule wins.
 */
data class CustomRule(
    val id: String,
    val pattern: String,
    val field: RuleField = RuleField.ANY,
    /** When true, [pattern] is a regular expression; otherwise a case-insensitive "contains". */
    val isRegex: Boolean = false,
    val onlyType: TxnType? = null,
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val setCategory: String? = null,
    val renameMerchant: String? = null,
    /** Leave matching transactions out of totals (e.g. transfers between your own accounts). */
    val exclude: Boolean = false,
    /** Drop matching SMS entirely (e.g. a wallet that sends duplicate alerts). */
    val ignore: Boolean = false,
    val taxSection: TaxSection? = null,
    val priority: Int = 0,
    val enabled: Boolean = true,
)

data class RuleInput(val sender: String?, val smsBody: String?, val merchant: String?, val type: TxnType, val amountMinor: Long)

data class RuleOutcome(
    val rule: CustomRule,
    val category: String?,
    val merchant: String?,
    val exclude: Boolean,
    val ignore: Boolean,
    val taxSection: TaxSection?,
)

object CustomRules {
    fun isValid(rule: CustomRule): Boolean {
        if (rule.pattern.isBlank()) return false
        if (rule.isRegex) runCatching { Regex(rule.pattern) }.getOrElse { return false }
        return rule.setCategory != null || rule.renameMerchant != null || rule.exclude || rule.ignore || rule.taxSection != null
    }

    fun matches(rule: CustomRule, input: RuleInput): Boolean {
        if (!rule.enabled || rule.pattern.isBlank()) return false
        if (rule.onlyType != null && rule.onlyType != input.type) return false
        if (rule.minAmountMinor != null && input.amountMinor < rule.minAmountMinor) return false
        if (rule.maxAmountMinor != null && input.amountMinor > rule.maxAmountMinor) return false
        val texts = when (rule.field) {
            RuleField.ANY -> listOfNotNull(input.merchant, input.smsBody, input.sender)
            RuleField.MERCHANT -> listOfNotNull(input.merchant)
            RuleField.SMS_TEXT -> listOfNotNull(input.smsBody)
            RuleField.SENDER -> listOfNotNull(input.sender)
        }
        if (rule.isRegex) {
            val re = runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return false
            return texts.any { re.containsMatchIn(it) }
        }
        val p = rule.pattern.trim().lowercase()
        return texts.any { it.lowercase().contains(p) }
    }

    fun apply(rules: List<CustomRule>, input: RuleInput): RuleOutcome? {
        val rule = rules.sortedBy { it.priority }.firstOrNull { matches(it, input) } ?: return null
        return RuleOutcome(rule, rule.setCategory, rule.renameMerchant ?: input.merchant, rule.exclude, rule.ignore, rule.taxSection)
    }
}
