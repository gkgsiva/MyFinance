package com.myfinance.core

enum class ReviewKind(val label: String) {
    UNKNOWN_PAYEE("New payee"),
    RECURRING("Possible recurring payment"),
    TRANSFER("Possible transfer"),
    DUPLICATE("Possible duplicate"),
    LATE("Arrived after the month was closed"),
    FAMILY_LIMIT("Family UPI limit"),
}

/** One thing the app isn't sure about. [txnId] opens the entry; [ref] is what the actions work on. */
data class ReviewItem(
    val key: String,
    val kind: ReviewKind,
    val title: String,
    val detail: String,
    val amountMinor: Long?,
    val timestamp: Long,
    val txnId: Long? = null,
    val ref: Any? = null,
)

/** An uncategorized payee: its latest entry and how many there are. */
data class UnknownPayee(val name: String, val latestId: Long, val count: Int, val totalMinor: Long, val latest: Long)

/** One place for new entries the app isn't sure about, so the user can confirm or fix them with a tap. */
object ReviewInbox {
    /** Uncategorized debits since [sinceMillis], one per payee. */
    fun unknownPayees(txns: List<TxnLite>, sinceMillis: Long): List<UnknownPayee> =
        txns.filter { it.type == TxnType.DEBIT && it.category == DefaultCategories.OTHER && it.timestamp >= sinceMillis && !it.merchant.isNullOrBlank() }
            .groupBy { Categorizer.merchantKey(it.merchant!!) }
            .map { (_, v) ->
                val last = v.maxBy { it.timestamp }
                UnknownPayee(last.merchant!!, last.id, v.size, v.sumOf { it.amountMinor }, last.timestamp)
            }

    fun build(
        unknown: List<UnknownPayee>,
        recurring: List<AutoDebitItem>,
        transfers: List<TransferMatch>,
        duplicates: List<DuplicatePair>,
        late: List<TxnLite>,
        family: List<FamilyPersonMonth>,
        dismissed: Set<String>,
        zoneMillisOf: (java.time.LocalDate) -> Long,
    ): List<ReviewItem> {
        val out = ArrayList<ReviewItem>()
        unknown.forEach { u ->
            out += ReviewItem(
                "payee:" + Categorizer.merchantKey(u.name), ReviewKind.UNKNOWN_PAYEE, u.name,
                if (u.count == 1) "No category yet" else "${u.count} payments with no category", u.totalMinor, u.latest, u.latestId,
            )
        }
        recurring.filter { it.source == AutoDebitSource.DETECTED }.forEach { r ->
            out += ReviewItem(
                "rev:" + r.key, ReviewKind.RECURRING, r.name, "${r.kind.label} · ${r.cadenceLabel} · paid ${r.occurrences} times",
                r.amountMinor, r.lastPaid?.let(zoneMillisOf) ?: 0, ref = r,
            )
        }
        transfers.forEach { t ->
            val accts = t.rows.mapNotNull { it.account }.distinct().joinToString(" → ")
            out += ReviewItem(t.key, ReviewKind.TRANSFER, t.kind.label, accts.ifEmpty { t.out?.merchant ?: t.into?.merchant ?: "" }, t.amountMinor, t.timestamp, t.out?.id ?: t.into?.id, t)
        }
        duplicates.forEach { d ->
            out += ReviewItem(d.key, ReviewKind.DUPLICATE, d.b.merchant ?: d.a.merchant ?: "Same amount twice", "Two entries, same amount and account, close together", d.b.amountMinor, d.b.timestamp, d.b.id, d)
        }
        late.forEach { t ->
            out += ReviewItem("late:${t.id}", ReviewKind.LATE, t.merchant ?: t.category, "Added after you closed the month", t.amountMinor, t.timestamp, t.id)
        }
        family.filter { it.over }.forEach { f ->
            out += ReviewItem("fam:${f.person}:${java.time.YearMonth.now()}", ReviewKind.FAMILY_LIMIT, "${f.person} is over the monthly limit", "Spent ${formatInr(f.spentMinor)} of ${formatInr(f.limitMinor ?: 0)}", f.spentMinor, System.currentTimeMillis())
        }
        return out.filter { it.key !in dismissed }.sortedByDescending { it.timestamp }
    }
}
