package com.myfinance.core

import java.time.Instant
import kotlin.math.abs

/**
 * A transaction as written to / read from an export file, so family members can merge
 * their phones' data into one view.
 */
data class SharedTxn(
    /** Stable id that survives export/import, so merging the same file twice adds nothing. */
    val uid: String,
    val amountMinor: Long,
    val type: TxnType,
    val merchant: String?,
    val category: String,
    val accountTail: String?,
    val bank: String?,
    val timestamp: Long,
    val note: String?,
    /** Whose phone the transaction came from, e.g. "Siva". */
    val owner: String?,
    /** "SMS" or "MANUAL". */
    val source: String,
    val smsKey: String?,
    val reference: String?,
    val excluded: Boolean,
)

object Dedupe {
    /** Bank SMS can be timestamped a few minutes apart on different phones. */
    private const val SMS_WINDOW_MS = 15 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * True when [a] and [b] describe the same real-world transaction, for example a joint-account debit
     * whose SMS reached two phones, or a manual entry typed on both.
     */
    fun isDuplicate(a: SharedTxn, b: SharedTxn): Boolean {
        if (a.uid == b.uid) return true
        if (a.amountMinor != b.amountMinor || a.type != b.type) return false
        if (a.smsKey != null && a.smsKey == b.smsKey) return true
        if (!a.reference.isNullOrBlank() && a.reference.length >= 6 && a.reference == b.reference) return true

        val gap = abs(a.timestamp - b.timestamp)
        if (a.accountTail != null && a.accountTail == b.accountTail && gap <= SMS_WINDOW_MS) return true

        if (a.source == "MANUAL" || b.source == "MANUAL") {
            val ma = a.merchant?.let(Categorizer::merchantKey)
            val mb = b.merchant?.let(Categorizer::merchantKey)
            if (ma != null && ma == mb && gap <= DAY_MS) return true
        }
        return false
    }

    /** Returns the incoming rows that are not already present in [existing] (nor repeated within [incoming]). */
    fun newOnly(existing: List<SharedTxn>, incoming: List<SharedTxn>): List<SharedTxn> {
        val uids = existing.mapTo(HashSet()) { it.uid }
        val smsKeys = existing.mapNotNullTo(HashSet()) { it.smsKey }
        // Index by time so each incoming row is only compared with rows from about the same day.
        val byTime = java.util.TreeMap<Long, MutableList<SharedTxn>>()
        fun add(t: SharedTxn) { byTime.getOrPut(t.timestamp) { ArrayList() } += t }
        existing.forEach(::add)

        val kept = ArrayList<SharedTxn>()
        for (t in incoming) {
            val clash = t.uid in uids ||
                (t.smsKey != null && t.smsKey in smsKeys) ||
                byTime.subMap(t.timestamp - DAY_MS, true, t.timestamp + DAY_MS, true)
                    .values.any { bucket -> bucket.any { isDuplicate(it, t) } }
            if (!clash) {
                kept += t
                uids += t.uid
                t.smsKey?.let { smsKeys += it }
                add(t)
            }
        }
        return kept
    }
}

/**
 * CSV export/import. CSV opens directly in Excel / Google Sheets, so the export doubles as a statement.
 */
object CsvCodec {
    val header = listOf(
        "uid", "date", "amount", "type", "merchant", "category", "account", "bank",
        "note", "owner", "source", "sms_key", "reference", "excluded",
    )

    fun encode(rows: List<SharedTxn>): String = buildString {
        appendLine(header.joinToString(","))
        for (r in rows) {
            appendLine(
                listOf(
                    r.uid,
                    Instant.ofEpochMilli(r.timestamp).toString(),
                    java.math.BigDecimal.valueOf(r.amountMinor, 2).toPlainString(),
                    r.type.name,
                    r.merchant, r.category, r.accountTail, r.bank, r.note, r.owner, r.source,
                    r.smsKey, r.reference, r.excluded.toString(),
                ).joinToString(",") { quote(it) },
            )
        }
    }

    /** Parses an export. Rows that cannot be read are skipped; the count of skipped rows is returned too. */
    fun decode(text: String): Pair<List<SharedTxn>, Int> {
        val records = splitRecords(text.removePrefix("﻿"))
        if (records.isEmpty()) return emptyList<SharedTxn>() to 0
        val cols = records.first().map { it.trim().lowercase() }
        fun idx(name: String) = cols.indexOf(name)
        val out = ArrayList<SharedTxn>()
        var bad = 0
        for (rec in records.drop(1)) {
            if (rec.all { it.isBlank() }) continue
            fun get(name: String): String? = idx(name).takeIf { it >= 0 && it < rec.size }?.let { rec[it] }?.ifBlank { null }
            try {
                out += SharedTxn(
                    uid = get("uid") ?: error("uid"),
                    amountMinor = parseAmountToMinor(get("amount") ?: error("amount")) ?: error("amount"),
                    type = TxnType.valueOf(get("type")!!.uppercase()),
                    merchant = get("merchant"),
                    category = get("category") ?: DefaultCategories.OTHER,
                    accountTail = get("account"),
                    bank = get("bank"),
                    timestamp = Instant.parse(get("date")!!).toEpochMilli(),
                    note = get("note"),
                    owner = get("owner"),
                    source = get("source") ?: "MANUAL",
                    smsKey = get("sms_key"),
                    reference = get("reference"),
                    excluded = get("excluded")?.toBooleanStrictOrNull() ?: false,
                )
            } catch (e: Exception) {
                bad++
            }
        }
        return out to bad
    }

    private fun quote(v: String?): String {
        if (v == null) return ""
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    private fun splitRecords(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var field = StringBuilder()
        var record = ArrayList<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(ch)
            } else when (ch) {
                '"' -> inQuotes = true
                ',' -> { record += field.toString(); field = StringBuilder() }
                '\r' -> {}
                '\n' -> { record += field.toString(); records += record; record = ArrayList(); field = StringBuilder() }
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) { record += field.toString(); records += record }
        return records
    }
}
