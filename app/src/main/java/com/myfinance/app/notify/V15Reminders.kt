package com.myfinance.app.notify

import android.content.Context
import com.myfinance.app.data.FinanceRepository
import com.myfinance.app.data.SentReminderEntity
import com.myfinance.core.Lending
import com.myfinance.core.LendDirection
import com.myfinance.core.LendState
import com.myfinance.core.Vault
import com.myfinance.core.formatInr
import java.time.LocalDate

/** Policy renewals, warranty ends and lending due dates, each shown once. */
object V15Reminders {
    suspend fun check(context: Context, repo: FinanceRepository) {
        val dao = repo.v15
        val today = LocalDate.now()
        dao.purgeSent(System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000)

        val out = ArrayList<Pair<String, Pair<String, String>>>()
        for ((key, text) in Vault.reminders(dao.policies().map { it.toPolicy() }, dao.warranties().map { it.toWarranty() }, today)) {
            val title = if (key.startsWith("policy")) "Insurance renewal" else "Warranty ending"
            out += key to (title to text)
        }
        val reps = dao.repayments().map { it.toRepayment() }
        for (e in dao.lending()) {
            val s = Lending.status(e.toEntry(), reps, today)
            if (!Lending.remindToday(s)) continue
            val lent = s.entry.direction == LendDirection.LENT
            val amount = formatInr(s.outstandingMinor)
            val text = when {
                s.state == LendState.OVERDUE && lent -> "${s.entry.person} hasn't returned $amount yet. Send a reminder from MyFinance."
                s.state == LendState.OVERDUE -> "You were to return $amount to ${s.entry.person}."
                lent -> "${s.entry.person} is due to return $amount ${if (s.daysLeft == 0L) "today" else "tomorrow"}."
                else -> "Return $amount to ${s.entry.person} ${if (s.daysLeft == 0L) "today" else "tomorrow"}."
            }
            out += "lend:${e.id}:$today" to ((if (lent) "Money lent" else "Money borrowed") to text)
        }
        for ((key, msg) in out) {
            if (dao.wasSent(key) > 0) continue
            Notifier.reminder(context, key.hashCode(), msg.first, msg.second)
            dao.markSent(SentReminderEntity(key, System.currentTimeMillis()))
        }
    }
}
