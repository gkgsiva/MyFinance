package com.myfinance.core

/** How a payment was made, worked out from the SMS wording. */
enum class PaymentMode(val label: String) {
    UPI("UPI"), CARD("Card"), CASH("Cash"), NETBANKING("Net banking"), OTHER("Other");

    companion object {
        private val upi = Regex("""\bupi\b|\bvpa\b|@[a-z]{2,}\b|\bgpay\b|\bphonepe\b|\bpaytm\b|\bbhim\b""", RegexOption.IGNORE_CASE)
        private val card = Regex("""\bcard\b|\bpos\b|\bswipe""", RegexOption.IGNORE_CASE)
        private val cash = Regex("""\batm\b|cash\s+(?:withdrawal|wdl)|withdrawn\s+at|\bcash\b""", RegexOption.IGNORE_CASE)
        private val netbanking = Regex("""\bneft\b|\bimps\b|\brtgs\b|net\s*banking|\bnetbanking\b|\bnach\b|\bach\b|\becs\b|auto\s*debit|standing\s+instruction|\bbill\s*desk\b|\bbillpay\b""", RegexOption.IGNORE_CASE)

        /**
         * [text] is the SMS body when there is one; for entries typed in by hand there is no SMS,
         * so they count as cash unless the category says otherwise.
         */
        fun detect(text: String?, manual: Boolean, category: String? = null): PaymentMode {
            if (text.isNullOrBlank()) {
                return when {
                    category == DefaultCategories.CASH -> CASH
                    manual -> CASH
                    else -> OTHER
                }
            }
            return when {
                cash.containsMatchIn(text) && !upi.containsMatchIn(text) -> CASH
                upi.containsMatchIn(text) -> UPI
                card.containsMatchIn(text) -> CARD
                netbanking.containsMatchIn(text) -> NETBANKING
                else -> OTHER
            }
        }

        fun fromName(name: String?): PaymentMode? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
