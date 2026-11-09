package com.myfinance.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/*
 * "Ask in plain words": reads a question such as "how much on Swiggy last year?" with simple rules
 * and answers it from the transactions on the phone. No internet, no AI service.
 */

enum class AskMetric { SPEND, INCOME, NET, COUNT, AVERAGE, MONTHLY_AVERAGE, BIGGEST, SMALLEST, TOP }

enum class AskSubjectKind { MERCHANT, CATEGORY, LABEL }

/** What the question is about. [term] is the payee word(s), category name or label. */
data class AskSubject(val kind: AskSubjectKind, val term: String, val display: String)

data class AskPeriod(val from: LocalDate, val to: LocalDate, val label: String)

data class AskQuery(
    val metric: AskMetric,
    val subject: AskSubject?,
    /** Null = all time. */
    val period: AskPeriod?,
    /** A family member's name, when the question names one. */
    val owner: String?,
    /** Spend questions look at debits, income questions at credits. */
    val type: TxnType?,
)

data class AskTop(val name: String, val totalMinor: Long, val count: Int)

data class AskAnswer(
    val query: AskQuery,
    /** The answer in one line, e.g. "₹12,340 on Swiggy last year". */
    val headline: String,
    /** How the question was read, so a wrong reading is easy to spot. */
    val understood: String,
    val totalMinor: Long,
    val count: Int,
    val matches: List<TxnLite>,
    val top: List<AskTop> = emptyList(),
    /** Extra line, e.g. refunds received from the same payee. */
    val note: String? = null,
)

/** Names the question can refer to. */
data class AskVocabulary(
    val merchants: Collection<String>,
    val categories: Collection<String>,
    val labels: Collection<String>,
    val owners: Collection<String> = emptyList(),
)

object Ask {

    val examples = listOf(
        "How much on Swiggy last year?",
        "Food spend this month",
        "Biggest payment in March",
        "Salary in 2025",
        "How many times Uber in the last 3 months?",
        "Where did I spend most last month?",
        "Average grocery spend per month this year",
        "Income this financial year",
    )

    private val day = DateTimeFormatter.ofPattern("d MMM yyyy")

    private val monthWords = mapOf(
        "jan" to Month.JANUARY, "january" to Month.JANUARY, "feb" to Month.FEBRUARY, "february" to Month.FEBRUARY,
        "mar" to Month.MARCH, "march" to Month.MARCH, "apr" to Month.APRIL, "april" to Month.APRIL,
        "may" to Month.MAY, "jun" to Month.JUNE, "june" to Month.JUNE, "jul" to Month.JULY, "july" to Month.JULY,
        "aug" to Month.AUGUST, "august" to Month.AUGUST, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
        "september" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "october" to Month.OCTOBER,
        "nov" to Month.NOVEMBER, "november" to Month.NOVEMBER, "dec" to Month.DECEMBER, "december" to Month.DECEMBER,
    )

