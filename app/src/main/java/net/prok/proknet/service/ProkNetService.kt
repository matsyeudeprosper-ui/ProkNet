package net.prok.proknet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.DiagLog
import net.prok.proknet.node.NetworkNode
import net.prok.proknet.core.ProductState
import net.prok.proknet.ui.MainActivity

/**
 * Milestone 2B: foreground service that owns the ProkNet node's lifecycle.
 *
 *   START action -> startForeground(notification) -> node.start()
 *   STOP action  -> node.stop() -> stopForeground -> stopSelf()
 *
 * The Activity never starts or stops the node directly any more. The service
 * also watches screen on/off and logs that the node is still running, so the
 * "screen off continuity" claim is visible in the in-app log.
 */
class ProkNetService : Service(), ProkNetNode.Listener {
    private val tag = "SERVICE"
    private lateinit var node: ProkNetNode
    private val main = Handler(Looper.getMainLooper())
    private var lastNotifText = ""
    private var notifPending = false
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> DiagLog.i(tag, "SCREEN OFF - node keeps running: " + node.statusLine())
                Intent.ACTION_SCREEN_ON -> DiagLog.i(tag, "SCREEN ON - node status: " + node.statusLine())
                Intent.ACTION_USER_PRESENT -> DiagLog.i(tag, "device unlocked")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        node = ProkNetApp.node(this)
        running = true
        DiagLog.i(tag, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        DiagLog.i(tag, "onStartCommand action=" + action + " flags=" + flags + " (redelivered=" + ((flags and START_FLAG_REDELIVERY) != 0) + ")")
        when (action) {
            ACTION_STOP -> { stopNode(); return START_NOT_STICKY }
            ACTION_SHARE_NOW -> {
                // v0.13.3: the notification button and the Gagner button call the SAME function,
                // which re-checks the request, the Internet, Bluetooth, the price and the session.
                startNode()
                val id = intent?.getStringExtra("request")?.takeIf { it.isNotEmpty() }
                val err = ProkNetApp.network(this).acceptOpportunity(id)
                DiagLog.i(tag, "PARTAGER from the notification: " + (err ?: "sharing ON"))
                clearAlert()
                if (err != null) toastOnMain(err)
            }
            else -> startNode()
        }
        return START_STICKY
    }

