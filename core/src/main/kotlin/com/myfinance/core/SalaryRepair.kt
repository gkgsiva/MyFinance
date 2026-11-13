package com.myfinance.core

import kotlin.math.abs

/**
 * Keeps a salary in the Salary category when an SMS and its statement line become one entry.
 *
 * The salary SMS ("credited ... salary", or a payer the user put in Salary) is in the Salary category, while
 * the bank statement prints the same credit as a plain NEFT line, which lands in Transfers or Income. When the
 * two were joined, the statement line used to keep its own category, so the salary stopped counting as salary
 * (and stopped moving to the next month with the salary month setting).
 */
object SalaryRepair {

    /** Categories that say little about a credit: a more specific one from the SMS wins over them. */
    private val generic = setOf(DefaultCategories.TRANSFERS, DefaultCategories.OTHER, DefaultCategories.INCOME, "Uncategorized", "Uncategorised")

    private val salaryWords = Regex("""\b(salary|payroll)\b|\bsal\b""", RegexOption.IGNORE_CASE)

    fun isSalary(category: String?) = category.equals(DefaultCategories.SALARY, ignoreCase = true)

    fun mentionsSalary(text: String?): Boolean = text != null && salaryWords.containsMatchIn(text)

    /**
     * The category for a statement line joined with its SMS entry. The user's own choice wins (the SMS entry's
     * when only it was edited); otherwise Salary from either side wins, then the SMS's when the line's is generic.
     */
    fun mergedCategory(lineCategory: String, smsCategory: String, lineEdited: Boolean, smsEdited: Boolean): String = when {
        lineEdited -> lineCategory
        smsEdited -> smsCategory
        isSalary(lineCategory) -> lineCategory
        isSalary(smsCategory) -> smsCategory
        lineCategory in generic && smsCategory !in generic -> smsCategory
        else -> lineCategory
    }

    data class Row(
        val id: Long,
        val type: TxnType,
        val amountMinor: Long,
        val timestamp: Long,
        val category: String,
        val text: String?,
        val userEdited: Boolean,
    )

    /**
     * Stored credits that should be back in Salary: lines joined with a salary SMS that was set aside ([removedSms],
     * found by the SMS text the line carries, else by the same amount within 3 days), and credits in a generic
     * category whose text says "salary". Entries the user edited are left alone.
     */
    fun toRestore(rows: List<Row>, removedSms: List<Row>): List<Long> {
        val candidates = rows.filter { it.type == TxnType.CREDIT && !it.userEdited && !isSalary(it.category) }
        val out = LinkedHashSet<Long>()
        candidates.filter { it.category in generic && mentionsSalary(it.text) }.forEach { out += it.id }
        val window = 3L * 24 * 60 * 60 * 1000
        for (s in removedSms.filter { it.type == TxnType.CREDIT && isSalary(it.category) }) {
            val sms = s.text?.trim()?.takeIf { it.length >= 20 }
            val byText = sms?.let { t -> candidates.firstOrNull { it.id !in out && it.text?.contains(t) == true } }
            val match = byText ?: candidates.filter { it.id !in out && it.amountMinor == s.amountMinor && abs(it.timestamp - s.timestamp) <= window }
                .minByOrNull { abs(it.timestamp - s.timestamp) }
            match?.let { out += it.id }
        }
        return out.toList()
    }
}
