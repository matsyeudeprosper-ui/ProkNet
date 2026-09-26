package net.prok.proknet.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import net.prok.proknet.core.DeviceReceipt
import net.prok.proknet.core.DiagLog

/**
 * v0.16.0: watching for the operator's "you have received" message on the seller's phone.
 *
 * Two sources, one job: turn an Android event into a [DeviceReceipt.Candidate] and hand it
 * over. Neither of them knows anything about money, amounts or debts — that is the
 * parser's and the matcher's business, and keeping the split means a change in operator
 * wording never touches this file.
 *
 * **Privacy is a design constraint here, not a policy note.** Nothing reads the inbox.
 * Nothing scans history. A message is looked at only while a payment is actually expected,
 * only the one that matches leaves a trace, and what is kept is its sha256 rather than its
 * text. The seller's private messages never become ProkNet's data.
 */
object ReceiptCapture {

    /** Where a candidate goes. Set by the running node; null means nobody is listening. */
    @Volatile var sink: ((DeviceReceipt.Candidate) -> Unit)? = null

    /**
     * v0.16.1: is a payment actually expected right now?
     *
     * v0.16.0 promised content was inspected only while a payment was expected, but a
     * running node was enough to read every default-SMS notification. This makes the
     * promise structural: both sources ask this and return **before** touching a title or
     * a body. It exposes one boolean and nothing else, so the listener never learns what
     * is owed, to whom, or how much.
     */
    @Volatile var paymentExpected: (() -> Boolean)? = null

    fun expecting(): Boolean = try { paymentExpected?.invoke() ?: false } catch (e: Exception) { false }

    /**
     * v0.18.0: the treasury phone. On Prok's own phone every operator message about Prok's
     * wallet is ProkNet's business, so the "only while a payment is expected" gate does not
     * apply there - but ONLY there: [treasuryActive] answers true solely when the Brain has
     * said this identity is a treasury identity, and a seller's phone never sees this path.
     */
    @Volatile var treasuryActive: (() -> Boolean)? = null
    @Volatile var treasurySink: ((DeviceReceipt.Candidate) -> Unit)? = null

    fun treasury(): Boolean = try { treasurySink != null && (treasuryActive?.invoke() ?: false) } catch (e: Exception) { false }

    /** May a source read message content right now, for either purpose? */
    fun anyoneListening(): Boolean = (sink != null && expecting()) || treasury()

    /**
     * The phone's default SMS application. A notification only counts as payment evidence
     * when this app posted it, because any app at all can post "Vous avez reçu 50 CFA".
     */
    fun defaultSmsPackage(context: Context): String =
        try { Telephony.Sms.getDefaultSmsPackage(context) ?: "" } catch (e: Exception) { "" }

    /** Is notification access actually granted right now? */
    fun notificationAccessGranted(context: Context): Boolean = try {
        val flat = android.provider.Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners") ?: ""
        flat.contains(context.packageName)
    } catch (e: Exception) { false }

    /** The screen Android provides for granting it. We never ask for more than this. */
    fun notificationAccessIntent(): Intent =
        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

    fun offer(c: DeviceReceipt.Candidate) {
        val s = sink
        if (s != null && expecting()) try { s(c) } catch (e: Exception) { DiagLog.w("RECEIPT", "sink: " + e) }
        val t = treasurySink
        if (t != null && treasury()) try { t(c) } catch (e: Exception) { DiagLog.w("RECEIPT", "treasury sink: " + e) }
    }
}

/**
 * The notification source: the practical one for most builds, because the SMS permissions
 * are restricted on Play and ProkNet has no business becoming anybody's SMS app.
 *
 * Weaker than direct SMS, and labelled that way all the way through to the receipt: a
 * notification is a rendering of a message, not the message.
 */
class ReceiptListener : NotificationListenerService() {

    private val tag = "RECEIPT"

    override fun onListenerConnected() {
        DiagLog.i(tag, "notification access connected; automatic payment detection is available")
    }

    override fun onListenerDisconnected() {
        DiagLog.w(tag, "notification access lost; automatic payment detection is off")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // v0.16.1: nobody is waiting on a payment, so this notification is none of our
        // business. We return before reading the title or the text, not after.
        if (!ReceiptCapture.anyoneListening()) return
        val pkg = sbn.packageName ?: return
        // only the default SMS application may speak for the operator
        if (pkg != ReceiptCapture.defaultSmsPackage(this)) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (text.isBlank()) return          // hidden content: never guess
        ReceiptCapture.offer(DeviceReceipt.Candidate(
            DeviceReceipt.Source.DEFAULT_SMS_NOTIFICATION, pkg, title, text, sbn.postTime))
    }
}

/**
 * The direct SMS source: stronger, because the sender address comes from the network
 * rather than from whatever an app chose to display.
 *
 * Feature-gated on purpose. `RECEIVE_SMS` is restricted on Play, so ProkNet must work
 * without it, and this receiver simply never fires when the permission is absent.
 */
class SmsReceiptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!ReceiptCapture.anyoneListening()) return
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            // a long message arrives in parts; the operator's text is the whole of it
            val sender = messages.firstOrNull()?.originatingAddress ?: ""
            val body = messages.joinToString("") { it.messageBody ?: "" }
            if (body.isBlank()) return
            ReceiptCapture.offer(DeviceReceipt.Candidate(
                DeviceReceipt.Source.DIRECT_SMS, context.packageName, sender, body,
                messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()))
        } catch (e: Exception) {
            DiagLog.w("RECEIPT", "sms: " + e)
        }
    }

    companion object {
        /** True when this phone actually granted the SMS permission. */
        fun available(context: Context): Boolean =
            context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
