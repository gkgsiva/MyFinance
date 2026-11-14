package com.myfinance.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Merchant names read from ICICI savings and card narrations (formats as printed; names made up). */
class NarrationMerchantTest {

    private fun m(s: String) = Narration.merchant(s)

    @Test
    fun savingsAccountNarrations() {
        assertEquals("Swiggy", m("UPI/435793431569/Payment from Ph/SWIGGY8@ybl/Yes B ank Ltd/"))
        assertEquals("Swiggy", m("UPI/436974563279/Swiggy/upiswiggy@icici/ICICI Bank"))
        assertEquals("Paytm wallet", m("UPI/430712429192/Oid2178780765@O/add-money@paytm/P aytm Payments/"))
        assertEquals("Google Pay", m("UPI/436974829202/UPI/goog-payment@ok/Axis Bank Ltd"))
        assertEquals("UPI 4540333333@ybl", m("UPI/437760820039/Payment from Ph/4540333333@ybl/DB"))
        assertEquals("Ravi Kumar", m("MOBILE BANKING MMT/IMPS/598876073987/Rent/RAVI KUMAR/CORP00"))
        assertEquals("IMPS transfer", m("MOBILE BANKING MMT/Ref534873540588/8179233333"))
        assertEquals("IRCTC", m("NET BANKING BIL/337099227197/ChgRs10 GSTRs1.80 IRCTC/10000121"))
        assertEquals("Credit card bill", m("NET BANKING BIL/337003202226/Credit bill/012887933333333"))
        assertEquals("Credit card bill", m("NET BANKING BIL/337687498392/Feb 2019 credit/012887933333333"))
        assertEquals("Anita R", m("BIL/NEFT/337678256728/school fees/ANITA R/CNRB0003 040"))
        assertEquals("Credit card bill", m("BIL/INFT/337847153095/CREDIT/Self"))
        assertEquals("Own account transfer", m("BIL/INFT/337042097976/savings/Self"))
        assertEquals("Corner Shop", m("DEBIT CARD VPS/CORNER SHOP /93753030719777/0"))
        assertEquals("Corner Shop", m("DEBIT CARD MPS/CORNER SHOP/937431947190/826075/CHENNAI"))
        assertEquals("HDFC Ltd", m("DECS/HDFC LTD EMI DEBITS/3331383860255"))
        assertEquals("HDFC Ltd", m("ACH/HDFCLTD/996983244"))
        assertEquals("LIC Housing Finance", m("CHEQUE 12920 CLG/LIC Housing Finance Limit/HDF 12920"))
        assertEquals("Salary", m("SAL/Salary - Apr 2018"))
        assertEquals("Salary", m("ICSP/ACME TECHNOLOGY BUSINESS CENTRE INDIA PVT LTD"))
        assertEquals("GST", m("SGST93753890022575420"))
        assertEquals("ATM withdrawal", m("OTHER ATMS NFS/CASH WDL/11-05-18"))
        assertEquals("Savings interest", m("639537833333:Int.Pd:31-12-2018 to 30-03-2019"))
    }

    @Test
    fun cardStatementNarrations() {
        assertEquals("Sri Vishnu Bakery", m("UPI-190811557230-SRI VISH NU BAKERY IN"))
        assertEquals("Hungerbox", m("UPI-190500605919-HungerBo x IN"))
        assertEquals("EMI principal", m("Principal Amount Amortization - <4/6>"))
        assertEquals("Allen Career Institute EMI", m("Principal Amount Amortization - <1/3>ALLEN CAREER INSTITUTE"))
        assertEquals("Allen Career Institute EMI interest", m("Interest Amount Amortization - <1/3>ALLEN CAREER INSTITUTE"))
        assertEquals("EMI interest", m("Interest Amount Amortization - <4/6>"))
        assertEquals("GST", m("IGST-CI@18%"))
        assertEquals("OpenAI ChatGPT", m("CHATGPT SUBSCRIPTION HTTPSOPENAI.C US*"))
        assertEquals("Vsp Super Market", m("VSP SUPER MARKET CHENNAI IN"))
    }

    @Test
    fun neverEmpty() {
        for (s in listOf("UPI/791086254379/", "99999999", "TECHN-/ACC/RN", "/ / /", "X")) {
            val name = m(s)
            assertTrue(!name.isNullOrBlank(), s)
        }
    }

    @Test
    fun narrationOverThreeLinesIsOneLine() {
        // ICICI: the first line of a long narration is printed above the date line, the last below it with "0.00".
        val text = """
            Statement of Transactions in Savings Account XXXXXXXX7788 in INR for the period April 01, 2018 - March 31, 2019
            DATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE
            01-11-2018  B/F  1,00,000.00
            20-11-2018 DEBIT CARD IPS/CORNER SHOP/93757793717207/0 1,334.00 98,666.00
            SAL/ACME TECHNOLOGY  BUSINESS CENTRE
            26-11-2018  1,44,924.00  2,43,590.00
            IND
             IA PVT LTD  0.00
            27-11-2018  UPI/439291174861/PhonePe Credit/phonepemerchant/Ye 1,065.00  2,44,655.00
             s Bank Ltd  0.00
            NEFT-SIN30994Q0000092-ACME NISSAN
            28-11-2018  1.00  2,44,656.00
            TECHN-/ACC/RN
             00278 TEST-09238008547-SCBL0000001  0.00
            Total: 1,46,002.00 1,334.00 2,44,656.00
        """.trimIndent()
        val st = StatementParser.fromText(text)!!
        assertEquals(4, st.rows.size)
        assertEquals("DEBIT CARD IPS/CORNER SHOP/93757793717207/0", st.rows[0].description)
        assertEquals("SAL/ACME TECHNOLOGY BUSINESS CENTRE IND IA PVT LTD", st.rows[1].description)
        assertEquals("Salary", m(st.rows[1].description))
        assertEquals("UPI/439291174861/PhonePe Credit/phonepemerchant/Ye s Bank Ltd", st.rows[2].description)
        assertEquals("NEFT-SIN30994Q0000092-ACME NISSAN TECHN-/ACC/RN 00278 TEST-09238008547-SCBL0000001", st.rows[3].description)
        assertEquals(st.check?.expectedMinor, st.check?.computedMinor)
    }

    /** Payees in every layout the parser reads (2022+ numbered savings, card UPI lines, ACH/sweep). Made-up names. */
    @Test
    fun payeesInAllLayouts() {
        // Numbered savings: UPI/<ref>/<note>/<vpa>/<bank>/<hash>
        assertEquals("Swiggy", m("UPI/209307800001/Payment for/swiggy.rzp@axis/Axis Bank/AXIfcc2a4cd6e12497a9a59031c463352ef"))
        assertEquals("Anand Stores", m("UPI/209307800002/ANAND STORES/paytmqr12345@paytm/Paytm Payments"))
        // Card UPI line: UPI-<ref>-<payee> IN, with words split by the PDF
        assertEquals("Swiggy", m("UPI-443348112287-Swiggy L td IN"))
        assertEquals("Redbus", m("UPI-988505869537-REDBUS IN"))
        // Card shop line ending in a city
        assertEquals("Adyar Ananda Bhavan", m("ADYAR ANANDA BHAVAN CHENNAI IN"))
        assertEquals("Amazon", m("AMAZON INDIA CYBS SI MUMBAI IN"))
        // Mandates and sweeps
        assertEquals("Sweep to linked account", m("Sweep Trf To: 123456789012"))
        assertEquals("Card payment received", m("INFINITY PAYMENT RECEIVED, THANK YOU"))
    }
}