    private fun startNode() {
        createChannel()
        val notif = buildNotification(getString(R.string.notif_starting))
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            DiagLog.i(tag, "foreground started (persistent notification shown)")
        } catch (e: Exception) {
            DiagLog.e(tag, "startForeground FAILED - Android refused the foreground service", e)
            stopSelf(); return
        }
        node.addListener(this)
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            })
            screenReceiverRegistered = true
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        DiagLog.i(tag, "battery optimisation ignored for this app: " + pm.isIgnoringBatteryOptimizations(packageName) +
            (if (!pm.isIgnoringBatteryOptimizations(packageName)) " (press Battery in the app if the phone kills ProkNet in the background)" else ""))
        DiagLog.i(tag, "screen is " + (if (pm.isInteractive) "ON" else "OFF") + " at service start")
        if (!node.isRunning) node.start() else DiagLog.i(tag, "node already running, service re-attached")
        val net = ProkNetApp.network(this)
        net.alertHook = { a -> notifyAlert(a) }
        net.clearAlertHook = { main.post { clearAlert() } }
        net.cancelHook = { _ -> main.post { if (net_prok_inboxEmpty()) clearAlert() } }
        main.removeCallbacks(periodicSync); main.postDelayed(periodicSync, 60_000)
        updateNotification(consumerStatus())
    }

    private fun net_prok_inboxEmpty(): Boolean =
        net.prok.proknet.core.ProviderInbox.active(ProkNetApp.network(this).inbox, System.currentTimeMillis()).isEmpty()

    private fun toastOnMain(text: String) = main.post {
        try { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    }

    private fun stopNode() {
        DiagLog.i(tag, "STOP requested: stopping node and service")
        node.stop()
        node.removeListener(this)
        if (screenReceiverRegistered) { try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}; screenReceiverRegistered = false }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        node.removeListener(this)
        if (screenReceiverRegistered) { try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}; screenReceiverRegistered = false }
        if (node.isRunning) {
            DiagLog.w(tag, "service destroyed by the system while node running - stopping node (Android will restart the service if it can)")
            node.stop()
        } else {
            DiagLog.i(tag, "service destroyed")
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagLog.i(tag, "app swiped away from recents - service keeps running, node status: " + node.statusLine())
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- node listener: keep the notification text current ------------------------------------

    override fun onPeers(peers: List<Peer>) { updateNotification(consumerStatus()) }
    override fun onMessagesChanged() { updateNotification(consumerStatus()) }
    override fun onStatus(status: String) { updateNotification(status); checkApproval() }

    private var approvalNotified = false
    /**
     * v0.13.3: ONE notification for everything waiting, rendered from the inbox.
     * Returns false when Android would not show it, so the request stays visible
     * in Gagner instead of disappearing with the alert.
     */
    private fun notifyAlert(a: net.prok.proknet.core.ProviderInbox.Alert): Boolean {
        if (!notificationsAllowed()) { DiagLog.w(tag, "request alert not shown: notifications are not permitted; the Gagner card still has it"); return false }
        return try {
            val share = PendingIntent.getService(this, 3, Intent(this, ProkNetService::class.java).setAction(ACTION_SHARE_NOW).putExtra("request", a.requestIds.firstOrNull() ?: ""),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", "earn"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ALERT) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, getString(R.string.notif_alert_channel), NotificationManager.IMPORTANCE_HIGH))
            val action = Notification.Action.Builder(R.drawable.ic_notify, getString(R.string.notif_share_action), share).build()
            nm.notify(NOTIF_REQUEST, Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_notify).setContentTitle(a.title).setContentText(a.text)
                .setStyle(Notification.BigTextStyle().bigText(a.text))
                .setContentIntent(open).addAction(action).setAutoCancel(true).setCategory(Notification.CATEGORY_RECOMMENDATION).build())
            DiagLog.i(tag, "request alert posted: " + a.title)
            true
        } catch (e: Exception) { DiagLog.w(tag, "request alert: " + e); false }
    }

    private fun notificationsAllowed(): Boolean = try {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.areNotificationsEnabled()
    } catch (e: Exception) { true }

    private fun clearAlert() {
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_REQUEST) } catch (_: Exception) {}
    }

    private val periodicSync = object : Runnable {
        override fun run() {
            if (!running) return
            val net = ProkNetApp.network(this@ProkNetService)
            net.sweep()
            net.syncNow("periodic")
            main.postDelayed(this, NetworkNode.PERIODIC_MS)
        }
    }

    /** The Wi-Fi join dialog only appears while a ProkNet screen is in front: ask the user to open the app. */
    private fun checkApproval() {
        val need = node.wifi.approvalNeeded && !ProkNetApp.appVisible()
        if (need && !approvalNotified) {
            approvalNotified = true
            try {
                val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ALERT) == null)
                    nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, getString(R.string.notif_alert_channel), NotificationManager.IMPORTANCE_HIGH))
                nm.notify(NOTIF_APPROVAL, Notification.Builder(this, CHANNEL_ALERT)
                    .setSmallIcon(R.drawable.ic_notify).setContentTitle(getString(R.string.notif_wifi_title))
                    .setContentText(getString(R.string.notif_wifi_text))
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_CALL).build())
                DiagLog.i(tag, "approval notification posted (app not visible)")
            } catch (e: Exception) { DiagLog.w(tag, "approval notification: " + e) }
        } else if (!node.wifi.approvalNeeded && approvalNotified) {
            approvalNotified = false
            try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_APPROVAL) } catch (_: Exception) {}
        }
    }

    private fun updateNotification(text: String) {
        if (text == lastNotifText || notifPending) return
        notifPending = true
        main.postDelayed({
            notifPending = false
            val t = consumerStatus()
            if (t == lastNotifText) return@postDelayed
            lastNotifText = t
            try {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(t))
            } catch (e: Exception) { DiagLog.w(tag, "notify: " + e) }
        }, 1000)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            ch.description = getString(R.string.notif_channel_desc)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    /**
     * v0.9.2: what the phone owner reads in the shade. The full engineering
     * status line still goes to the log, never here.
     */
    private fun consumerStatus(): String {
        val seller = ProductState.seller(node.sellOn, node.gateway.state)
        if (seller != ProductState.Seller.OFF) return ProductState.sellerTitle(seller)
        val buyer = ProductState.buyer(node.buyerWanted != null, node.wifi.phase, node.wifi.linkedPeer != null,
            node.tunnel.state, net.prok.proknet.vpn.ProkVpnService.running, node.tunnel.lastError)
        if (buyer != ProductState.Buyer.IDLE) return ProductState.buyerTitle(buyer)
        val nearby = node.peers().count { it.inRange }
        val offers = node.offers().size
        return if (nearby == 0) getString(R.string.notif_idle_alone)
        else getString(R.string.notif_idle, if (nearby == 1) getString(R.string.home_people_one) else getString(R.string.home_people_many, nearby), offers)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProkNetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notif_title, node.identity.displayName))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "net.prok.proknet.START"
        const val ACTION_STOP = "net.prok.proknet.STOP"
        const val CHANNEL_ID = "proknet_node"
        const val CHANNEL_ALERT = "proknet_alert"
        const val NOTIF_ID = 1001
        const val NOTIF_APPROVAL = 1002
        const val NOTIF_REQUEST = 1003
        const val ACTION_SHARE_NOW = "net.prok.proknet.SHARE_NOW"

        /** True between onCreate and onDestroy of the service instance. */
        @Volatile var running = false
            private set

        fun start(context: Context) {
            val i = Intent(context, ProkNetService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ProkNetService::class.java).setAction(ACTION_STOP)
            context.startService(i)
        }
    }
}
