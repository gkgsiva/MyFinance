# MyFinance

A personal finance Android app that reads your bank SMS, turns them into transactions and
builds a spending profile. Everything stays on the phone: there is no server or account.

## Features

- **SMS import**: reads the last 12 months of bank / card / UPI SMS on first run, and picks up new
  ones as they arrive. Tuned for Indian banks (HDFC, SBI, ICICI, Axis, Kotak and others). OTPs,
  promos, reminders and failed transactions are ignored.
- **Edit and add**: fix any amount, merchant, category, date or account, add notes, mark internal
  transfers as "leave out of totals", or add cash / personal entries by hand.
- **Auto-categorisation** by merchant keywords. When you change a merchant's category, the app
  remembers it and applies it to that merchant's other transactions.
- **Overview** per month: income, spending, net and savings rate, broken down by category,
  merchant, account or person, with optional monthly budgets per category.
- **Family merge**: each phone exports a CSV (opens in Excel / Sheets). Merge the files on one phone
  to see the whole family's spending. Duplicates, such as a joint-account payment whose SMS
  reached two phones, or the same file merged twice, are skipped.

### New in 1.1

- **Credit card bills and EMIs**: card statement and EMI reminder SMS show what is due and when,
  with a reminder notification 3 days before. Bills are marked paid automatically when the card
  payment SMS arrives, or by hand.
- **Recurring payments**: subscriptions, rent, SIPs and other repeating payments are found in your
  history, with the next expected date and a reminder 2 days before.
- **Budget alerts**: a notification when a category passes 80% of its monthly budget, and again at 100%.
- **Account balances**: the latest balance per bank account (and card limit left) from SMS.
- **Search and filters**: by date range, person, account, amount, type and payment mode; export the
  filtered list to Excel (.xlsx) or CSV.
- **Family sync with Google Drive**: every phone syncs with one shared CSV file in your Drive
  (Manage tab). No Google sign-in or server: the app reads and writes that one file through the
  Drive app's file picker, and duplicates are skipped as with a manual merge. Deletions on one
  phone are not removed from the others.
- **Home screen widget**: this month's spending, income and budget left.
- **Reports tab**: month-by-month trend, savings rate, category trends, spending by person, top
  merchants, payment mode split (UPI / card / cash / net banking), financial-year (April-March)
  totals, unusual spends, and a shareable monthly PDF statement.

### New in 1.2 (Plan tab)

- **Goals**: savings targets like "Vacation ₹1L by June", with progress, how much to save each month,
  and whether you are on track.
- **Net worth**: SIPs, mutual funds, FDs, RDs and share trades are read from SMS; add gold, property,
  PPF and loans by hand. Bank balances and cash in hand are included.
- **Bill reminders**: electricity, mobile, internet, insurance premiums, school fees, rent and more,
  repeating monthly, quarterly or yearly, with a notification before the due date. A matching payment
  SMS marks the bill paid.
- **Split & settle**: split a payment equally with family or friends, see who owes whom with the
  fewest payments, and record settlements.
- **Receipt photos**: attach bill photos to any transaction (camera or gallery). They stay in the
  app's private storage.
- **Cash wallet**: ATM withdrawals add cash in hand; log cash spends to see where it went.
- **Tax helper**: tag payments with 80C, 80CCD(1B), 80D, 80E, 80G or 24(b); suggestions for
  insurance, PPF, ELSS, NPS, tuition and donations; yearly (April-March) totals against the limits.
- **App lock**: PIN (stored only as a salted hash) and fingerprint, relocks after a minute away,
  hides the app in recent apps.
- **Encrypted backup & restore**: one file with everything, including receipt photos, encrypted with
  AES-256-GCM using your password. Save it wherever you like (Drive, Downloads, pen drive) and
  restore it on a new phone.
- **Custom SMS rules**: "any SMS with SWIGGY goes to Food", with the option to rename, leave out of
  totals, ignore, or tag a tax section; match on merchant, SMS text or sender, with a live preview.

### New in 1.3

- **Auto debits & EMIs** (Plan tab, or Bills, payments & balances): one list of loan EMIs, SIPs,
  insurance premiums, subscriptions and NACH / ECS / e-mandate / autopay debits, each with its next
  expected date, instalments left and the total due in the next 30 days. Add your own with the due
  date and number of instalments; a matching debit SMS ticks off that month. Ones found in SMS can be
  added to your list or hidden.
- **Undo and Recently deleted**: deleting a transaction shows an Undo button, and Manage > Recently
  deleted keeps deleted transactions for 60 days so you can restore them exactly as they were.
- **Rescan messages** (Manage tab): re-read the SMS inbox for the last 1, 3, 6 or 12 months or all of
  it. Nothing is added twice, edited transactions keep your changes, and deleted ones stay deleted
  unless you tick "bring back".
