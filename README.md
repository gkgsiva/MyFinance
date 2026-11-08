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
