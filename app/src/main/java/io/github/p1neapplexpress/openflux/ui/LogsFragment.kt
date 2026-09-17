package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.CrashHandler
import io.github.p1neapplexpress.openflux.util.Logx
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class LogsFragment : BaseFragment() {

    companion object {
        private const val MAX_LINES = 512
        private const val FLUSH_INTERVAL_MS = 200L
    }

    private lateinit var textView: TextView
    private lateinit var scrollView: ScrollView
    private var autoScroll = true
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val lineColor = ForegroundColorSpan(0xFF666666.toInt())
    private val tsColor = ForegroundColorSpan(0xFF888888.toInt())

    private val pending = ConcurrentLinkedQueue<String>()
    private var totalLines = 0
    private var flushScheduled = false

    override fun onNewEvent(ev: AppEvent) = Unit

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_logs, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        textView = view.findViewById(R.id.logs)
        scrollView = view.findViewById(R.id.log_scroll)

        view.findViewById<TextView>(R.id.btn_clear).setOnClickListener {
            pending.clear(); totalLines = 0; textView.text = ""
        }

        val autoBtn = view.findViewById<TextView>(R.id.btn_autoscroll)
        autoBtn.text = getString(R.string.auto_scroll)
        autoBtn.setTextColor(ContextCompat.getColor(requireContext(), R.color.log_green))
        autoBtn.setOnClickListener {
            autoScroll = !autoScroll
            autoBtn.setTextColor(ContextCompat.getColor(requireContext(),
                if (autoScroll) R.color.log_green else R.color.log_gray))
            if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }

        view.findViewById<TextView>(R.id.btn_crashlog).setOnClickListener {
            showCrashLogPicker()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            EventBus.events.collect { ev ->
                if (ev is AppEvent.LogMessage) enqueue(ev.message)
            }
        }
    }

    private fun showCrashLogPicker() {
        val ctx = requireContext()
        val crashFiles = CrashHandler.dir(ctx).listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val fullLog = Logx.file()
        val entries = buildList {
            crashFiles.forEach { add(it.name to it) }
            if (fullLog != null && fullLog.exists()) add("full app_log.txt" to fullLog)
        }

        if (entries.isEmpty()) {
            Toast.makeText(ctx, R.string.no_crash_logs, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.crash_logs_title)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                showFileContent(entries[which].second)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showFileContent(file: File) {
        val ctx = requireContext()
        val content = runCatching { file.readText() }.getOrElse { "(failed to read ${file.name}: ${it.message})" }

        val textView = TextView(ctx).apply {
            text = content
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
        }
        val scroll = ScrollView(ctx).apply { addView(textView) }

        AlertDialog.Builder(ctx)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(R.string.share) { _, _ -> shareText(file.name, content) }
            .setNeutralButton(R.string.copy) { _, _ -> copyToClipboard(file.name, content) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareText(subject: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun copyToClipboard(label: String, content: String) {
        val ctx = requireContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, content))
        Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun enqueue(message: String) {
        
        message.split('\n').forEach { if (it.isNotEmpty()) pending.offer(it) }
        if (!flushScheduled) {
            flushScheduled = true
            textView.postDelayed({ flushPending(); flushScheduled = false }, FLUSH_INTERVAL_MS)
        }
    }

    private fun flushPending() {
        if (pending.isEmpty()) return

        val toAppend = SpannableStringBuilder()
        var appended = 0
        while (appended < 64) {
            val line = pending.poll() ?: break
            if (appended > 0) toAppend.append('\n')
            val lineNo = (totalLines + 1).toString().padStart(4)
            val base = toAppend.length
            toAppend.append(lineNo).append(' ')
            toAppend.setSpan(lineColor, base, base + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val stamped = "[${ts.format(Date())}] $line"
            val tsStart = toAppend.length
            toAppend.append(stamped)
            val tsEnd = stamped.indexOf(']')
            if (tsEnd > 0) toAppend.setSpan(tsColor, tsStart, tsStart + tsEnd + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            totalLines++
            appended++
        }

        textView.append(toAppend)

        
        val layout = textView.layout
        if (layout != null && textView.lineCount > MAX_LINES) {
            val cut = layout.getLineStart(textView.lineCount - MAX_LINES)
            textView.text = textView.text.subSequence(cut, textView.text.length)
        }

        if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }

        if (!pending.isEmpty() && !flushScheduled) {
            flushScheduled = true
            textView.postDelayed({ flushPending(); flushScheduled = false }, FLUSH_INTERVAL_MS)
        }
    }
}
