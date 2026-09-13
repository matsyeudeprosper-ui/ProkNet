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
            else -> startNode()
        }
        return START_STICKY
    }

    private fun startNode() {
        createChannel()
        val notif = buildNotification("Starting...")
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
        updateNotification(node.statusLine())
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

    override fun onPeers(peers: List<Peer>) { updateNotification(node.statusLine()) }
    override fun onMessagesChanged() { updateNotification(node.statusLine()) }
    override fun onStatus(status: String) { updateNotification(status) }

    private fun updateNotification(text: String) {
        if (text == lastNotifText || notifPending) return
        notifPending = true
        main.postDelayed({
            notifPending = false
            val t = node.statusLine()
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
            val ch = NotificationChannel(CHANNEL_ID, "ProkNet node", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Shown while ProkNet is discovering and delivering in the background"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
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
            .setContentTitle("ProkNet running (" + node.identity.displayName + ")")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop ProkNet", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "net.prok.proknet.START"
        const val ACTION_STOP = "net.prok.proknet.STOP"
        const val CHANNEL_ID = "proknet_node"
        const val NOTIF_ID = 1001

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