- **Labels**: tap a transaction to add labels like EMI, SIP, Food, Grocery or Vegetables (several per
  transaction). Choose to label every payment to that payee, now and in future. Group by label on the
  Overview, filter by label on Transactions, see "By label" in Reports, and rename or delete labels in Manage.
- **Payee names**: rename a payee on any transaction (e.g. a UPI id to "Vegetable seller Ravi") and tick
  the box to use that name for all their payments, past and future.
- **Hide from lists**: hide a transaction or an auto debit / EMI. It disappears from lists and report
  lists but still counts in every total. Transactions > ⋮ > Show hidden brings them back into view.
- **Settings** (Manage > All settings, or Plan > All settings): everything in one place, including
  Hidden items (unhide transactions and EMIs), Recently deleted, Rescan messages, SMS rules, labels,
  payee names, month setting, app lock, backup, theme (light / dark / same as phone) and notifications.
- **Explore spending** (Reports > Explore): pick a label, payee, category, account or person and see
  what it cost (or brought in) by week, month or year over any dates, with every payment listed.
- **Income over the years** (Reports): total income and salary per financial or calendar year, the
  typical monthly salary and how much it changed from the year before, and a salary-by-month chart.
- **Did you know?** (Overview and Reports): facts such as "Vegetables: highest month ever",
  "Fuel up 120%", "Food tops this month", the biggest payment and saving streaks.
- **Import bank and credit card statements** (Manage > Import statements): build a profile for past
  years from the statements your bank emails or lets you download. PDF (including password-protected
  PDFs), Excel (.xlsx and old .xls) and CSV work, for HDFC, SBI, ICICI, Axis, Kotak and most other
  Indian banks and cards. Pick many files at once, for example 3 years of monthly card bills; a PDF
  password is tried on the other files too, and is never saved. Nothing is counted twice: a statement
  line and the SMS for the same payment (same UPI reference, or same amount and account within 2 days)
  become one entry, whichever arrives first, and the statement's details win because it is the
  official record. Your category, labels, payee name and notes on that entry are kept.
- **Accounts & cards** (Manage, or Reports): every bank account and card, consolidated or one at a
  time, with totals per month, quarter, year or financial year. Tap an account to see its
  transactions, or switch to All items. Reports go back 3 or 5 years and compare financial years.

### New in 1.3.1

- **Income labels**: when labelling money received, Salary, Bonus, Rent income, Interest, Dividend,
  Freelance, Refund and Cashback are offered first. Reports > By label lists income per label for each
  financial year.
- **Refunds net off**: a label or payee now shows what was spent, what came back (refunds,
  cancellations, cashback) and the net. For example, IRCTC: tickets bought minus tickets refunded.
  Labelling a payee also labels refunds from the same seller under a slightly different name
  ("IRCTC REFUND", "Amazon Seller Services"), and Explore has a Net / Spent / Received switch.

### New in 1.4

- **Label suggestions**: after you label a couple of payments, similar ones are suggested: the same
  seller, payees sharing a name word ("Murugan Vegetables" like "Sri Balaji Vegetables"), or the label's
  own name in the payee ("ravi.veg@okicici"). Review them from the Overview card, Manage, or the edit screen.
- **Label budgets**: a monthly limit per label (Labels screen or Manage), shown on the Overview's
  "By label" rows, with alerts at 80% and 100%. Refunds on the label count against it.
- **Card bill check** (Plan): each credit card bill, from the statement SMS or an imported card
  statement PDF, against what was paid to the card. Flags late, partial, minimum-only and unpaid bills.
- **EMIs & loan payoff** (Plan): when each EMI ends and what that frees up each month; enter the
  interest rate to see principal still owed, interest paid and left, and what a prepayment saves.
- **Salary breakdown** (Reports): raises, bonus months, and income vs spending growth by year.
- **Refund tracker** (Plan, or "Waiting for refund" on a payment): matches refunds from the same
  seller and flags ones not back after 10 days (adjustable), with a notification.
- **Year in review** (Reports or Plan): totals, top sellers, record months and fun facts for a calendar
  or financial year, shareable on WhatsApp.
- **Statement reminders** (Settings): a monthly notification listing accounts and cards whose latest
  imported statement is older than last month.
- **Labels by person** (Reports): spending per label for each family member. Labels now travel in the
  family export, so labels added on other phones show on the main phone after merging.
- **Pinned reports**: tap Pin in Explore to keep a view such as "IRCTC net by year" on the Overview.

### New in 1.10.1