    /** Everyday words for the built-in categories. */
    private val categoryWords: Map<String, String> = mapOf(
        "food" to DefaultCategories.FOOD, "dining" to DefaultCategories.FOOD, "eating" to DefaultCategories.FOOD,
        "restaurant" to DefaultCategories.FOOD, "restaurants" to DefaultCategories.FOOD, "eating out" to DefaultCategories.FOOD,
        "grocery" to DefaultCategories.GROCERIES, "groceries" to DefaultCategories.GROCERIES, "vegetables" to DefaultCategories.GROCERIES,
        "shopping" to DefaultCategories.SHOPPING, "clothes" to DefaultCategories.SHOPPING,
        "transport" to DefaultCategories.TRANSPORT, "cab" to DefaultCategories.TRANSPORT, "cabs" to DefaultCategories.TRANSPORT,
        "taxi" to DefaultCategories.TRANSPORT, "auto" to DefaultCategories.TRANSPORT, "metro" to DefaultCategories.TRANSPORT,
        "fuel" to DefaultCategories.FUEL, "petrol" to DefaultCategories.FUEL, "diesel" to DefaultCategories.FUEL,
        "bills" to DefaultCategories.BILLS, "utilities" to DefaultCategories.BILLS, "electricity" to DefaultCategories.BILLS,
        "recharge" to DefaultCategories.BILLS, "mobile bill" to DefaultCategories.BILLS,
        "entertainment" to DefaultCategories.ENTERTAINMENT, "movies" to DefaultCategories.ENTERTAINMENT, "movie" to DefaultCategories.ENTERTAINMENT,
        "health" to DefaultCategories.HEALTH, "medical" to DefaultCategories.HEALTH, "medicine" to DefaultCategories.HEALTH,
        "medicines" to DefaultCategories.HEALTH, "doctor" to DefaultCategories.HEALTH, "hospital" to DefaultCategories.HEALTH,
        "pharmacy" to DefaultCategories.HEALTH,
        "travel" to DefaultCategories.TRAVEL, "trips" to DefaultCategories.TRAVEL, "flights" to DefaultCategories.TRAVEL,
        "trains" to DefaultCategories.TRAVEL, "hotels" to DefaultCategories.TRAVEL,
        "rent" to DefaultCategories.RENT, "housing" to DefaultCategories.RENT,
        "education" to DefaultCategories.EDUCATION, "school" to DefaultCategories.EDUCATION, "fees" to DefaultCategories.EDUCATION,
        "tuition" to DefaultCategories.EDUCATION,
        "investments" to DefaultCategories.INVESTMENTS, "investment" to DefaultCategories.INVESTMENTS, "invested" to DefaultCategories.INVESTMENTS,
        "mutual funds" to DefaultCategories.INVESTMENTS, "sip" to DefaultCategories.INVESTMENTS, "sips" to DefaultCategories.INVESTMENTS,
        "emi" to DefaultCategories.EMI, "emis" to DefaultCategories.EMI, "loan" to DefaultCategories.EMI, "loans" to DefaultCategories.EMI,
        "cash" to DefaultCategories.CASH, "atm" to DefaultCategories.CASH, "withdrawals" to DefaultCategories.CASH,
        "transfers" to DefaultCategories.TRANSFERS,
        "salary" to DefaultCategories.SALARY, "salaries" to DefaultCategories.SALARY,
    )

    private val stopWords = setOf(
        "how", "much", "many", "did", "do", "does", "i", "we", "me", "my", "our", "us", "the", "a", "an", "on", "at", "in",
        "for", "to", "from", "of", "spend", "spent", "spending", "spends", "pay", "paid", "paying", "payment", "payments",
        "money", "total", "what", "was", "were", "is", "are", "has", "have", "had", "been", "times", "time", "often",
        "and", "with", "by", "so", "far", "till", "until", "upto", "up", "now", "please", "show", "tell", "give", "all",
        "this", "last", "past", "previous", "year", "years", "month", "months", "week", "weeks", "day", "days", "today",
        "yesterday", "since", "between", "during", "ago", "fy", "financial", "calendar", "where", "which", "who", "most",
        "biggest", "largest", "highest", "smallest", "lowest", "least", "top", "average", "avg", "per", "each", "every",
        "count", "number", "expense", "expenses", "earn", "earned", "earning", "earnings", "income", "received", "receive",
        "got", "get", "credited", "credit", "credits", "debit", "debits", "debited", "net", "saved", "save", "savings",
        "overall", "whole", "entire", "about", "around", "approx", "on", "ever", "any", "it", "there", "than",
        "single", "one", "transaction", "transactions", "bought", "buy", "buying", "purchase", "purchases", "orders", "order",
        "max", "maximum", "min", "minimum", "went", "go", "used", "use", "so", "far", "much", "be", "can", "you",
    )

    fun ask(
        text: String,
        today: LocalDate,
        txns: List<TxnLite>,
        labelsById: Map<Long, List<String>> = emptyMap(),
        owners: Collection<String> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): AskAnswer {
        val vocab = AskVocabulary(
            merchants = txns.mapNotNull { it.merchant }.distinct(),
            categories = txns.map { it.category }.distinct(),
            labels = labelsById.values.flatten().distinct(),
            owners = owners,
        )
        return answer(parse(text, today, vocab), txns, labelsById, zone)
    }

    // ---- Reading the question ----

