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

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        node = ProkNetNode(this)
        DiagLog.i("APP", "process started, node created (not running)")
    }

    companion object {
        fun node(context: android.content.Context): ProkNetNode =
            (context.applicationContext as ProkNetApp).node
    }
}
