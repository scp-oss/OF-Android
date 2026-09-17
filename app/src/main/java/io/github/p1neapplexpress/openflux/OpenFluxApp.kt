package io.github.p1neapplexpress.openflux

import android.app.Application
import io.github.p1neapplexpress.openflux.util.CrashHandler
import io.github.p1neapplexpress.openflux.util.Logx

class OpenFluxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Always verbose, not just BuildConfig.DEBUG builds — this fork is
        // specifically for chasing down the mailru-transport crash, and a
        // release build with debug-only logging hides exactly the detail
        // needed for that.
        Logx.init(isDebug = true, appContext = this)
        Thread.setDefaultUncaughtExceptionHandler(
            CrashHandler(this, Thread.getDefaultUncaughtExceptionHandler())
        )
    }
}