    fun parse(text: String, today: LocalDate, vocab: AskVocabulary): AskQuery {
        val q = " " + text.lowercase().replace(Regex("""[^a-z0-9₹\s-]"""), " ").replace(Regex("""\s+"""), " ").trim() + " "
        fun has(vararg phrases: String) = phrases.any { q.contains(" $it ") }

        val period = parsePeriod(q, today)
        val owner = vocab.owners.firstOrNull { o -> o.isNotBlank() && o.lowercase() !in setOf("me", "i") && q.contains(" ${o.lowercase()} ") }

        val subject = findSubject(q, vocab, owner)

        val incomeWords = has("earn", "earned", "earning", "earnings", "income", "received", "receive", "got", "credited", "credits", "came in")
        val salary = subject?.kind == AskSubjectKind.CATEGORY && subject.term == DefaultCategories.SALARY
        val metric = when {
            has("how many", "how often", "count", "number of", "times") -> AskMetric.COUNT
            has("biggest", "largest", "highest", "most expensive", "max", "maximum", "costliest") -> AskMetric.BIGGEST
            has("smallest", "lowest", "least", "cheapest", "min", "minimum") -> AskMetric.SMALLEST
            (has("average", "avg") && has("per month", "a month", "monthly", "each month", "every month")) -> AskMetric.MONTHLY_AVERAGE
            has("average", "avg") -> AskMetric.AVERAGE
            has("per month", "monthly") && subject != null -> AskMetric.MONTHLY_AVERAGE
            has("where", "top", "which", "most on", "spend most", "spent most", "spending most", "breakdown") -> AskMetric.TOP
            has("saved", "save", "savings", "net", "left over", "leftover") -> AskMetric.NET
            incomeWords || salary -> AskMetric.INCOME
            else -> AskMetric.SPEND
        }
        val type = when {
            metric == AskMetric.NET -> null
            metric == AskMetric.INCOME || incomeWords || salary -> TxnType.CREDIT
            else -> TxnType.DEBIT
        }
        return AskQuery(metric, subject, period, owner, type)
    }

    private fun findSubject(q: String, vocab: AskVocabulary, owner: String?): AskSubject? {
        // 1. Labels and categories named exactly ("Vacation", "Food & Dining").
        fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()
        vocab.labels.sortedByDescending { it.length }.firstOrNull { l -> norm(l).length >= 2 && q.contains(" ${norm(l)} ") }
            ?.let { return AskSubject(AskSubjectKind.LABEL, it, it) }
        vocab.categories.sortedByDescending { it.length }.firstOrNull { c -> norm(c).length >= 3 && q.contains(" ${norm(c)} ") }
            ?.let { return AskSubject(AskSubjectKind.CATEGORY, it, it) }

        // 2. Payees named in the question ("swiggy", "big basket").
        val merchantWords = vocab.merchants.flatMap { m -> Categorizer.merchantKey(m).split(' ') }
            .filter { it.length >= 3 && it.any(Char::isLetter) && it !in stopWords }
            .toSet()
        val words = q.trim().split(' ').filter { it.isNotEmpty() }
        val ownerWord = owner?.lowercase()
        val candidates = words.filter { it.length >= 3 && it !in stopWords && it != ownerWord && it !in monthWords && it.toIntOrNull() == null }
        // Two-word payees first ("big basket"), then single words.
        for (i in 0 until words.size - 1) {
            val pair = words[i] + words[i + 1]
            if (words[i] !in stopWords && words[i + 1] !in stopWords && pair.length >= 5 && pair in merchantWords) {
                return AskSubject(AskSubjectKind.MERCHANT, pair, displayFor(pair, vocab.merchants))
            }
        }

        // 3. Everyday words for categories ("petrol", "medicines").
        val phraseHit = categoryWords.entries.sortedByDescending { it.key.length }.firstOrNull { q.contains(" ${it.key} ") }
        val exactMerchant = candidates.firstOrNull { it in merchantWords && it !in categoryWords }
        if (exactMerchant != null) return AskSubject(AskSubjectKind.MERCHANT, exactMerchant, displayFor(exactMerchant, vocab.merchants))
        if (phraseHit != null) {
            val cat = vocab.categories.firstOrNull { it.equals(phraseHit.value, ignoreCase = true) } ?: phraseHit.value
            return AskSubject(AskSubjectKind.CATEGORY, cat, cat)
        }

        // 4. Spelling slips: "swigy", "zomatto". Labels and categories by their first word too.
        for (w in candidates) {
            if (w.length < 4) continue
            val limit = if (w.length >= 7) 2 else 1
            vocab.labels.firstOrNull { l -> norm(l).split(' ').any { it.length >= 4 && editDistance(it, w) <= limit } }
                ?.let { return AskSubject(AskSubjectKind.LABEL, it, it) }
            merchantWords.filter { it.length >= 4 && editDistance(it, w) <= limit }
                .minByOrNull { editDistance(it, w) }
                ?.let { return AskSubject(AskSubjectKind.MERCHANT, it, displayFor(it, vocab.merchants)) }
            vocab.categories.firstOrNull { c -> norm(c).split(' ').any { it.length >= 4 && editDistance(it, w) <= limit } }
                ?.let { return AskSubject(AskSubjectKind.CATEGORY, it, it) }
        }
        return null
    }