- **Statements with several cards or accounts.** A consolidated card statement lists each card under its own "Card Number" heading, and a bank statement can hold savings and current accounts. Each line now goes to its own card or account, and so to its owner (add-on or family cards from My cards & accounts). The preview lists every card with its line count, and Change account lets you correct each card's last 4 digits.
- **Repeated tables left out.** When a consolidated file prints the same statement twice, the repeat is skipped (card lines are recognised by their reference numbers, bank lines by their running balance).
- **Transfers between your own accounts.** Savings to current and back, seen on both sides, is no longer counted as spending or income. The preview's spent and received totals also leave out card bill payments, and show them separately.

### New in 1.10

- **Old statements import fast**: lines are written in batches, a progress bar shows pages read and lines imported, with Stop, and the import keeps running if you leave the screen (a notification says when it's done). Fixed: ICICI savings statements with a "B/F" opening line stopped the reader, and that line was read as an entry.
- **Import history & undo**: every import is listed and can be undone as a whole.
- **Preview before importing**: the period covered, lines, new and already there, for all files together.
- **Missing statements**: per card and account, the months with no statement.
- **Per-year summary** after importing past years. Bank balances still come from each account's latest SMS.
- **Uncategorised**: entries with no category grouped by payee; pick one and all of them (and future SMS and statements) get it.
- **Backup reminder** after 30 days without a backup or saved export.
- **Large fonts and small screens**: rows of chips and buttons wrap instead of scrolling sideways; tab names stay on one line.
- **Loading and empty states** on Home and Transactions.

### New in 1.9

- **Card bills never count twice**: paying a credit card bill from your bank account, and the "payment received" line on the card statement, are left out of spending and income by themselves (SMS and statements). The card's own purchases are the spending. They still mark the card bill as paid. Entries stored before this are cleaned up once; entries you edited are left alone.

- **Balances & card use**: the newest balance of each bank account and how much of each card's limit is used (add limits under My cards & accounts), with a link to Net worth.
- **This month vs last**: spending so far against last month by the same day, and by category.
- **Fixed vs flexible**: rent, EMIs, bills, fees, investments and repeating payments against what you can cut; plus cash, UPI and card.
- **Savings rate**: the share of money received that wasn't spent, month by month for a year.
- **Family summary**: the month's totals, each person's spending and top categories, shared on WhatsApp in one tap.
- **Meal card and FASTag pages**: balance from the newest SMS, this month's spends and where.
- **Weekly digest**: a Monday notification with last week's spending against the week before and what is due this week (switch on the Family summary screen).
- **Future payments from Home**: "+ Future payment" on Home adds a payment once or repeating (every day, week, 2 weeks, month, quarter, 6 months or year). It shows on Home, Payments ahead, the calendar and the forecast.

### New in 1.8

- **Simple Home:** today's transactions, payments due today and over the next few days (and missed ones), and one line for the month so far. Everything else moved to the next screens.
- **Dashboard tab** (replaces the Overview charts; Reports open from it): filter by month, 3 months, 12 months or financial year and by account or card; tiles for spent, received, top category, transaction count and unpaid card bills; monthly spend trend, category breakdown, top merchants and spend by bank and card.
- **Better SMS reading:** real alerts that end "never share your OTP" are kept; balances, limits and "outstanding" amounts are never read as the spend; promotions, cashback offers, limit increases, bounced cheques, refunds not yet credited and spend summaries are skipped; "card used for a transaction of", "charged", UPI narrations ("UPI/P2M/…/SWIGGY") and ••1234 account numbers are read; more bank and card senders (PayZapp, OneCard, slice, Scapia, Jupiter, IDBI, Bandhan, UCO and others); promotional senders ending "-P" are ignored.
- **No double count from UPI apps:** when PhonePe, Paytm or Amazon Pay and your bank both send an SMS for one payment (same reference, or within 5 minutes), it is added once.
- **My cards & accounts** (Manage, Settings): add your and your family's cards and bank accounts by number (full or last 4), bank, whose it is, and an optional PDF password. Imported statements and SMS for those numbers go on the right card and person (add-on cards on your statement included), locked PDFs open with the saved password, and past entries move to the right person when you save. Only the last 4 digits (and a card's first 6) are kept.
- **Built-in rules from the shared projects:** merchant-to-category rules from kharcha and Card-Statement-Analyser (about 70 more merchants such as Box8, Licious, Thyrocare, Zudio, Excitel), their sender lists and non-transaction patterns. Your own rules, payee names and corrections always come first. expense-analyser's list (AGPL) was not copied.
- **Your SMS:** Pluxee meal card and FASTag spends show as their own card / tag; ICICI "UPI:" and "IMPS:" references are kept; ICICI card "UPI-…-HungerBo" names the payee; "Acct XX144 debited … & Acct XX848 credited" to your own account is a transfer, not spending.
- **ICICI, SBI and Canara SMS:** ICICI "debited …; SWIGGY credited" names the payee; Canara "Total Avail.bal" is never read as the amount.
- **Statements:** HDFC's newer card statement layout ("12/03/2025| 14:32 … C 450.00", "+" for credits), whole-rupee amounts ("Rs. 1,234"), and a check at import that the lines add up to the statement's closing balance or the card's total due.

