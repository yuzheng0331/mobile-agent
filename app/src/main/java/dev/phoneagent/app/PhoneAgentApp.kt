package dev.phoneagent.app

import android.app.Application
import android.os.Handler
import android.os.Looper

/**
 * The process, whatever started it: the accessibility service binding, the user opening the app, vivo restarting it
 * after a kill. Nothing restarts it reliably after a kill on vivo (a periodic job never got the process started), so
 * the user opening the app is what brings a killed accessibility service back.
 */
class PhoneAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The system binds our service right after starting us when it means to: give it that moment first.
        Handler(Looper.getMainLooper()).postDelayed({ AgentService.revive(this) }, REVIVE_DELAY_MS)
    }

    private companion object {
        const val REVIVE_DELAY_MS = 3_000L
    }
}
