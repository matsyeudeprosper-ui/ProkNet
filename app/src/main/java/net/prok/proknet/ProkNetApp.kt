package net.prok.proknet

import android.app.Application
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.DiagLog

/**
 * Milestone 2B: the node lives for the whole process, owned by the
 * Application, driven by ProkNetService. The Activity only observes it.
 */
class ProkNetApp : Application() {
    lateinit var node: ProkNetNode
        private set
    /** v0.12: the phone as a coverage sensor and the GET INTERNET executor. */
    lateinit var coverage: net.prok.proknet.node.CoverageEngine
        private set

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        node = ProkNetNode(this)
        coverage = net.prok.proknet.node.CoverageEngine(this)
        coverage.attach(node)
        DiagLog.i("APP", "process started, node created (not running)")
    }

    companion object {
        fun node(context: android.content.Context): ProkNetNode =
            (context.applicationContext as ProkNetApp).node
        fun coverage(context: android.content.Context): net.prok.proknet.node.CoverageEngine =
            (context.applicationContext as ProkNetApp).coverage

        /** Number of ProkNet activities currently started (visible). The Wi-Fi join dialog needs one. */
        @Volatile var visibleActivities = 0
        fun appVisible(): Boolean = visibleActivities > 0
    }
}
