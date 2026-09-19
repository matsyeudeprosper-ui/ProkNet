package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10.2: the words on the Bluetooth test screen are decided by pure code,
 * so the person holding the phone reads one true sentence at a time and the
 * copied summary can be judged without the log.
 */
class BtLabTextTest {
    private val full = BulkPlan.Direction(BulkPlan.PROBE_BYTES.toLong(), 9_000)
    private val part = BulkPlan.Direction(243_712, 30_000, timedOut = true)

    private fun snap(
        role: BtLabText.Role = BtLabText.Role.BUYER, bluetoothOn: Boolean = true, upstream: String = "Wi-Fi", validated: Boolean = true,
        sellerFound: Boolean = true, phase: BulkPlan.Phase = BulkPlan.Phase.IDLE, authenticated: Boolean = false,
        step: BulkPlan.ProbeStep = BulkPlan.ProbeStep.NOT_STARTED, b2s: BulkPlan.Direction? = null, s2b: BulkPlan.Direction? = null,
        verdict: BulkPlan.Verdict = BulkPlan.Verdict.NOT_RUN, contract: Boolean = false, tunnel: String = "DISCONNECTED", vpn: Boolean = false,
        dns: Int = 0, https: Boolean? = null, customer: Boolean = false, error: String = "",
    ) = BtLabText.Snapshot(role, true, bluetoothOn, upstream, validated, sellerFound, phase, authenticated, step, b2s, s2b, verdict,
        contract, tunnel, vpn, dns, https, customer, error)

    @Test
    fun the_seller_screen_walks_the_user_through_the_test() {
        val idle = snap(role = BtLabText.Role.NONE)
        assertEquals(listOf("Internet source: Wi-Fi ✅", "Bluetooth: Ready ✅"), BtLabText.sellerReadyLines(idle))
        assertEquals("Tap START SHARING", BtLabText.sellerStatus(idle))
        assertEquals("Bluetooth: Off ❌", BtLabText.sellerReadyLines(snap(bluetoothOn = false))[1])
        assertEquals("Internet source: none ❌", BtLabText.sellerReadyLines(snap(upstream = "none"))[0])

        val s = BtLabText.Role.SELLER
        assertEquals("Waiting for another phone...", BtLabText.sellerStatus(snap(role = s)))
        assertEquals("Phone connecting...", BtLabText.sellerStatus(snap(role = s, phase = BulkPlan.Phase.AUTH)))
        assertEquals("Phone connected ✅\nTesting connection...", BtLabText.sellerStatus(snap(role = s, phase = BulkPlan.Phase.UP, authenticated = true, step = BulkPlan.ProbeStep.BUYER_TO_SELLER)))
        assertEquals("Bluetooth connection works both ways ✅\nInternet sharing starting...",
            BtLabText.sellerStatus(snap(role = s, phase = BulkPlan.Phase.UP, authenticated = true, b2s = full, s2b = full, verdict = BulkPlan.Verdict.BIDIRECTIONAL)))
        assertEquals("Sharing Internet ✅",
            BtLabText.sellerStatus(snap(role = s, phase = BulkPlan.Phase.UP, authenticated = true, b2s = full, s2b = full, verdict = BulkPlan.Verdict.BIDIRECTIONAL, customer = true)))
        // a failed probe is one sentence on the seller too
        assertEquals("Connection test failed. Buyer -> seller was too slow.",
            BtLabText.sellerStatus(snap(role = s, phase = BulkPlan.Phase.UP, authenticated = true, b2s = part, verdict = BulkPlan.Verdict.PARTIAL)))
    }

