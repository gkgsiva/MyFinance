package com.myfinance.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/* Subscription audit: price hikes, duplicates, double charges and ones you don't use. */

enum class SubKind(val label: String) {
    VIDEO("Video streaming"), MUSIC("Music"), CLOUD("Cloud storage"), NEWS("News & reading"), FITNESS("Fitness"),
    SOFTWARE("Apps & software"), FOOD("Food delivery membership"), SHOPPING("Shopping membership"),
    TV("TV / DTH"), GAMING("Gaming"), OTHER("Other"),
}

data class KnownService(val name: String, val kind: SubKind, val words: List<String>)

enum class SubFlag(val label: String) {
    PRICE_HIKE("Price went up"),
    SAME_KIND("Another service of the same kind"),
    TWO_ACCOUNTS("Charged on two accounts"),
    DOUBLE_CHARGE("Charged twice in one cycle"),
    NOT_USED("You marked it not used"),
}

data class SubscriptionItem(
    /** Stable key the user's "not used" mark is stored under. */
    val key: String,
    val name: String,
    val kind: SubKind,
    val cadence: Cadence,
    val amountMinor: Long,
    val previousMinor: Long?,
    val yearlyMinor: Long,
    val lastDate: LocalDate,
    val nextDate: LocalDate,
    val accounts: List<String>,
    val charges: Int,
    val flags: List<SubFlag>,
    /** One line per flag, e.g. "₹199 → ₹249 from 4 Oct". */
    val details: List<String>,
    val notUsed: Boolean,
)

data class SubscriptionAuditResult(
    val items: List<SubscriptionItem>,
    val yearlyTotalMinor: Long,
    val monthlyTotalMinor: Long,
    /** What cancelling the ones marked not used would save a year. */
    val yearlySavingMinor: Long,
    val flagged: Int,
)

object SubscriptionAudit {

    val services: List<KnownService> = listOf(
        KnownService("Netflix", SubKind.VIDEO, listOf("netflix")),
        KnownService("Amazon Prime", SubKind.SHOPPING, listOf("amazon prime", "prime video", "primevideo", "amazonprime", "prime membership")),
        KnownService("JioHotstar", SubKind.VIDEO, listOf("hotstar", "jiohotstar", "disney")),
        KnownService("JioCinema", SubKind.VIDEO, listOf("jiocinema", "jio cinema")),
        KnownService("SonyLIV", SubKind.VIDEO, listOf("sonyliv", "sony liv")),
        KnownService("ZEE5", SubKind.VIDEO, listOf("zee5")),
        KnownService("aha", SubKind.VIDEO, listOf("aha video", "ahavideo")),
        KnownService("Sun NXT", SubKind.VIDEO, listOf("sun nxt", "sunnxt")),
        KnownService("YouTube Premium", SubKind.VIDEO, listOf("youtube", "youtubepremium")),
        KnownService("Apple TV+", SubKind.VIDEO, listOf("apple tv")),
        KnownService("Spotify", SubKind.MUSIC, listOf("spotify")),
        KnownService("JioSaavn", SubKind.MUSIC, listOf("jiosaavn", "saavn")),
        KnownService("Gaana", SubKind.MUSIC, listOf("gaana")),
        KnownService("Wynk", SubKind.MUSIC, listOf("wynk")),
        KnownService("Apple Music", SubKind.MUSIC, listOf("apple music")),
        KnownService("Audible", SubKind.NEWS, listOf("audible")),
        KnownService("Kindle Unlimited", SubKind.NEWS, listOf("kindle")),
        KnownService("Google One", SubKind.CLOUD, listOf("google one", "googleone", "google storage")),
        KnownService("iCloud", SubKind.CLOUD, listOf("icloud")),
        KnownService("Dropbox", SubKind.CLOUD, listOf("dropbox")),
        KnownService("Microsoft 365", SubKind.SOFTWARE, listOf("microsoft 365", "office 365", "microsoft365", "msft")),
        KnownService("Adobe", SubKind.SOFTWARE, listOf("adobe")),
        KnownService("Canva", SubKind.SOFTWARE, listOf("canva")),
        KnownService("ChatGPT", SubKind.SOFTWARE, listOf("openai", "chatgpt")),
        KnownService("Claude", SubKind.SOFTWARE, listOf("anthropic", "claude ai")),
        KnownService("LinkedIn Premium", SubKind.SOFTWARE, listOf("linkedin")),
        KnownService("Apple services", SubKind.SOFTWARE, listOf("apple com bill", "apple services", "itunes")),
        KnownService("Google Play", SubKind.SOFTWARE, listOf("google play", "googleplay")),
        KnownService("Swiggy One", SubKind.FOOD, listOf("swiggy one")),
        KnownService("Zomato Gold", SubKind.FOOD, listOf("zomato gold")),
        KnownService("Flipkart VIP", SubKind.SHOPPING, listOf("flipkart vip", "flipkart plus")),
        KnownService("Times Prime", SubKind.SHOPPING, listOf("times prime", "timesprime")),
        KnownService("cult.fit", SubKind.FITNESS, listOf("cult fit", "cultfit", "curefit")),
        KnownService("HealthifyMe", SubKind.FITNESS, listOf("healthify")),
        KnownService("Tata Play", SubKind.TV, listOf("tata play", "tataplay", "tata sky", "tatasky")),
        KnownService("Airtel DTH / Xstream", SubKind.TV, listOf("airtel dth", "xstream")),
        KnownService("Dish TV", SubKind.TV, listOf("dish tv", "dishtv", "d2h")),
        KnownService("The Hindu", SubKind.NEWS, listOf("the hindu", "thehindu")),
        KnownService("Times of India", SubKind.NEWS, listOf("times of india", "toi plus")),
        KnownService("PlayStation Plus", SubKind.GAMING, listOf("playstation", "psn")),
        KnownService("Xbox Game Pass", SubKind.GAMING, listOf("xbox")),
    )