    /** "swiggy" -> "Swiggy", using how the payee is written in the transactions. */
    private fun displayFor(term: String, merchants: Collection<String>): String {
        val hit = merchants.firstOrNull { m -> Categorizer.merchantKey(m).split(' ').contains(term) }
            ?: merchants.firstOrNull { m -> Categorizer.merchantKey(m).replace(" ", "").contains(term) }
        val word = hit?.split(Regex("""\s+"""))?.firstOrNull { Categorizer.merchantKey(it).replace(" ", "") == term }
        return (word ?: term).lowercase().replaceFirstChar { it.uppercase() }
    }

    fun parsePeriod(q: String, today: LocalDate): AskPeriod? {
        val s = " " + q.trim() + " "
        fun has(p: String) = s.contains(" $p ")
        val monday = today.with(DayOfWeek.MONDAY)
        val fyNow = Dates.financialYearStart(today)

        Regex(""" (?:last|past|previous) (\d{1,3}) (day|days|week|weeks|month|months|year|years) """).find(s)?.let { m ->
            val n = m.groupValues[1].toLong().coerceAtLeast(1)
            val unit = m.groupValues[2]
            val from = when {
                unit.startsWith("day") -> today.minusDays(n - 1)
                unit.startsWith("week") -> today.minusWeeks(n).plusDays(1)
                unit.startsWith("month") -> today.minusMonths(n).plusDays(1)
                else -> today.minusYears(n).plusDays(1)
            }
            return AskPeriod(from, today, "in the last $n ${if (n == 1L) unit.removeSuffix("s") else unit.let { if (it.endsWith("s")) it else it + "s" }}")
        }
        // FY 2024-25, FY25, FY 2025
        Regex(""" fy ?(\d{4})(?: ?- ?(\d{2,4}))? """).find(s)?.let { m ->
            val a = m.groupValues[1].toInt()
            val start = if (m.groupValues[2].isNotEmpty()) a else a - 1
            return fy(start)
        }
        Regex(""" fy ?(\d{2}) """).find(s)?.let { m -> return fy(2000 + m.groupValues[1].toInt() - 1) }
        when {
            has("today") -> return AskPeriod(today, today, "today")
            has("yesterday") -> return today.minusDays(1).let { AskPeriod(it, it, "yesterday") }
            has("this week") -> return AskPeriod(monday, today, "this week")
            has("last week") || has("previous week") -> return AskPeriod(monday.minusWeeks(1), monday.minusDays(1), "last week")
            has("this month") -> return AskPeriod(today.withDayOfMonth(1), today, "this month")
            has("last month") || has("previous month") -> today.minusMonths(1).let {
                return AskPeriod(it.withDayOfMonth(1), it.withDayOfMonth(it.lengthOfMonth()), "last month")
            }
            has("this financial year") || has("this fy") || has("current fy") || has("current financial year") ->
                return fy(fyNow).copy(to = today, label = "this financial year")
            has("last financial year") || has("last fy") || has("previous fy") || has("previous financial year") ->
                return fy(fyNow - 1).copy(label = "last financial year")
            has("this year") || has("current year") -> return AskPeriod(today.withDayOfYear(1), today, "this year")
            has("last year") || has("previous year") -> today.minusYears(1).let {
                return AskPeriod(it.withDayOfYear(1), it.withDayOfYear(it.lengthOfYear()), "last year")
            }
        }
        val year = Regex(""" (20\d{2}) """).find(s)?.groupValues?.get(1)?.toInt()
        val month = s.trim().split(' ').firstNotNullOfOrNull { monthWords[it] }
            // "may" is also a verb; take it only as a month when nothing else reads as one and it isn't "may i".
            ?.takeUnless { it == Month.MAY && !Regex(""" (in|of|during|since) may | may (20\d{2}) """).containsMatchIn(s) }
        val since = has("since")
        if (month != null) {
            val y = year ?: if (month.value > today.monthValue) today.year - 1 else today.year
            val first = LocalDate.of(y, month, 1)
            if (since) return AskPeriod(first, today, "since ${first.format(DateTimeFormatter.ofPattern("MMM yyyy"))}")
            val last = first.withDayOfMonth(first.lengthOfMonth()).let { if (it.isAfter(today)) today else it }
            return AskPeriod(first, last, "in ${first.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}")
        }
        if (year != null) {
            val first = LocalDate.of(year, 1, 1)
            if (since) return AskPeriod(first, today, "since $year")
            val last = LocalDate.of(year, 12, 31).let { if (it.isAfter(today)) today else it }
            return AskPeriod(first, last, "in $year")
        }
        return null
    }

