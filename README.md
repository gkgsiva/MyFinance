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

## Install

The app reads SMS, which Google Play only allows for default SMS apps, so it is installed directly:

1. Open the latest run of **Build APK** under the repo's *Actions* tab and download the
   `myfinance-debug-apk` artifact.
2. Unzip it, copy the `.apk` to the phone and open it (allow "install unknown apps" when asked).
3. Open MyFinance, tap **Allow and import** and grant SMS permission.

## Project layout

- `core/`: pure Kotlin. SMS parser, categoriser, summaries, CSV export and duplicate detection,
  with unit tests. Runs without the Android SDK: `./gradlew -p core test`.
- `app/`: the Android app (Jetpack Compose, Room).

Build the APK locally with Android Studio or `./gradlew :app:assembleDebug`.