    fun serviceFor(merchant: String?, sms: String? = null): KnownService? {
        val h = " " + Categorizer.merchantKey(listOfNotNull(merchant, sms).joinToString(" ")) + " "
        return services.firstOrNull { s -> s.words.any { w -> h.contains(" ${Categorizer.merchantKey(w)} ") || h.contains(" ${Categorizer.merchantKey(w).replace(" ", "")} ") } }
    }

    private fun perYear(amount: Long, cadence: Cadence): Long = when (cadence) {
        Cadence.WEEKLY -> amount * 52
        Cadence.MONTHLY -> amount * 12
        Cadence.QUARTERLY -> amount * 4
        Cadence.YEARLY -> amount
    }

    /**
     * [recurring]: repeating payments the app found; [debits]: all debits with SMS text (for known
     * services charged only once a year); [notUsed]: keys the user marked not used.
     */
    fun audit(
        recurring: List<RecurringPayment>,
        debits: List<DebitRecord>,
        notUsed: Set<String>,
        ignored: Set<String>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SubscriptionAuditResult {
        data class Raw(val key: String, val name: String, val kind: SubKind, val cadence: Cadence, val history: List<TxnLite>, val nextDate: LocalDate)

        val raws = ArrayList<Raw>()
        val usedKeys = HashSet<String>()
        fun historyOf(pred: (DebitRecord) -> Boolean) = debits.filter(pred).map { it.txn }.sortedBy { it.timestamp }

        for (r in recurring) {
            val mk = Categorizer.merchantKey(r.merchant)
            val sms = debits.lastOrNull { it.txn.merchant?.let(Categorizer::merchantKey) == mk }?.sms
            val service = serviceFor(r.merchant, sms)
            val isSub = service != null || r.category == DefaultCategories.ENTERTAINMENT ||
                AutoDebits.classify(r.merchant, sms, r.category) == AutoDebitKind.SUBSCRIPTION
            if (!isSub) continue
            val key = "sub:" + (service?.name?.let(Categorizer::merchantKey) ?: mk)
            if (!usedKeys.add(key)) continue
            val history = historyOf { d ->
                if (service != null) serviceFor(d.txn.merchant, d.sms) == service
                else d.txn.merchant?.let(Categorizer::merchantKey) == mk
            }
            raws += Raw(key, service?.name ?: r.merchant, service?.kind ?: SubKind.OTHER, r.cadence, history, r.nextDate)
        }
        // Known services not caught as recurring, e.g. a yearly plan paid once so far.
        val byService = debits.mapNotNull { d -> serviceFor(d.txn.merchant, d.sms)?.let { it to d } }.groupBy({ it.first }, { it.second })
        for ((service, list) in byService) {
            val key = "sub:" + Categorizer.merchantKey(service.name)
            if (key in usedKeys) continue
            val history = list.map { it.txn }.sortedBy { it.timestamp }
            val last = Dates.localDate(history.last().timestamp, zone)
            if (ChronoUnit.DAYS.between(last, today) > 380) continue
            val cadence = if (history.size >= 2) {
                val gap = ChronoUnit.DAYS.between(Dates.localDate(history[history.size - 2].timestamp, zone), last)
                Cadence.entries.firstOrNull { it.fits(gap) } ?: Cadence.YEARLY
            } else Cadence.YEARLY
            // Monthly ones that stopped two cycles ago are gone, not "yearly".
            if (cadence != Cadence.YEARLY && ChronoUnit.DAYS.between(last, today) > cadence.days * 2 + 7) continue
            var next = cadence.next(last)
            while (next.isBefore(today)) next = cadence.next(next)
            usedKeys += key
            raws += Raw(key, service.name, service.kind, cadence, history, next)
        }

        val live = raws.filter { it.key !in ignored && it.history.isNotEmpty() }
        val items = live.map { raw ->
            val h = raw.history
            val last = h.last()
            val lastDate = Dates.localDate(last.timestamp, zone)
            val flags = ArrayList<SubFlag>()
            val details = ArrayList<String>()

            // Price hike: latest charge above the typical earlier one by 5% and at least ₹10.
            val earlier = h.dropLast(1).map { it.amountMinor }.sorted()
            val previous = earlier.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
            if (previous != null && last.amountMinor - previous >= 10_00 && last.amountMinor * 100 >= previous * 105) {
                flags += SubFlag.PRICE_HIKE
                val since = h.indexOfLast { it.amountMinor <= previous }.let { i -> h.getOrNull(i + 1) ?: last }
                details += "${formatInr(previous)} → ${formatInr(last.amountMinor)} from ${Dates.localDate(since.timestamp, zone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))}"
            }
            // Accounts used in the last two cycles.
            val recentFrom = lastDate.minusDays(raw.cadence.days * 2L - 1)
            val recent = h.filter { !Dates.localDate(it.timestamp, zone).isBefore(recentFrom) }
            val accounts = recent.mapNotNull { it.account }.distinct()
            if (accounts.size >= 2) {
                flags += SubFlag.TWO_ACCOUNTS
                details += "Paid from ${accounts.joinToString(" and ")}"
            }
            // Two similar charges far closer together than the cycle.
            val dates = h.map { Dates.localDate(it.timestamp, zone) }
            val double = h.indices.drop(1).firstOrNull { i ->
                val gap = ChronoUnit.DAYS.between(dates[i - 1], dates[i])
                val similar = kotlin.math.abs(h[i].amountMinor - h[i - 1].amountMinor) * 10 <= h[i].amountMinor
                gap < raw.cadence.days / 3 && similar && !dates[i].isBefore(today.minusDays(400))
            }
            if (double != null) {
                flags += SubFlag.DOUBLE_CHARGE
                details += "Charged ${formatInr(h[double].amountMinor)} on ${dates[double - 1]} and again on ${dates[double]}"
            }
            val marked = raw.key in notUsed
            if (marked) flags += SubFlag.NOT_USED
            SubscriptionItem(
                key = raw.key, name = raw.name, kind = raw.kind, cadence = raw.cadence, amountMinor = last.amountMinor,
                previousMinor = previous, yearlyMinor = perYear(last.amountMinor, raw.cadence), lastDate = lastDate,
                nextDate = raw.nextDate, accounts = h.mapNotNull { it.account }.distinct(), charges = h.size,
                flags = flags, details = details, notUsed = marked,
            )
        }.toMutableList()

        // Several services of the same kind (two video apps, two music apps).
        val byKind = items.filter { it.kind != SubKind.OTHER && it.kind != SubKind.SOFTWARE }.groupBy { it.kind }
        for ((kind, group) in byKind) {
            if (group.size < 2) continue
            for (s in group) {
                val i = items.indexOf(s)
                val others = group.filter { o -> o !== s }.joinToString(", ") { o -> o.name }
                items[i] = s.copy(flags = s.flags + SubFlag.SAME_KIND, details = s.details + "${kind.label}: you also pay for $others")
            }
        }
        val sorted = items.sortedWith(compareByDescending<SubscriptionItem> { it.flags.isNotEmpty() }.thenByDescending { it.yearlyMinor })
        val yearly = sorted.sumOf { it.yearlyMinor }
        return SubscriptionAuditResult(
            items = sorted,
            yearlyTotalMinor = yearly,
            monthlyTotalMinor = yearly / 12,
            yearlySavingMinor = sorted.filter { it.notUsed }.sumOf { it.yearlyMinor },
            flagged = sorted.count { it.flags.isNotEmpty() },
        )
    }
}
