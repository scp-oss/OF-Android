package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.util.Log
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logx {
    private const val LOG_FILE_NAME = "app_log.txt"
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val TRIM_TO_BYTES = 256 * 1024
    private const val TAIL_CHARS = 8 * 1024

    private var verbose = false
    private var logFile: File? = null
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /**
     * [isDebug] gates the extra-chatty `d()` calls; everything else is
     * always logged. [appContext], when given, also persists every line
     * to disk so a crash (which wipes the in-memory EventBus log) still
     * leaves a trail — see CrashHandler, which reads [tail] into its report.
     */
    fun init(isDebug: Boolean, appContext: Context? = null) {
        verbose = isDebug
        appContext?.let { logFile = File(it.filesDir, LOG_FILE_NAME) }
    }

    fun d(tag: String, msg: String) {
        if (!verbose) return
        Log.d(tag, msg)
        emit("[$tag] $msg")
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        emit("[$tag] $msg")
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        emit("[W/$tag] $msg" + (tr?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        emit("[E/$tag] $msg" + (tr?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    /** Never logs value — only key presence. */
    fun secret(tag: String, key: String) {
        if (!verbose) return
        Log.d(tag, "secret present: $key")
    }

    /** The persistent log file, once [init] has been given a context. */
    fun file(): File? = logFile

    /** Last ~[TAIL_CHARS] characters of the persistent log, for a crash report. */
    fun tail(): String {
        val f = logFile ?: return "(no log file — Logx.init() was never given a context)"
        return try {
            if (!f.exists()) "(log file not created yet)" else f.readText().takeLast(TAIL_CHARS)
        } catch (e: Exception) {
            "(failed to read log file: ${e.message})"
        }
    }

    private fun emit(message: String) {
        EventBus.dispatch(AppEvent.LogMessage(message))
        persist(message)
    }

    private fun persist(message: String) {
        val f = logFile ?: return
        try {
            f.appendText("[${ts.format(Date())}] $message\n")
            if (f.length() > MAX_FILE_BYTES) {
                f.writeText(f.readText().takeLast(TRIM_TO_BYTES))
            }
        } catch (_: Exception) {
            // Best-effort — never let logging itself crash the app.
        }
    }
}
