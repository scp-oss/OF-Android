package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.os.Build
import io.github.p1neapplexpress.openflux.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a readable crash report to disk before handing the exception to
 * whatever handler Android had installed (so the normal system crash
 * dialog still appears — this only adds a copy that survives the process
 * dying, since the in-memory Logx/EventBus log does not).
 */
class CrashHandler(
    private val appContext: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    companion object {
        const val DIR_NAME = "crash_logs"
        private const val MAX_KEPT = 10
        private val TS = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

        fun dir(context: Context): File = File(context.filesDir, DIR_NAME)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            writeCrashReport(thread, ex)
        } catch (writeError: Throwable) {
            android.util.Log.e("CrashHandler", "failed to write crash report", writeError)
        }
        previous?.uncaughtException(thread, ex)
    }

    private fun writeCrashReport(thread: Thread, ex: Throwable) {
        val d = dir(appContext).apply { mkdirs() }
        val file = File(d, "crash_${TS.format(Date())}.txt")

        val report = buildString {
            appendLine("OpenFlux crash report")
            appendLine("time: ${Date()}")
            appendLine("crashed thread: ${thread.name}")
            appendLine("app version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine(
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
            appendLine()
            appendLine("--- recent app log ---")
            appendLine(Logx.tail())
            appendLine()
            appendLine("--- stack trace ---")
            appendLine(android.util.Log.getStackTraceString(ex))
        }
        file.writeText(report)

        d.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEPT)
            ?.forEach { it.delete() }
    }
}
