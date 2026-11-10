package com.myfinance.core

/**
 * The family's own cards and bank accounts, registered by number, so statements and SMS are put on the
 * right card and the right person automatically.
 *
 * Only the last 4 digits are needed to match; for a full card number the first 6 (the card's issuer range)
 * are kept too, to tell apart two cards ending in the same digits. The rest of the number is never stored.
 */
data class MyAccount(
    val id: Long = 0,
    /** Last 4 digits. */
    val tail: String,
    /** First 6 digits of a card, when the full number was entered. */
    val prefix: String? = null,
    val bank: String? = null,
    val isCard: Boolean = true,
    /** The user's name for it, e.g. "Amazon Pay card". */
    val name: String? = null,
    /** Whose it is; null = the phone's owner. */
    val owner: String? = null,
    /** Password that opens this card's or account's PDF statements, when they are locked. */
    val pdfPassword: String? = null,
) {
    val label: String
        get() = listOfNotNull(name?.takeIf { it.isNotBlank() } ?: bank, (if (isCard) "card" else "a/c").takeIf { name.isNullOrBlank() && bank == null }, "••$tail")
            .joinToString(" ")
}

object MyAccounts {

    /** Splits a typed number ("4375 5112 3456 7890", "XX1234", "1234") into (prefix, last 4). Null when under 4 digits. */
    fun parseNumber(input: String, isCard: Boolean): Pair<String?, String>? {
        val d = input.filter(Char::isDigit)
        if (d.length < 4) return null
        val prefix = if (isCard && d.length >= 12 && input.none { it == 'x' || it == 'X' || it == '*' }) d.take(6) else null
        return prefix to d.takeLast(4)
    }

    private fun bankKey(b: String?) = b?.lowercase()?.replace(Regex("""\b(bank|ltd|limited|card|credit)\b"""), " ")
        ?.trim()?.split(Regex("""\s+"""))?.firstOrNull()?.ifBlank { null }

    /** True when the two bank names could be the same bank (either unknown counts as yes). */
    fun sameBank(a: String?, b: String?): Boolean {
        val x = bankKey(a) ?: return true
        val y = bankKey(b) ?: return true
        return x == y || x.startsWith(y) || y.startsWith(x)
    }

    /**
     * The registered account with these last 4 digits (and a compatible bank). When two registered cards end
     * the same and the bank doesn't decide, [prefix] (first digits seen in the statement) can; otherwise null.
     */
    fun match(accounts: List<MyAccount>, tail: String?, bank: String? = null, prefix: String? = null): MyAccount? {
        val t = tail?.filter(Char::isDigit)?.takeLast(4)?.takeIf { it.length == 4 } ?: return null
        val same = accounts.filter { it.tail == t }
        if (same.size <= 1) return same.firstOrNull()?.takeIf { sameBank(it.bank, bank) }
        val byBank = same.filter { sameBank(it.bank, bank) }
        if (byBank.size == 1) return byBank.first()
        val byPrefix = byBank.filter { it.prefix != null && prefix != null && it.prefix.startsWith(prefix.take(6)) }
        if (byPrefix.size == 1) return byPrefix.first()
        // Several, but all the same person's: the owner is still known.
        return byBank.takeIf { l -> l.isNotEmpty() && l.all { it.owner == l.first().owner } }?.first()
    }

    /** Whose a payment on this card / account is, from the registered numbers; null when unknown or the phone's owner. */
    fun ownerOf(accounts: List<MyAccount>, tail: String?, bank: String?): String? = match(accounts, tail, bank)?.owner

    /**
     * The registered account a statement is for: the number the parser picked, or else the one registered
     * number printed first in the statement's text (a customer id or other number may have been picked instead).
     */
    fun forStatement(accounts: List<MyAccount>, statement: ParsedStatement): MyAccount? {
        match(accounts, statement.accountTail, statement.bank)?.let { return it }
        // The first registered number printed is the statement's own (add-on cards are listed after it).
        for ((p, t) in statement.numbersSeen) {
            val hits = accounts.filter { a ->
                a.tail == t && (a.prefix == null || p == null || a.prefix.startsWith(p) || p.startsWith(a.prefix)) && sameBank(a.bank, statement.bank)
            }
            if (hits.size == 1) return hits.first()
        }
        return null
    }

    /** Saved PDF passwords, the statement's own account first when known. */
    fun passwords(accounts: List<MyAccount>): List<String> = accounts.mapNotNull { it.pdfPassword?.takeIf { p -> p.isNotBlank() } }.distinct()
}
