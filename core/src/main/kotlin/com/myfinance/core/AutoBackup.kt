package com.myfinance.core

/* When the scheduled encrypted backup should run. */

enum class BackupFrequency(val label: String, val days: Int) { DAILY("Every day", 1), WEEKLY("Every week", 7), MONTHLY("Every month", 30) }

object AutoBackupSchedule {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * True when a backup is due: none made yet, or the last good one is at least the period old
     * (with two hours of slack so a daily job running a little early still counts).
     */
    fun isDue(lastSuccess: Long?, frequency: BackupFrequency, now: Long): Boolean {
        if (lastSuccess == null || lastSuccess <= 0) return true
        return now - lastSuccess >= frequency.days * DAY_MS - 2 * 60 * 60 * 1000
    }

    /** Warn when backups have been failing or not running for more than two periods. */
    fun isStale(lastSuccess: Long?, frequency: BackupFrequency, now: Long): Boolean =
        lastSuccess != null && lastSuccess > 0 && now - lastSuccess > 2L * frequency.days * DAY_MS + DAY_MS
}
