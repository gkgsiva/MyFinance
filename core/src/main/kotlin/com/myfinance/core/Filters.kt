package com.myfinance.core

/** Search and filter options for the transaction list. Null / blank means "any". */
data class TxnFilter(
    val text: String = "",
    /** Inclusive epoch-millis range. */
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val category: String? = null,
    val owner: String? = null,
    val account: String? = null,
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val type: TxnType? = null,
    val mode: PaymentMode? = null,
    /** Only entries with a bill photo. */
    val withPhoto: Boolean = false,
    /** Only entries whose note contains this text (any note when "*"). */
    val noteText: String = "",
    /** Only entries whose nickname contains this text (any nickname when "*"). */
    val nicknameText: String = "",
) {
    /** True when anything beyond the text box and category is set (shown as a badge on the filter button). */
    val hasAdvanced: Boolean
        get() = fromMillis != null || toMillis != null || owner != null || account != null ||
            minAmountMinor != null || maxAmountMinor != null || type != null || mode != null || withPhoto || noteText.isNotBlank() || nicknameText.isNotBlank()

    val hasDateRange: Boolean get() = fromMillis != null || toMillis != null

    fun matches(t: TxnLite, note: String? = null, hasPhoto: Boolean = false, nickname: String? = null): Boolean {
        if (withPhoto && !hasPhoto) return false
        val nickNeedle = nicknameText.trim()
        if (nickNeedle == "*" && nickname.isNullOrBlank()) return false
        if (nickNeedle.isNotEmpty() && nickNeedle != "*" && nickname?.contains(nickNeedle, ignoreCase = true) != true) return false
        val noteNeedle = noteText.trim()
        if (noteNeedle == "*" && note.isNullOrBlank()) return false
        if (noteNeedle.isNotEmpty() && noteNeedle != "*" && note?.contains(noteNeedle, ignoreCase = true) != true) return false
        if (fromMillis != null && t.timestamp < fromMillis) return false
        if (toMillis != null && t.timestamp > toMillis) return false
        if (category != null && t.category != category) return false
        if (owner != null && t.owner != owner) return false
        if (account != null && t.account != account) return false
        if (minAmountMinor != null && t.amountMinor < minAmountMinor) return false
        if (maxAmountMinor != null && t.amountMinor > maxAmountMinor) return false
        if (type != null && t.type != type) return false
        if (mode != null && t.mode != mode) return false
        val needle = text.trim().lowercase()
        if (needle.isNotEmpty()) {
            val amountText = java.math.BigDecimal.valueOf(t.amountMinor, 2).stripTrailingZeros().toPlainString()
            val hay = listOfNotNull(t.merchant, nickname, note, t.category, t.account, t.owner, amountText, formatInr(t.amountMinor))
            if (hay.none { it.lowercase().contains(needle) }) return false
        }
        return true
    }
}