    private fun fy(start: Int) = AskPeriod(LocalDate.of(start, 4, 1), LocalDate.of(start + 1, 3, 31), "in ${Dates.financialYearLabel(start)}")

    // ---- Answering ----

    fun matchesSubject(t: TxnLite, subject: AskSubject?, labelsById: Map<Long, List<String>>): Boolean {
        if (subject == null) return true
        return when (subject.kind) {
            AskSubjectKind.CATEGORY -> t.category.equals(subject.term, ignoreCase = true)
            AskSubjectKind.LABEL -> labelsById[t.id].orEmpty().any { it.equals(subject.term, ignoreCase = true) }
            AskSubjectKind.MERCHANT -> t.merchant?.let { m ->
                val key = Categorizer.merchantKey(m)
                key.split(' ').contains(subject.term) || key.replace(" ", "").contains(subject.term)
            } == true
        }
    }

    fun answer(query: AskQuery, txns: List<TxnLite>, labelsById: Map<Long, List<String>>, zone: ZoneId = ZoneId.systemDefault()): AskAnswer {
        val inScope = txns.filter { t ->
            val d = Dates.localDate(t.timestamp, zone)
            val p = query.period
            (p == null || (!d.isBefore(p.from) && !d.isAfter(p.to))) &&
                (query.owner == null || t.owner.equals(query.owner, ignoreCase = true)) &&
                matchesSubject(t, query.subject, labelsById)
        }
        val matches = inScope.filter { query.type == null || it.type == query.type }.sortedByDescending { it.timestamp }
        val total = matches.sumOf { it.amountMinor }
        val count = matches.size

        val subj = query.subject
        val what = when {
            subj == null -> null
            subj.kind == AskSubjectKind.LABEL -> "label \"${subj.display}\""
            else -> subj.display
        }
        val on = what?.let { if (query.type == TxnType.CREDIT) " from $it" else " on $it" }.orEmpty()
        val whenText = query.period?.label ?: "in all your transactions"
        val who = query.owner?.let { " by $it" }.orEmpty()
        val range = query.period?.let { "${it.from.format(day)} to ${it.to.format(day)}" } ?: "all dates"
        val kindWord = if (query.type == TxnType.CREDIT) "Money received" else "Spending"
        val term = query.subject?.term.orEmpty()
        val subjectText = when (query.subject?.kind) {
            AskSubjectKind.MERCHANT -> "payee \"$term\""
            AskSubjectKind.CATEGORY -> "category $term"
            AskSubjectKind.LABEL -> "label $term"
            null -> "everything"
        }
        val understood = "$kindWord · $subjectText${query.owner?.let { " · $it" }.orEmpty()} · $range"

        fun plural(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"
        val payWord = if (query.type == TxnType.CREDIT) "credit" else "payment"

        if (count == 0 && query.metric != AskMetric.NET) {
            return AskAnswer(query, "Nothing found$on $whenText$who", understood, 0, 0, emptyList())
        }

        return when (query.metric) {
            AskMetric.SPEND, AskMetric.INCOME -> {
                // Refunds from the same payee, so "Swiggy last year" is honest about money that came back.
                val refunds = if (query.type == TxnType.DEBIT && query.subject != null) inScope.filter { it.type == TxnType.CREDIT } else emptyList()
                val note = if (refunds.isNotEmpty()) "${formatInr(refunds.sumOf { it.amountMinor })} came back in ${plural(refunds.size, "refund")}, " +
                    "so the net is ${formatInr(total - refunds.sumOf { it.amountMinor })}." else null
                AskAnswer(query, "${formatInr(total)}$on $whenText$who", understood + " · ${plural(count, payWord)}", total, count, matches, note = note)
            }
            AskMetric.COUNT -> AskAnswer(
                query, "${plural(count, payWord)}$on $whenText$who", understood + " · ${formatInr(total)} in all", total, count, matches,
            )
            AskMetric.AVERAGE -> {
                val avg = total / count
                AskAnswer(query, "${formatInr(avg)} per $payWord$on $whenText$who", understood + " · ${plural(count, payWord)}, ${formatInr(total)} in all", total, count, matches)
            }
            AskMetric.MONTHLY_AVERAGE -> {
                val from = query.period?.from ?: matches.minOf { Dates.localDate(it.timestamp, zone) }
                val to = query.period?.to ?: matches.maxOf { Dates.localDate(it.timestamp, zone) }
                val months = (ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1)) + 1).coerceAtLeast(1)
                val avg = total / months
                AskAnswer(query, "${formatInr(avg)} a month$on $whenText$who", understood + " · ${formatInr(total)} over ${plural(months.toInt(), "month")}", total, count, matches)
            }
            AskMetric.BIGGEST, AskMetric.SMALLEST -> {
                val pick = if (query.metric == AskMetric.BIGGEST) matches.maxBy { it.amountMinor } else matches.minBy { it.amountMinor }
                val where = pick.merchant ?: pick.category
                val date = Dates.localDate(pick.timestamp, zone).format(day)
                val adj = if (query.metric == AskMetric.BIGGEST) "Biggest" else "Smallest"
                AskAnswer(query, "$adj: ${formatInr(pick.amountMinor)} at $where on $date", understood + " · out of ${plural(count, payWord)}", pick.amountMinor, 1, listOf(pick) + matches.filter { it !== pick }.sortedByDescending { it.amountMinor }.let {
                    if (query.metric == AskMetric.BIGGEST) it else it.reversed()
                }.take(19))
            }
            AskMetric.TOP -> {
                val byMerchant = query.subject?.kind == AskSubjectKind.CATEGORY || query.subject?.kind == AskSubjectKind.LABEL
                val groups = matches.groupBy { if (byMerchant) (it.merchant ?: "Unknown") else it.category }
                    .map { (k, v) -> AskTop(k, v.sumOf { it.amountMinor }, v.size) }
                    .sortedByDescending { it.totalMinor }
                val first = groups.first()
                AskAnswer(
                    query, "Most$on $whenText$who: ${first.name}, ${formatInr(first.totalMinor)}",
                    understood + " · ${formatInr(total)} in all", total, count, matches, top = groups.take(10),
                )
            }
            AskMetric.NET -> {
                val inc = inScope.filter { it.type == TxnType.CREDIT }.sumOf { it.amountMinor }
                val out = inScope.filter { it.type == TxnType.DEBIT }.sumOf { it.amountMinor }
                val net = inc - out
                val head = if (net >= 0) "Saved ${formatInr(net)} $whenText$who" else "Spent ${formatInr(-net)} more than came in $whenText$who"
                AskAnswer(
                    query, head, "Money in minus money out · $range",
                    net, inScope.size, inScope.sortedByDescending { it.timestamp },
                    note = "In ${formatInr(inc)}, out ${formatInr(out)}.",
                )
            }
        }
    }

    /** Levenshtein distance, for spelling slips in payee names. */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
