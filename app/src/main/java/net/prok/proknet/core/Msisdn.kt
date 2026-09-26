package net.prok.proknet.core

/**
 * v0.18.0: one Mobile Money number, one hash, on every side.
 *
 * The treasury phone hashes the counterparty printed in the operator's message; the payee's
 * phone hashes what they typed into Gagner; the Brain hashes what a withdrawal request
 * carried. If any of the three normalises differently, a withdrawal the treasurer really
 * sent never turns "Payé" on its own. So the rule is small and shared:
 *
 *   sha256("ProkNet-msisdn-1|" + the nine national digits), hex.
 *
 * "+242 06 612 34 56", "00242066123456" and "066123456" are one number. Anything that is
 * not exactly nine digits starting with 0 once the country code is gone is NOT a number:
 * guessing a missing digit would send somebody's money to a number nobody typed.
 *
 * Must equal `msisdn_hash` in server/brain/ledger.py; `server/tests/fixtures/msisdn_hash.txt`
 * was written by the Python side and is read by the test here.
 */
object Msisdn {
    const val DOMAIN = "ProkNet-msisdn-1|"

    /** The nine national digits, or "" when this is not a number we accept. */
    fun digits(raw: String?): String {
        var d = (raw ?: "").filter { it.isDigit() }
        if (d.startsWith("00242")) d = d.substring(5)
        else if (d.startsWith("242") && d.length > 9) d = d.substring(3)
        return if (d.length == 9 && d[0] == '0') d else ""
    }

    fun hash(raw: String?): String {
        val d = digits(raw)
        if (d.isEmpty()) return ""
        return Crypto.sha256((DOMAIN + d).toByteArray(Charsets.UTF_8)).toHex()
    }

    /** "06 61 23 45 6" for a screen; "" when not a number. */
    fun pretty(raw: String?): String {
        val d = digits(raw)
        if (d.isEmpty()) return ""
        return d.substring(0, 2) + " " + d.substring(2, 4) + " " + d.substring(4, 6) + " " + d.substring(6, 8) + " " + d.substring(8)
    }
}
