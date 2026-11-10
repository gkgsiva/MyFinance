package com.myfinance.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cases taken from the ideas in kharcha, omoi-sms-parser, transaction-sms-parser and Card-Statement-Analyser. */
class SmsCoverageTest {
    private fun parse(sender: String, body: String) = SmsParser.parse(sender, body, 1_700_000_000_000)

    @Test
    fun `a real debit with a never-share-your-OTP footer is kept`() {
        val t = assertNotNull(parse("JD-ICICIT-S", "INR 348.90 spent using ICICI Bank Card XX7788 on 05-Nov-24 on AMAZON. Avl Limit: INR 61,120.44. If not you, call 1800 2662. Never share your OTP, CVV or PIN."))
        assertEquals(34890, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("7788", t.accountTail)
    }

    @Test
    fun `OTP messages bound to a code are still dropped`() {
        assertNull(parse("VM-HDFCBK", "123456 is your OTP for txn of Rs 2,000.00 at AMAZON on HDFC Bank card x4321."))
        assertNull(parse("VM-HDFCBK", "Use OTP 482913 to complete your payment of Rs 999 at FLIPKART."))
    }

    @Test
    fun `currency-less debit is not confused with the balance`() {
        val t = assertNotNull(parse("VM-SBIUPI", "Dear UPI user A/C X1234 debited by 35.0 on date 05Nov24 trf to CHAI POINT Refno 431298765432. Avl Bal Rs 800.50"))
        assertEquals(3500, t.amountMinor)
        assertEquals(80050, t.balanceMinor)
    }

    @Test
    fun `card used for a transaction reads as a spend`() {
        val t = assertNotNull(parse("AD-ICICIT", "Your ICICI Bank Credit Card XX7788 has been used for a transaction of INR 1,250.00 on Nov 05, 2024 at DECATHLON. Available Credit Limit INR 61,120.44. Total Credit Limit INR 70,000.00"))
        assertEquals(125000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
    }

    @Test
    fun `outstanding amount is never the transaction`() {
        val t = assertNotNull(parse("VM-SBICRD", "Total outstanding Rs 45,000.00. Rs 2,499.00 spent on your SBI Credit Card ending 5566 at MYNTRA on 05/11/24."))
        assertEquals(249900, t.amountMinor)
    }

    @Test
    fun `UPI narration in the message gives the payee`() {
        val t = assertNotNull(parse("VM-AXISBK", "INR 420.00 debited A/c no. XX3456 05-11-24 11:20:01 UPI/P2M/431212345678/SWIGGY LIMITED Not you? SMS BLOCKUPI"))
        assertEquals("SWIGGY LIMITED", t.merchant)
        val info = assertNotNull(parse("VM-AXISBK", "Debit INR 500.00 A/c no. XX3456 05-11-24 Info- UPI/DR/431212345679/RAVI KUMAR/YESB. Avl Bal INR 10,000.00"))
        assertEquals("RAVI KUMAR", info.merchant)
    }

    @Test
    fun `bullet masked account numbers`() {
        val t = assertNotNull(parse("VM-KOTAKB", "Sent Rs.300.00 from Kotak Bank AC ••4455 to zomato@hdfcbank on 05-11-24. UPI Ref 431200000001."))
        assertEquals("4455", t.accountTail)
        assertEquals("zomato", t.merchant)
    }

    @Test
    fun `marketing and money that did not move are dropped`() {
        assertNull(parse("VM-HDFCBK", "Get flat Rs 500 cashback on your next spend of Rs 2,000 with HDFC Bank Credit Card. T&C apply"))
        assertNull(parse("VM-ICICIB", "Congratulations! Your credit limit has been increased to Rs 3,00,000."))
        assertNull(parse("VM-HDFCBK", "Cheque no 000123 for Rs 25,000.00 has been returned unpaid. Charges debited."))
        assertNull(parse("VM-AXISBK", "Refund of Rs 899.00 initiated by Myntra. It will reflect in 5-7 working days."))
        assertNull(parse("VM-ICICIB", "You have spent Rs 12,450 on your ICICI card this month. Track your spends on iMobile."))
        assertNull(parse("VM-HDFCBK", "Your HDFC Bank Credit Card statement is generated. Total amount due Rs 12,340.00, minimum Rs 620.00."))
    }

    @Test
    fun `cashback that was credited is still income`() {
        val t = assertNotNull(parse("VM-AXISBK", "Cashback of Rs 50.00 credited to your Axis Bank a/c XX3456 on 05-11-24."))
        assertEquals(TxnType.CREDIT, t.type)
        assertEquals(5000, t.amountMinor)
    }

    @Test
    fun `card payment received keeps working with total due in the text`() {
        val t = assertNotNull(parse("VM-HDFCBK", "Payment of Rs 12,340.00 received towards your HDFC Bank Credit Card XX4321. Total amount due is now Rs 0.00."))
        assertEquals(TxnType.CREDIT, t.type)
        assertEquals(1234000, t.amountMinor)
    }

    @Test
    fun `charges are spends`() {
        val t = assertNotNull(parse("VM-SBIINB", "Your A/c XX1111 is charged Rs 236.00 towards SMS charges for the quarter."))
        assertEquals(TxnType.DEBIT, t.type)
    }

    @Test
    fun `promotional senders and phone numbers are not banks`() {
        assertFalse(SmsParser.looksLikeFinancialSender("VM-HDFCBK-P"))
        assertFalse(SmsParser.looksLikeFinancialSender("+91 98765 43210"))
        assertTrue(SmsParser.looksLikeFinancialSender("VM-HDFCBK-S"))
        assertTrue(SmsParser.looksLikeFinancialSender("JD-ICICIT"))
    }

    @Test
    fun `more bank and card senders`() {
        assertEquals("HDFC Bank", SmsParser.bankFromSender("VM-PAYZAP"))
        assertEquals("OneCard", SmsParser.bankFromSender("AX-ONECRD-S"))
        assertEquals("PhonePe", SmsParser.bankFromSender("VK-PHONPE"))
    }
}

class SmsDedupeTest {
    private val t0 = 1_700_000_000_000

    @Test
    fun `same reference is one payment`() {
        val bank = SmsDedupe.Seen(25000, TxnType.DEBIT, t0, "431012345678", "HDFC Bank")
        val app = SmsDedupe.Seen(25000, TxnType.DEBIT, t0 + 90_000, "431012345678", "PhonePe")
        assertEquals(bank, SmsDedupe.duplicateOf(app, listOf(bank)))
    }

    @Test
    fun `wallet message within minutes of the bank's is one payment`() {
        val bank = SmsDedupe.Seen(25000, TxnType.DEBIT, t0, "431012345678", "HDFC Bank")
        val wallet = SmsDedupe.Seen(25000, TxnType.DEBIT, t0 + 120_000, null, "Paytm")
        assertEquals(bank, SmsDedupe.duplicateOf(wallet, listOf(bank)))
        // Much later, or two bank messages, or different refs: separate payments.
        assertNull(SmsDedupe.duplicateOf(wallet.copy(timestamp = t0 + 3_600_000), listOf(bank)))
        assertNull(SmsDedupe.duplicateOf(wallet.copy(bank = "SBI"), listOf(bank)))
        assertNull(SmsDedupe.duplicateOf(wallet.copy(reference = "431099999999"), listOf(bank)))
    }
}

class StatementCoverageTest {
    @Test
    fun `HDFC card statement with pipe time and rupee glyph`() {
        val text = """
            HDFC Bank Credit Card Statement
            Card No: 4375 XXXX XXXX 1234
            Domestic Transactions
            DATE & TIME TRANSACTION DESCRIPTION REWARDS AMOUNT
            12/03/2025| 14:32 SWIGGY BANGALORE C 450.00 l
            13/03/2025| 09:05 AMAZON PAY INDIA PRIVA MUMBAI C 1,299.00
            15/03/2025| 10:10 PAYMENT RECEIVED - THANK YOU + C 10,000.00
        """.trimIndent()
        val st = assertNotNull(StatementParser.fromText(text))
        assertEquals(StatementKind.CARD, st.kind)
        assertEquals(3, st.rows.size)
        assertEquals("SWIGGY BANGALORE", st.rows[0].description)
        assertEquals(45000, st.rows[0].amountMinor)
        assertEquals(TxnType.DEBIT, st.rows[0].type)
        assertEquals(14, st.rows[0].time?.hour)
        assertEquals(TxnType.CREDIT, st.rows[2].type)
        assertEquals("PAYMENT RECEIVED - THANK YOU", st.rows[2].description)
    }

    @Test
    fun `whole-rupee amounts after Rs and time lines below`() {
        val text = """
            CSB Bank Edge Credit Card statement. Credit card number XXXX XXXX XXXX 9988
            05 Mar 2025 ZOMATO LTD Rs. 1,234
            10:32 PM
            07 Mar 2025 UBER INDIA Rs. 310
        """.trimIndent()
        val st = assertNotNull(StatementParser.fromText(text))
        assertEquals(listOf(123400L, 31000L), st.rows.map { it.amountMinor })
        assertEquals("ZOMATO LTD", st.rows[0].description)
    }

    @Test
    fun `bank statement totals are checked against the closing balance`() {
        val rows = listOf(
            StatementRow(LocalDate.of(2025, 3, 1), null, "UPI-SWIGGY", 45000, TxnType.DEBIT, 955000),
            StatementRow(LocalDate.of(2025, 3, 2), null, "NEFT CR SALARY", 5000000, TxnType.CREDIT, 5955000),
            StatementRow(LocalDate.of(2025, 3, 3), null, "ATM WDL", 200000, TxnType.DEBIT, 5755000),
        )
        assertTrue(assertNotNull(StatementCheck.bank(rows)).ok)
        // A missed line breaks the chain.
        assertFalse(assertNotNull(StatementCheck.bank(rows.drop(1).let { listOf(rows[0]) + it.drop(1) })).ok)
        // Newest first works too.
        assertTrue(assertNotNull(StatementCheck.bank(rows.reversed())).ok)
    }

    @Test
    fun `card bill is checked against previous balance and total due`() {
        val text = """
            ICICI Bank Credit Card Statement
            Previous Balance ₹5,000.00
            Total Amount Due ₹6,749.00
            Payment Due Date 05/04/2025
            10/03/2025 SWIGGY BANGALORE 450.00
            12/03/2025 AMAZON 1,299.00
        """.trimIndent()
        val st = assertNotNull(StatementParser.fromText(text))
        val check = assertNotNull(st.check)
        assertEquals(674900, check.expectedMinor)
        assertTrue(check.ok, "computed ${check.computedMinor}")
    }
}

class DashboardTest {
    private val zone = java.time.ZoneId.of("Asia/Kolkata")
    private fun t(day: String, amt: Long, type: TxnType, cat: String, merchant: String?, account: String?) =
        TxnLite(amt, type, cat, merchant, account, LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli())

    private val txns = listOf(
        t("2025-03-05", 50000, TxnType.DEBIT, "Food", "Swiggy", "HDFC ••1234"),
        t("2025-03-06", 120000, TxnType.DEBIT, "Shopping", "Amazon", "ICICI card ••7788"),
        t("2025-03-07", 30000, TxnType.DEBIT, "Food", "Zomato", "HDFC ••1234"),
        t("2025-03-01", 9000000, TxnType.CREDIT, "Salary", "ACME", "HDFC ••1234"),
        t("2025-01-10", 70000, TxnType.DEBIT, "Shopping", "Amazon", "ICICI card ••7788"),
        t("2024-03-10", 99900, TxnType.DEBIT, "Travel", "IRCTC", null),
    )

    @Test
    fun `month totals, top category and accounts`() {
        val d = Dashboard.compute(txns, java.time.YearMonth.of(2025, 3), DashPeriod.MONTH, zone = zone)
        assertEquals(200000, d.spentMinor)
        assertEquals(9000000, d.receivedMinor)
        assertEquals(4, d.count)
        assertEquals("Shopping", d.topCategory?.key)
        assertEquals(listOf("ICICI card ••7788", "HDFC ••1234"), d.byAccount.map { it.key })
        assertEquals(12, d.trend.size)
        assertEquals(200000, d.trend.last().second)
        assertEquals(70000, d.trend.first { it.first == java.time.YearMonth.of(2025, 1) }.second)
    }

    @Test
    fun `account filter and financial year`() {
        val card = Dashboard.compute(txns, java.time.YearMonth.of(2025, 3), DashPeriod.FY, account = "ICICI card ••7788", zone = zone)
        assertEquals(190000, card.spentMinor)
        assertEquals(12, card.months.size)
        val other = Dashboard.compute(txns, java.time.YearMonth.of(2024, 3), DashPeriod.MONTH, account = Dashboard.UNKNOWN_ACCOUNT, zone = zone)
        assertEquals(99900, other.spentMinor)
    }
}

class IciciSbiCanaraSmsTest {
    private fun parse(sender: String, body: String) = SmsParser.parse(sender, body, 1_700_000_000_000)

    @Test
    fun `ICICI account debit names the payee`() {
        val t = assertNotNull(parse("JM-ICICIT-S", "ICICI Bank Acct XX123 debited for Rs 500.00 on 05-Nov-24; SWIGGY credited. UPI:431234567890. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766."))
        assertEquals(50000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("SWIGGY", t.merchant)
        assertEquals("ICICI Bank", t.bank)
        assertEquals("123", t.accountTail)
    }

    @Test
    fun `ICICI account credit`() {
        val t = assertNotNull(parse("VM-ICICIT", "Dear Customer, Acct XX123 is credited with Rs 5,000.00 on 05-Nov-24 from RAVI KUMAR. UPI:431234567891-ICICI Bank."))
        assertEquals(TxnType.CREDIT, t.type)
        assertEquals("RAVI KUMAR", t.merchant)
    }

    @Test
    fun `SBI debit by transfer and credit`() {
        val d = assertNotNull(parse("AD-SBIINB-S", "Dear Customer, Your A/C XXXXX654321 has a debit by transfer of Rs 1,200.00 on 05Nov24. Avl Bal Rs 23,456.00 -SBI"))
        assertEquals(120000, d.amountMinor)
        assertEquals(TxnType.DEBIT, d.type)
        assertEquals(2345600, d.balanceMinor)
        assertEquals("SBI", d.bank)
        val c = assertNotNull(parse("AD-SBIINB-S", "Dear Customer, Your A/C XXXXX654321 Credited INR 50,000.00 on 01/11/24 -Deposit by transfer from ACME CORP. Avl Bal INR 73,456.00 -SBI"))
        assertEquals(TxnType.CREDIT, c.type)
        assertEquals(5000000, c.amountMinor)
    }

    @Test
    fun `Canara debit with Total Avail bal`() {
        val t = assertNotNull(parse("VM-CANBNK", "An amount of INR 750.00 has been DEBITED to your account XXX4321 on 05/11/2024 towards UPI/431234500000/BIGBASKET. Total Avail.bal INR 12,000.00 - Canara Bank"))
        assertEquals(75000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("Canara Bank", t.bank)
        assertEquals("4321", t.accountTail)
        val c = assertNotNull(parse("VM-CANBNK", "Your a/c XX4321 is credited by Rs.2,000.00 on 05/11/2024 by UPI from RAMESH. Total Avail.bal INR 14,000.00 - Canara Bank"))
        assertEquals(TxnType.CREDIT, c.type)
        assertEquals(200000, c.amountMinor)
    }
}

class MyAccountsTest {
    private val mine = MyAccount(1, "1234", "437551", "ICICI Bank", true, "Amazon Pay card")
    private val wifeAddOn = MyAccount(2, "5678", null, "ICICI Bank", true, "Add-on", owner = "Priya", pdfPassword = "PRIY0101")
    private val wifeCanara = MyAccount(3, "4321", null, "Canara Bank", false, owner = "Priya")
    private val sbi = MyAccount(4, "1234", null, "SBI", false)
    private val all = listOf(mine, wifeAddOn, wifeCanara, sbi)

    @Test
    fun `numbers typed in`() {
        assertEquals("437551" to "7890", MyAccounts.parseNumber("4375 5112 3456 7890", true))
        assertEquals(null to "1234", MyAccounts.parseNumber("XX1234", true))
        assertNull(MyAccounts.parseNumber("12", true))
        assertEquals(null to "6789", MyAccounts.parseNumber("30123456789", false))
    }

    @Test
    fun `matching by last 4 and bank`() {
        assertEquals("Priya", MyAccounts.ownerOf(all, "4321", "Canara Bank"))
        assertEquals(wifeAddOn, MyAccounts.match(all, "5678", null))
        // Same last 4 on ICICI and SBI: the bank decides.
        assertEquals(sbi, MyAccounts.match(all, "1234", "SBI"))
        assertEquals(mine, MyAccounts.match(all, "1234", "ICICI Bank"))
        assertNull(MyAccounts.match(all, "1234", null)?.takeIf { it.owner != null })
        assertNull(MyAccounts.match(all, "4321", "HDFC Bank"))
    }

    @Test
    fun `statement matched by a number printed in it`() {
        val text = """
            ICICI Bank Credit Card Statement
            Customer ID 99887766
            4375XXXXXXXX1234
            10/03/2025 SWIGGY BANGALORE 450.00
            Card Number : 4375 XXXX XXXX 5678
            11/03/2025 AMAZON 1,299.00
        """.trimIndent()
        val st = assertNotNull(StatementParser.fromText(text))
        assertEquals(mine, MyAccounts.forStatement(listOf(mine, wifeAddOn, wifeCanara), st.copy(accountTail = "7766")))
        assertTrue(st.rows.any { it.cardTail == "5678" })
        assertEquals("Priya", MyAccounts.ownerOf(all, st.rows.last().cardTail, st.bank))
        assertEquals(listOf("PRIY0101"), MyAccounts.passwords(all))
    }
}

/** Siva's own messages (2026-11-10). */
class SivaSmsTest {
    private fun parse(sender: String, body: String) = SmsParser.parse(sender, body, 1_700_000_000_000)

    @Test
    fun `Pluxee meal card spend`() {
        val t = assertNotNull(parse("VM-PLUXEE", "Rs. 40.00 spent from Pluxee  Meal Card wallet, card no.xx2793 on 5-11-2026 8:42:3 at Southern De . Avl bal Rs.13137.00. Not you call 18002106919"))
        assertEquals(4000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("Southern De", t.merchant)
        assertEquals("2793", t.accountTail)
        assertEquals("Pluxee meal card", t.bank)
        assertEquals(1313700, t.balanceMinor)
    }

    @Test
    fun `Equitas FASTag toll`() {
        val t = assertNotNull(parse("VM-EQUTAS", "Equitas FasTag Toll charge debited for vehicle TN11AS9523 with TAG ID D7C0 for Rs.130.00 at Express avenue Mall parking . Avl Bal is Rs.100.00 NOV 09, 17:54 . Not u? SMS BLOCK FT D7C0 to 7045030000. Wear front &amp; rear seat belt. NHAI helpline - 1033"))
        assertEquals(13000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("Express avenue Mall parking", t.merchant)
        assertEquals("FASTag", t.bank)
        assertEquals("D7C0", t.accountTail)
        assertEquals(10000, t.balanceMinor)
        assertEquals(DefaultCategories.TRANSPORT, Categorizer().categorize(t.merchant, t.type, "Equitas FasTag Toll charge"))
    }

    @Test
    fun `ICICI account UPI debit`() {
        val t = assertNotNull(parse("JD-ICICIT-S", "ICICI Bank Acct XX144 debited for Rs 888.00 on 07-Nov-26; Alifs Fish Bay credited. UPI:117869245541. Call 18002662 for dispute. SMS BLOCK 144 to 9215676766."))
        assertEquals(88800, t.amountMinor)
        assertEquals("Alifs Fish Bay", t.merchant)
        assertEquals("144", t.accountTail)
        assertEquals("117869245541", t.reference)
        assertEquals("ICICI Bank", t.bank)
    }

    @Test
    fun `ICICI IMPS to another own account`() {
        val t = assertNotNull(parse("JD-ICICIT-S", "ICICI Bank Acct XX144 debited with Rs 22,000.00 on 03-Nov-26 &amp; Acct XX848 credited.IMPS:630721708826. Call 18002662 for dispute or SMS BLOCK 144 to 9215676766"))
        assertEquals(2200000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("144", t.accountTail)
        assertEquals("848", t.counterTail)
        assertEquals("630721708826", t.reference)
    }

    @Test
    fun `ICICI credit card UPI spend`() {
        val t = assertNotNull(parse("JD-ICICIT-S", "ICICI Bank Credit Card XX7002 debited for INR 40.00 on 03-Nov-26 for UPI-841795829162-HungerBo. To dispute call 18001080/SMS BLOCK 7002 to 9215676766"))
        assertEquals(4000, t.amountMinor)
        assertEquals(TxnType.DEBIT, t.type)
        assertEquals("7002", t.accountTail)
        assertEquals("HungerBo", t.merchant)
        assertEquals("841795829162", t.reference)
    }
}

class OpenSourceRulesTest {
    private val c = Categorizer()

    @Test
    fun `merchant rules from kharcha and Card-Statement-Analyser`() {
        assertEquals(DefaultCategories.FOOD, c.categorize("BOX8", TxnType.DEBIT))
        assertEquals(DefaultCategories.GROCERIES, c.categorize("LICIOUS", TxnType.DEBIT))
        assertEquals(DefaultCategories.HEALTH, c.categorize("THYROCARE TECHNOLOGIES", TxnType.DEBIT))
        assertEquals(DefaultCategories.SHOPPING, c.categorize("ZUDIO CHENNAI", TxnType.DEBIT))
        // Short names stay whole words: MOTOROLA is not Ola, ACTIVE is not ACT.
        assertEquals(DefaultCategories.SHOPPING, c.categorize("MOTOROLA STORE AMAZON", TxnType.DEBIT))
        // Your own rule wins over every built-in one.
        assertEquals("Snacks", Categorizer(mapOf("box8" to "Snacks")).categorize("BOX8", TxnType.DEBIT))
    }
}