Parsing ideas came from these open-source projects (rewritten in Kotlin, no code copied): [kharcha](https://github.com/AkashPriyadarshii/kharcha) (Apache-2.0), [omoi-sms-parser](https://github.com/abhirajsinha/omoi-sms-parser) (MIT), [transaction-sms-parser](https://github.com/saurabhgupta050890/transaction-sms-parser) (MIT), [Card-Statement-Analyser](https://github.com/madhav921/Card-Statement-Analyser) (MIT) and [expense-analyser](https://github.com/juneja-varun/expense-analyser) (AGPL-3.0, ideas only).

### New in 1.6

- **Auto debits are only what repeats.** A single "autopay mandate" SMS no longer makes something an auto debit. It has to have been paid at least twice, a similar amount, about a month, quarter or year apart. Family UPI / UPI Circle payments are left out. Tap a detected row and choose **Not recurring** to remove that payee for good; undo it under Settings > Hidden items.
- **Review inbox** (Plan, and on the Today card): new payees, possible recurring payments, transfers, duplicates and late entries, each fixed or confirmed with a tap.
- **Family UPI:** what each family member spent through your UPI, by month, with a monthly limit per person. Label a payment "Family UPI - Name" or set the person on the screen.
- **Transfers & card bills:** money between your own accounts and card bill payments, so nothing counts twice.
- **Split a payment** into categories from its edit screen.
- **Search** by note text, notes in general and bill photos (Filters).
- **Rule tester:** "Test on past entries" in the rule editor.
- **Monthly close:** lock a finished month; imports leave it alone.
- **Data health check:** missing months, quiet accounts, possible duplicates.
- **Export to Excel or Sheets:** everything with labels, payees and categories.
- **Foreign currency:** USD and other card spends converted to rupees with rates you can edit.
- **Heavy spends:** every payment above an amount you choose in a period you choose.

### New in 1.5

- **Today card** on the Overview: today's spend and everything due in the next 7 days (EMIs, auto debits, bills, renewals, money lent), with quick add buttons.
- **Quick adds**: a spend on any past date, income, a payment due on any future date (once or repeating), a past investment with its date, a property, and your EPF balance. EPFO passbook SMS keep EPF up to date.
- **Ask in plain words** (Plan > Ask): "How much on Swiggy last year?", "Biggest payment in March", "Salary in 2025". Answered on the phone with built-in rules; nothing is sent anywhere.
- **Tax-time pack** (Plan): salary, savings and FD interest, capital gains (short/long term), and deductions for one financial year, saved as Excel or shared as text.
- **Insurance & warranty vault** (Plan): policies and warranties with renewal and end dates, photos, and reminders 30, 7 and 1 day before.
- **Subscription audit** (Plan): price hikes, two services of the same kind, charges on two accounts, double charges, and what cancelling unused ones saves.
- **Cash flow calendar** (Plan): a month of expected salary, EMIs, SIPs, bills and renewals next to what actually happened.
- **Goal-linked savings**: link a SIP, FD, holding or label to a goal and see when it will be reached.
- **Lent & borrowed** (Plan): who owes you and whom you owe, part repayments, due-date reminders and a WhatsApp reminder message.
- **Auto-backup** (Backup & restore): an encrypted backup on a schedule to a file you choose, including Google Drive.
- **Home screen**: long-press the icon for Add spend, Add income, Ask and Calendar; a new Today widget with a + button.

New data is kept in its own database file (myfinance-v15.db), so upgrading keeps everything.

## Install

The app reads SMS, which Google Play only allows for default SMS apps, so it is installed directly:

1. Open the repo's **Releases** page and download the `.apk` from the latest release
   (to publish one: *Actions* > **Build APK** > *Run workflow*, and enter a version such as `1.1`).
2. Open the `.apk` on the phone (allow "install unknown apps" when asked). New versions install
   over the old one and keep your data.
3. Open MyFinance, tap **Allow and import** and grant SMS permission.

## Project layout

- `core/`: pure Kotlin. SMS parser, categoriser, summaries, CSV export and duplicate detection,
  with unit tests. Runs without the Android SDK: `./gradlew -p core test`.
- `app/`: the Android app (Jetpack Compose, Room).

Build the APK locally with Android Studio or `./gradlew :app:assembleDebug`.