    @Test
    fun the_buyer_screen_says_one_thing_at_a_time() {
        assertEquals("Looking for the seller...", BtLabText.buyerStatus(snap(role = BtLabText.Role.NONE, sellerFound = false)))
        assertEquals("Seller found ✅\nTap CONNECT", BtLabText.buyerStatus(snap(role = BtLabText.Role.NONE)))
        assertEquals("Bluetooth: Off ❌", BtLabText.buyerStatus(snap(role = BtLabText.Role.NONE, bluetoothOn = false)))
        assertEquals("Connecting...", BtLabText.buyerStatus(snap(phase = BulkPlan.Phase.REQUESTED)))
        assertEquals("Connecting...", BtLabText.buyerStatus(snap(phase = BulkPlan.Phase.AUTH)))
        val up = snap(phase = BulkPlan.Phase.UP, authenticated = true, step = BulkPlan.ProbeStep.BUYER_TO_SELLER)
        assertEquals("Checking both directions...", BtLabText.buyerStatus(up))
        val passed = up.copy(buyerToSeller = full, sellerToBuyer = full, verdict = BulkPlan.Verdict.BIDIRECTIONAL, probeStep = BulkPlan.ProbeStep.COMPLETE)
        assertEquals("Starting Internet...", BtLabText.buyerStatus(passed))
        val tunnel = passed.copy(contract = true, tunnelState = "TUNNEL UP")
        assertTrue(BtLabText.buyerStatus(tunnel).startsWith("Starting Internet..."))
        val vpn = tunnel.copy(vpn = true)
        assertEquals("Testing Internet...", BtLabText.buyerStatus(vpn))
        val working = vpn.copy(httpsOk = true, dnsCount = 3, tunnelState = "INTERNET OK")
        assertEquals("INTERNET WORKING ✅", BtLabText.buyerStatus(working))
        assertEquals(listOf("Bluetooth: connected", "Seller Internet: available", "VPN: connected", "DNS: working", "HTTPS: working"), BtLabText.buyerDetailLines(working))
        assertEquals("Internet test failed.", BtLabText.buyerStatus(vpn.copy(httpsOk = false)))
        assertEquals("Connection test failed. Buyer -> seller was too slow.", BtLabText.buyerStatus(up.copy(buyerToSeller = part, verdict = BulkPlan.Verdict.PARTIAL)))
        assertEquals("Bluetooth connection failed.", BtLabText.buyerStatus(snap(phase = BulkPlan.Phase.FAILED, error = "Bluetooth bulk: the Bluetooth channel could not be connected")))
        assertEquals("The other phone could not be verified.", BtLabText.buyerStatus(snap(phase = BulkPlan.Phase.FAILED, error = "handshake failed")))
    }

    @Test
    fun the_copied_summary_can_be_judged_without_the_log() {
        val working = snap(phase = BulkPlan.Phase.UP, authenticated = true, step = BulkPlan.ProbeStep.COMPLETE, b2s = full, s2b = full,
            verdict = BulkPlan.Verdict.BIDIRECTIONAL, contract = true, tunnel = "INTERNET OK", vpn = true, dns = 4, https = true)
        val ok = BtLabText.summary(working)
        assertTrue(ok.startsWith("ProkNet Bluetooth Test\n\nPhone role: BUYER\n"))
        for (line in listOf("Bluetooth connected: YES", "Authentication: YES", "Buyer -> seller: PASS 262144/262144 B", "Seller -> buyer: PASS 262144/262144 B",
            "Contract: YES", "VPN: YES", "DNS: YES", "HTTPS: YES", "Internet: YES")) assertTrue(line, ok.contains(line + "\n"))
        assertFalse(ok.contains("Failure stage"))
        assertNull(BtLabText.failureSentence(working))
        assertEquals("NONE", BtLabText.failureStage(working))

        val failed = snap(phase = BulkPlan.Phase.UP, authenticated = true, step = BulkPlan.ProbeStep.BUYER_TO_SELLER, b2s = part, verdict = BulkPlan.Verdict.PARTIAL,
            error = BulkPlan.PROBE_FAIL_REASON)
        val bad = BtLabText.summary(failed)
        for (line in listOf("Buyer -> seller: PARTIAL 243712/262144 B", "Seller -> buyer: not run", "Internet: NO", "Failure stage: PROBE",
            "Reason: Connection test failed. Buyer -> seller was too slow.")) assertTrue(line, bad.contains(line + "\n"))

        // the seller summary reports its own side, not the buyer's VPN
        val seller = BtLabText.summary(working.copy(role = BtLabText.Role.SELLER, customerConnected = true))
        assertTrue(seller.contains("Phone role: SELLER\n"))
        assertTrue(seller.contains("Customer connected: YES\n"))
        assertFalse(seller.contains("VPN:"))

        // an attempt that died before the handshake
        val bt = BtLabText.summary(snap(phase = BulkPlan.Phase.FAILED, error = "Bluetooth bulk: never connected to the Bluetooth channel"))
        assertTrue(bt.contains("Bluetooth connected: NO\n"))
        assertTrue(bt.contains("Failure stage: BLUETOOTH\n"))
        assertTrue(bt.contains("Reason: Bluetooth connection failed.\n"))
    }
}
