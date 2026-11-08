package com.myfinance.core

/** Built-in categories. Users can add their own on top of these. */
object DefaultCategories {
    const val FOOD = "Food & Dining"
    const val GROCERIES = "Groceries"
    const val SHOPPING = "Shopping"
    const val TRANSPORT = "Transport"
    const val FUEL = "Fuel"
    const val BILLS = "Bills & Utilities"
    const val ENTERTAINMENT = "Entertainment"
    const val HEALTH = "Health"
    const val TRAVEL = "Travel"
    const val RENT = "Rent & Housing"
    const val EDUCATION = "Education"
    const val INVESTMENTS = "Investments"
    const val EMI = "Loans & EMI"
    const val CASH = "Cash Withdrawal"
    const val TRANSFERS = "Transfers"
    const val SALARY = "Salary"
    const val INCOME = "Other Income"
    const val OTHER = "Uncategorized"

    val all = listOf(
        FOOD, GROCERIES, SHOPPING, TRANSPORT, FUEL, BILLS, ENTERTAINMENT, HEALTH, TRAVEL, RENT,
        EDUCATION, INVESTMENTS, EMI, CASH, TRANSFERS, SALARY, INCOME, OTHER,
    )
}

/**
 * Assigns a category to a transaction using, in order:
 *  1. the user's own merchant -> category corrections ([userRules]),
 *  2. built-in keyword rules matched against the merchant and the SMS text.
 */
class Categorizer(private val userRules: Map<String, String> = emptyMap()) {

    fun categorize(merchant: String?, type: TxnType, smsBody: String? = null): String {
        val key = merchant?.let { merchantKey(it) }
        if (key != null) userRules[key]?.let { return it }

        val haystack = listOfNotNull(merchant, smsBody).joinToString(" ").lowercase()

        if (type == TxnType.CREDIT) {
            if (Regex("""\b(salary|sal\b|payroll)""").containsMatchIn(haystack)) return DefaultCategories.SALARY
            if (Regex("""\b(refund|reversal|reversed|cashback)""").containsMatchIn(haystack)) return DefaultCategories.INCOME
            if (Regex("""\b(interest|dividend)""").containsMatchIn(haystack)) return DefaultCategories.INCOME
        }

        for ((category, words) in keywordRules) {
            if (words.any { containsWord(haystack, it) }) return category
        }

        if (Regex("""\b(atm|cash withdrawal|cash wdl|withdrawn at)""").containsMatchIn(haystack)) return DefaultCategories.CASH
        if (Regex("""\b(neft|imps|rtgs)\b""").containsMatchIn(haystack)) return DefaultCategories.TRANSFERS

        return if (type == TxnType.CREDIT) DefaultCategories.INCOME else DefaultCategories.OTHER
    }

    private fun containsWord(haystack: String, word: String): Boolean {
        // Short keywords ("ola", "emi", "gas") must be whole words; longer ones may prefix ("swiggy" in "swiggyinstamart").
        val tail = if (word.trim().length <= 4) "(?![a-z])" else ""
        return Regex("""(?<![a-z])${Regex.escape(word)}$tail""").containsMatchIn(haystack)
    }

    companion object {
        /** Normalises a merchant name so "SWIGGY ", "Swiggy" and "swiggy" share one rule. */
        fun merchantKey(merchant: String): String =
            merchant.lowercase().replace(Regex("""[^a-z0-9@]+"""), " ").trim()

        val keywordRules: List<Pair<String, List<String>>> = listOf(
            DefaultCategories.FOOD to listOf(
                "swiggy", "zomato", "eatsure", "domino", "pizza", "mcdonald", "kfc", "burger", "starbucks",
                "cafe", "restaurant", "hotel saravana", "chaayos", "haldiram", "biryani", "bakery", "eatclub",
            ),
            DefaultCategories.GROCERIES to listOf(
                "bigbasket", "blinkit", "zepto", "instamart", "dmart", "grofers", "jiomart", "more retail",
                "reliance fresh", "nature basket", "spencer", "supermarket", "kirana", "milkbasket", "country delight",
            ),
            DefaultCategories.FUEL to listOf(
                "petrol", "fuel", "hpcl", "bpcl", "iocl", "indian oil", "bharat petroleum", "hp pay", "shell", "nayara",
            ),
            DefaultCategories.TRANSPORT to listOf(
                "uber", "ola", "rapido", "metro", "irctc", "redbus", "fastag", "parking", "namma yatri", "blusmart",
            ),
            DefaultCategories.TRAVEL to listOf(
                "makemytrip", "goibibo", "cleartrip", "indigo", "air india", "vistara", "akasa", "spicejet",
                "oyo", "airbnb", "yatra", "ixigo", "booking.com", "agoda",
            ),
            DefaultCategories.SHOPPING to listOf(
                "amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "croma", "reliance digital",
                "decathlon", "ikea", "lifestyle", "westside", "pantaloons", "zara", "h&m", "uniqlo", "lenskart",
            ),
            DefaultCategories.BILLS to listOf(
                "electricity", "bescom", "tneb", "msedcl", "tata power", "adani", "airtel", "jio", "vodafone",
                " vi ", "bsnl", "act fibernet", "broadband", "dth", "tata play", "gas", "water bill", "recharge",
                "bill payment", "billdesk", "postpaid", "insurance", "lic",
            ),
            DefaultCategories.ENTERTAINMENT to listOf(
                "netflix", "hotstar", "prime video", "spotify", "bookmyshow", "pvr", "inox", "youtube",
                "sonyliv", "zee5", "apple.com", "google play", "steam", "playstation",
            ),
            DefaultCategories.HEALTH to listOf(
                "pharmacy", "apollo", "medplus", "pharmeasy", "netmeds", "1mg", "hospital", "clinic", "diagnostic",
                "practo", "cult.fit", "cultfit", "gym", "dental",
            ),
            DefaultCategories.RENT to listOf("rent", "nobroker", "society", "maintenance", "housing"),
            DefaultCategories.EDUCATION to listOf("school", "college", "university", "udemy", "coursera", "byju", "unacademy", "tuition", "fees"),
            DefaultCategories.INVESTMENTS to listOf(
                "zerodha", "groww", "upstox", "kuvera", "coin by", "mutual fund", "sip", "nps", "ppf", "smallcase",
                "indmoney", "angel one", "icici direct", "hdfc securities",
            ),
            DefaultCategories.EMI to listOf("emi", "loan", "bajaj finance", "home credit", "credit card bill", "cc payment"),
        )
    }
}
