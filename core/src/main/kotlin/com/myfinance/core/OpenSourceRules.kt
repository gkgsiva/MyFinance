package com.myfinance.core

/**
 * Built-in merchant keyword rules taken from open-source Indian finance apps, added to the app's own
 * defaults. They are the lowest priority: your own rules, payee names and category corrections always win.
 *
 * Sources (both allow reuse with attribution):
 *  - kharcha by Akash Priyadarshi, Apache-2.0, github.com/AkashPriyadarshii/kharcha (android-app Seed.kt SEED_RULES)
 *  - Card-Statement-Analyser (stmtforge) by Madhav, MIT, github.com/madhav921/Card-Statement-Analyser
 *    (config_template.yaml categories)
 *
 * Patterns that were too loose for SMS text ("more", "reliance", "upi", "salary", "vi", wallet names) were
 * left out; short ones are matched as whole words by [Categorizer]. expense-analyser's pattern list is
 * AGPL-3.0 and was not copied.
 */
object OpenSourceRules {
    val merchants: Map<String, List<String>> = mapOf(
        DefaultCategories.FOOD to listOf(
            "dominos", "pizza hut", "burger king", "barbeque", "subway", "chai point", "faasos", "behrouz biryani",
            "ovenstory", "freshmenu", "box8", "uber eats",
        ),
        DefaultCategories.GROCERIES to listOf(
            "jiomart", "nature basket", "milkbasket", "country delight", "dunzo", "licious", "fresh to home", "freshtohome", "otipy",
        ),
        DefaultCategories.TRANSPORT to listOf("yulu", "zoomcar", "metro parking", "toll"),
        DefaultCategories.FUEL to listOf("hp petrol", "shell fuel"),
        DefaultCategories.SHOPPING to listOf(
            "zudio", "vijay sales", "amzn", "ajio",
        ),
        DefaultCategories.BILLS to listOf(
            "adani electricity", "mahadiscom", "mgl", "indane", "bharat gas", "act broadband", "excitel", "tatasky", "tatapower", "utilities",
        ),
        DefaultCategories.ENTERTAINMENT to listOf("sony liv", "youtube premium", "jiosaavn"),
        DefaultCategories.HEALTH to listOf(
            "apollo pharmacy", "tata 1mg", "dr lal pathlabs", "lal pathlabs", "thyrocare", "metropolis labs", "healthcare",
        ),
        DefaultCategories.TRAVEL to listOf("easemytrip", "akasa air", "irctc", "redbus"),
        DefaultCategories.EDUCATION to listOf("physicswallah", "byjus", "vedantu", "aakash institute"),
    )
}
