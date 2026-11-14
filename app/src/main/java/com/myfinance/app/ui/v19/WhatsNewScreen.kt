package com.myfinance.app.ui.v19

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.myfinance.app.ui.v13.Muted
import com.myfinance.app.ui.v13.SimpleScreen

/** One release and what it brought. */
data class Release(val version: String, val title: String, val changes: List<String>)

/** Every release from 1.0, newest first. Add the new release at the top when publishing one. */
val releaseNotes: List<Release> = listOf(
    Release(
        "1.16", "Year-end and family reports, receipts, card due alerts",
        listOf(
            "Monthly PDF report in colour with the entries first, then Household accounts (income and expenditure, each account and card, bank reconciliation, family members, EMIs, lines to sign) and your notes in full.",
            "Year-end report (PDF): twelve months side by side, the year's income and expenditure, investments, family members, EMIs, the items for your tax return, and every receipt kept. Settings > Year-end report.",
            "Receipts and attachments: attach a PDF bill or invoice to an entry as well as photos. They go into the encrypted backup, and the PDF reports list them with the entry number.",
            "Family report: after merging the family's exports, everyone's month side by side, each person apart, as a screen and a PDF. Settings > Family report & limits.",
            "A monthly spending limit for each family member, with a notification at 80% and when it's crossed.",
            "Card due alerts: 3 days before each card's bill date (what's on it so far) and on the bill date (the amount due and pay-by date). Switch on Balances.",
            "Import health check: months with no statement, and bank months whose balance doesn't match the entries. Tap one to see the entry that looks missing or counted twice, and open it. The PDF's bank reconciliation says where to look too.",
        ),
    ),
    Release(
        "1.15", "Card spends stay on the card until the bill is paid",
        listOf(
            "Home: credit card spends count as spending in the month you make them, but no longer take money off the bank balance. They show \"on card bill\" and add up in a new \"Card due so far\" line.",
            "Home: paying the card bill from the bank is listed (\"Card bill paid\") and moves the bank balance, without counting the spending a second time.",
            "Salary counts once in every month, including months read only from the bank statement.",
            "Duplicates are matched by the bank's transaction ID: copies with the same ID are removed on their own (to Recently deleted), two payments with different IDs are never called duplicates, and the Review inbox shows one card per payment with \"Remove all\".",
            "Every line of the ICICI statements is read: the 2022-onward savings layout, the website \"View last statement\" page, and card refunds paid out to the bank.",
            "Payee names read right from statement lines (Swiggy, Amazon, IRCTC, shop names without the city, UPI payees in every layout). Lines already imported are named again once.",
            "Monthly PDF report to print, share or save, with every entry, splits, charts and insights.",
        ),
    ),
    Release(
        "1.14", "Money that comes back, statements per period",
        listOf(
            "A failed payment and its money back, a refund, or an autopay hold that was released cancel out: neither counts as spending or income. Settings > Reversed & refunded lists them; \"Not a reversal\" undoes one.",
            "Billers' receipts (\"Payment of Rs 2576.71 for your JioHome connection has been received\") are no longer counted as money received.",
            "Payees shown as a UPI code (Yblec123879a52a…) take the name the message gives, e.g. Jio broadband.",
            "A UPI payment on the RuPay card and its statement line are one entry, even when the statement files it under another card; the card the SMS names is kept.",
            "Import history > Keep only SMS for a period: takes statement entries out for days whose statement isn't final yet.",
            "Settings > Statements: a card's or account's entries for this or last bill cycle, a month, or any dates.",
            "Investments are shown as Invested, not counted as spending (Home, Insights, reports, budgets).",
            "Autopay set-up, pre-debit and amount-blocked messages are no longer read as payments.",
            "Settings > What's new: this page.",
        ),
    ),
    Release(
        "1.13", "Card bill payments, end dates",
        listOf(
            "Salary counts in the salary month again (also when it comes on a weekend); faster with years of entries.",
            "Card bill payments: bank payments matched with each card month by month, 14th-to-14th card totals against the statement, and a check that bank entries are complete.",
            "Repeating reminders and future payments can end (\"Ends on\"); card EMIs end by themselves from the statement's instalments.",
        ),
    ),
    Release(
        "1.12", "Home ledger and a new look",
        listOf(
            "Home is this month's ledger with a running balance.",
            "New look across the app; Dashboard renamed Insights.",
            "SMS and statement doubles cleaned up (ICICI's XX144 vs 4144); payee names read from statement remarks; card EMI instalments; same amount on the same date each month counts as recurring.",
        ),
    ),
    Release("1.11.2", "Crash fix", listOf("Home no longer crashes; Settings > Share error details sends a crash log.")),
    Release(
        "1.11.1", "Card bills paired",
        listOf("A card bill paid from the bank is paired with the card's payment received; salary found more reliably; the same line on two cards is read once."),
    ),
    Release(
        "1.11", "Filters, salary month, card bill days",
        listOf("Compact Transactions filters.", "Salary month found by itself.", "Card bill day and pay-by day, with a pay-by reminder."),
    ),
    Release(
        "1.10.1", "Several cards in one statement",
        listOf("Lines of multi-card and multi-account statements kept per card or account; repeated tables skipped; transfers between own accounts left out."),
    ),
    Release(
        "1.10", "Faster statement import",
        listOf("Old statements import faster, with progress and Stop, in the background.", "Import history with undo; preview; missing statements finder.", "Uncategorised entries in bulk; 30-day backup reminder."),
    ),
    Release(
        "1.9", "Card bills not counted twice",
        listOf("Card bill payments are not spending or income.", "More views on the Dashboard, Monday weekly digest, + Future payment."),
    ),
    Release(
        "1.8", "Simple Home, cards & accounts",
        listOf("Simpler Home and a Dashboard tab.", "My cards & accounts with an owner per card.", "\"Already paid\" on reminders; importing the same file twice adds nothing; about 70 built-in merchant rules."),
    ),
    Release(
        "1.7", "Reminders and payments ahead",
        listOf("Payment reminders and Payments ahead.", "Missed payment flag, bill amounts from SMS, 30-day forecast, yearly dues, spending pace alert.", "Statement names with your own nickname per payment."),
    ),
    Release(
        "1.6", "Review inbox, transfers, splits",
        listOf("Auto debits only when they repeat; family UPI left out.", "Review inbox, transfers, split payments, rule tester, monthly close, data health check, Excel export, foreign currency, heavy spends."),
    ),
    Release(
        "1.5", "Today card and quick adds",
        listOf("Today card with quick adds.", "Ask in plain words, tax pack, insurance & warranty vault, subscription audit, cash flow calendar, goal links, lending tracker, auto-backup, shortcuts and a widget."),
    ),
    Release(
        "1.4", "Labels and refunds",
        listOf("Label suggestions and budgets, card bill check, loan payoff, salary breakdown, refund tracker, year in review, statement reminders, family label view, pinned reports."),
    ),
    Release("1.3.1", "Income labels", listOf("Labels for money received; refunds netted per label and payee.")),
    Release(
        "1.3", "Statements, labels, undo",
        listOf("Import bank and card statements (PDF, locked PDF, Excel, CSV; several years); statements take priority over SMS with no duplicates.", "Auto debits and EMIs, undo and Recently deleted, rescan SMS, labels, payee names, hide entries, Settings, Explore, income over the years, \"Did you know?\"."),
    ),
    Release("1.2.2", "SMS permission help", listOf("Steps for Android 13+ \"restricted settings\" so SMS can be read.")),
    Release("1.2.1", "Share via WhatsApp", listOf("Family phones share their entries over WhatsApp; open the file with MyFinance to merge.")),
    Release(
        "1.2", "Goals, net worth, backups",
        listOf("Goals, investments and net worth, bill reminders, split and settle, receipt photos, cash wallet, tax helper, app lock, encrypted backup and restore, custom SMS rules."),
    ),
    Release("1.1", "Bills, balances, family", listOf("Bills and balances from SMS; family export and merge without duplicates.")),
    Release("1.0", "First version", listOf("Reads bank SMS (India), builds your spending and income, lets you edit, add and group entries. Everything stays on the phone.")),
)

@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    SimpleScreen("What's new", onBack) {
        item { Muted("Every version of MyFinance, newest first.") }
        items(releaseNotes, key = { it.version }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("v${r.version} · ${r.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    r.changes.forEach { c -> Text("• $c", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
