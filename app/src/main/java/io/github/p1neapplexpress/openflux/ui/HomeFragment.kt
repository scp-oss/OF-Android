package io.github.p1neapplexpress.openflux.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity.RESULT_OK
import android.content.ClipboardManager
import android.content.Context
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import io.github.p1neapplexpress.openflux.BuildConfig
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.DnsProvider
import io.github.p1neapplexpress.openflux.data.ProfileKind
import io.github.p1neapplexpress.openflux.data.ProfileMeta
import io.github.p1neapplexpress.openflux.data.Profiles
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.ui.widget.AuroraView
import io.github.p1neapplexpress.openflux.ui.widget.PulseRingsView
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.CrashHandler
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.toUptimeHms
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale

class HomeFragment : BaseFragment() {

    companion object {
        private val PREFS = Constants.PREF_HOME_UI
        private const val KEY_VERBOSE = "verbose"
        private const val KEY_CONSOLE_OPEN = "console_open"
        private const val DOCK_LOG_HEIGHT_DP = 190
        private const val GITHUB_REPO = "scp-oss/OF-Android"

        // Direct request: a one-tap way to send every log file to this
        // fixed address, instead of the generic share-chooser the user
        // has to manually pick Gmail and address by hand for each time.
        private const val LOGS_EMAIL = "mixaa1998@gmail.com"
    }

    private val vm: TunnelsViewModel by activityViewModels()

    // ---- hero ----
    private lateinit var powerWrap: View
    private lateinit var aurora: AuroraView
    private lateinit var pulseRings: PulseRingsView
    private lateinit var ringOuter: View
    private lateinit var ringMid: View
    private lateinit var connectButton: View
    private lateinit var powerIcon: ImageView
    private lateinit var statusDot: View
    private lateinit var heroState: TextView
    private lateinit var uptimeText: TextView
    private lateinit var speedText: TextView
    private lateinit var dnsInUseText: TextView
    private var speedJob: Job? = null
    private var rotationAnim: ObjectAnimator? = null
    private var breathAnim: ObjectAnimator? = null
    private var currentVisualState: TunnelState? = null

    // ---- profile dropdown ----
    private lateinit var profileTrigger: View
    private lateinit var triggerAvatar: TextView
    private lateinit var triggerName: TextView
    private lateinit var triggerDot: View
    private lateinit var triggerSub: TextView
    private lateinit var triggerChev: ImageView
    private lateinit var profilePanel: LinearLayout
    private var dropdownOpen = false

    // ---- availability ----
    private lateinit var btnAvailability: View
    private lateinit var availResultBox: View
    private lateinit var availSpinner: ProgressBar
    private lateinit var availResult: TextView

    // ---- log dock ----
    private lateinit var dock: View
    private lateinit var dockLogScroll: View
    private lateinit var logInner: TextView
    private lateinit var dockBar: View
    private lateinit var dockChev: ImageView
    private lateinit var dockPreview: TextView
    private lateinit var switchVerbose: SwitchMaterial
    private var consoleOpen = false
    private var verboseUi = false

    // ---- add/edit sheet state (only valid while that sheet is showing) ----
    private var addSheet: BottomSheetDialog? = null
    private var sheetMode = "auto" // "auto" | "edit"
    private var sheetTargetType: TransportType? = null
    private var sheetFieldKind = ProfileKind.LINK
    private var autoMatchType: TransportType? = null
    private var qrTarget: ((String) -> Unit)? = null

    private val prefs by lazy { requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.startCurrent()
        else Toast.makeText(requireContext(), R.string.vpn_permission_required, Toast.LENGTH_LONG).show()
    }

    private val qrScanner = registerForActivityResult(ScanQRCode()) { result ->
        val raw = (result as? QRResult.QRSuccess)?.content?.rawValue ?: return@registerForActivityResult
        qrTarget?.invoke(raw)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_home, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        wireTopbar(view)
        wireHero(view)
        wireProfileDropdown()
        wireAvailability()
        wireDock()
        restorePrefs()
        observe()
    }

    private fun bindViews(view: View) {
        powerWrap = view.findViewById(R.id.powerWrap)
        aurora = view.findViewById(R.id.aurora)
        pulseRings = view.findViewById(R.id.pulseRings)
        ringOuter = view.findViewById(R.id.ringOuter)
        ringMid = view.findViewById(R.id.ringMid)
        connectButton = view.findViewById(R.id.connectButton)
        powerIcon = view.findViewById(R.id.powerIcon)
        statusDot = view.findViewById(R.id.statusDot)
        heroState = view.findViewById(R.id.heroState)
        uptimeText = view.findViewById(R.id.uptimeText)
        speedText = view.findViewById(R.id.speedText)
        dnsInUseText = view.findViewById(R.id.dnsInUseText)

        profileTrigger = view.findViewById(R.id.profileTrigger)
        triggerAvatar = view.findViewById(R.id.triggerAvatar)
        triggerName = view.findViewById(R.id.triggerName)
        triggerDot = view.findViewById(R.id.triggerDot)
        triggerSub = view.findViewById(R.id.triggerSub)
        triggerChev = view.findViewById(R.id.triggerChev)
        profilePanel = view.findViewById(R.id.profilePanel)

        btnAvailability = view.findViewById(R.id.btnAvailability)
        availResultBox = view.findViewById(R.id.availResultBox)
        availSpinner = view.findViewById(R.id.availSpinner)
        availResult = view.findViewById(R.id.availResult)

        dock = view.findViewById(R.id.dock)
        dockLogScroll = view.findViewById(R.id.dockLogScroll)
        logInner = view.findViewById(R.id.logInner)
        dockBar = view.findViewById(R.id.dockBar)
        dockChev = view.findViewById(R.id.dockChev)
        dockPreview = view.findViewById(R.id.dockPreview)
        switchVerbose = view.findViewById(R.id.switchVerbose)
    }

    // ---------------------------------------------------------------- topbar

    private fun wireTopbar(view: View) {
        view.findViewById<View>(R.id.btnSettings).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openSettingsSheet()
        }
        view.findViewById<View>(R.id.btnAddLink).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openAddSheet()
        }
    }

    // ---------------------------------------------------------------- hero / power

    private fun wireHero(view: View) {
        connectButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (vm.active.value) {
                is TunnelState.Running -> vm.stop()
                is TunnelState.Idle, is TunnelState.Error -> requestVpnAndStart()
                else -> Unit
            }
        }
    }

    private fun requestVpnAndStart() {
        val intent = VpnService.prepare(requireActivity())
        if (intent != null) vpnPermission.launch(intent) else vm.startCurrent()
    }

    // Ported as-is from the old TunnelsFragment — this is the visual the
    // user specifically asked to keep from the previous UI: aurora glow +
    // radar pulse rings + a breathing/rotating static ring pair + icon
    // scale/pop/shake, driven by TunnelState.color the same way it always
    // was. Only the surrounding screen changed, not this.
    private fun applyState(state: TunnelState) {
        if (state == currentVisualState) { renderTrigger(); return }
        currentVisualState = state

        val color = state.color
        aurora.setStateColor(color)

        when (state) {
            is TunnelState.Idle -> {
                heroState.text = getString(R.string.tap_to_connect)
                heroState.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink))
                aurora.setIntensity(0.4f)
                pulseRings.stop()
                stopRotation()
                startBreath()
                animateIcon(scale = 1f, alpha = 0.92f)
            }
            is TunnelState.Connecting, is TunnelState.StartingTransport, is TunnelState.StartingTun2Socks -> {
                heroState.text = when (state) {
                    is TunnelState.StartingTransport -> getString(R.string.starting_transport)
                    is TunnelState.StartingTun2Socks -> getString(R.string.starting_tsocks)
                    else -> getString(R.string.connecting)
                }
                heroState.setTextColor(color)
                aurora.setIntensity(0.75f)
                startRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1800L)
                stopBreath()
                animateIcon(scale = 0.94f, alpha = 0.7f)
            }
            is TunnelState.Running -> {
                heroState.text = getString(R.string.running)
                heroState.setTextColor(color)
                aurora.setIntensity(1f)
                stopRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1400L)
                startBreath()
                animateIcon(scale = 1.08f, alpha = 1f)
                popButton()
            }
            is TunnelState.Error -> {
                heroState.text = state.message
                heroState.setTextColor(color)
                aurora.setIntensity(0.9f)
                pulseRings.stop()
                stopRotation()
                stopBreath()
                animateIcon(scale = 1f, alpha = 1f)
                shake()
            }
        }

        val dotColor = ContextCompat.getColor(
            requireContext(),
            when (state) {
                is TunnelState.Running -> R.color.success
                is TunnelState.Connecting, is TunnelState.StartingTransport, is TunnelState.StartingTun2Socks -> R.color.warning
                is TunnelState.Error -> R.color.danger
                else -> R.color.ink_faint
            }
        )
        statusDot.background?.mutate()?.setTint(dotColor)

        uptimeText.isVisible = state is TunnelState.Running
        speedText.isVisible = state is TunnelState.Running
        dnsInUseText.isVisible = state is TunnelState.Running
        if (state is TunnelState.Running) {
            startSpeedUpdates()
            renderDnsInUse()
        } else {
            stopSpeedUpdates()
        }
        renderTrigger()
    }

    // Whichever DNS provider was configured (see openDnsSettingsSheet())
    // at the moment this connection started — read once here rather than
    // polled, since a mid-connection settings change only takes effect on
    // the next connect (same "applies on next connect" contract the DNS
    // settings sheet's own hint text states), so it can't change under a
    // running tunnel. Read from the same prefs TunnelsViewModel.
    // currentDotSpec() resolves when it actually builds VPNConfig, so this
    // always names the provider actually in effect, not just what's
    // currently selected in Settings.
    private fun renderDnsInUse() {
        val providerId = prefs.getString(Constants.PREF_DNS_PROVIDER, null)
        val provider = DnsProvider.byId(providerId)
        val label = if (provider == DnsProvider.CUSTOM) {
            prefs.getString(Constants.PREF_DNS_CUSTOM_SPEC, null)?.takeIf { it.isNotBlank() }
                ?: getString(provider.labelRes)
        } else {
            getString(provider.labelRes)
        }
        dnsInUseText.text = getString(R.string.dns_in_use, label)
    }

    private fun animateIcon(scale: Float, alpha: Float) {
        powerIcon.animate().cancel()
        powerIcon.animate().scaleX(scale).scaleY(scale).alpha(alpha)
            .setDuration(320L).setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    private fun popButton() {
        connectButton.animate().cancel()
        connectButton.scaleX = 0.94f; connectButton.scaleY = 0.94f
        connectButton.animate().scaleX(1f).scaleY(1f).setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun shake() {
        val props = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -14f, 14f, -10f, 10f, -4f, 4f, 0f)
        ObjectAnimator.ofPropertyValuesHolder(connectButton, props).apply {
            duration = 520L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun startRotation() {
        if (rotationAnim?.isRunning == true) return
        rotationAnim = ObjectAnimator.ofFloat(ringOuter, View.ROTATION, 0f, 360f).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRotation() {
        rotationAnim?.cancel()
        rotationAnim = null
        ringOuter.rotation = 0f
    }

    private fun startBreath() {
        if (breathAnim?.isRunning == true) return
        breathAnim = ObjectAnimator.ofPropertyValuesHolder(
            ringOuter,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.02f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.02f),
        ).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBreath() {
        breathAnim?.cancel()
        breathAnim = null
        ringOuter.scaleX = 1f
        ringOuter.scaleY = 1f
    }

    private fun renderUptime(seconds: Long) {
        if (seconds <= 0L) return
        uptimeText.text = seconds.toUptimeHms()
    }

    // ==================================================================
    // Speed + ping — direct request, alongside the DNS settings above.
    // Throughput reuses the exact same per-UID TrafficStats counters
    // VpnNotificationManager already polls for the pinned notification
    // (see that class's own speedUpdater) — this is a second, independent
    // reader of the same OS counters, purely UI-layer, no service/backend
    // change needed. Ping is a plain TCP-connect timing against whichever
    // DNS target is currently active (the chosen DoT server's real
    // addr:port, or the plain-DNS default if DoT is off) — a raw ICMP
    // ping needs root on Android, this is the honest reachable proxy for
    // "is the DNS path responsive," and it's meaningful precisely because
    // the DNS settings sheet right above this is what decides that path.
    // ==================================================================

    private fun startSpeedUpdates() {
        if (speedJob?.isActive == true) return
        speedJob = viewLifecycleOwner.lifecycleScope.launch {
            val uid = Process.myUid()
            var lastRx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
            var lastTx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            var lastAt = SystemClock.elapsedRealtime()
            var pingMs: Long? = null
            var tick = 0
            while (isActive) {
                delay(1000L)
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastAt).coerceAtLeast(1)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
                val rxPerSec = (rx - lastRx) * 1000 / elapsed
                val txPerSec = (tx - lastTx) * 1000 / elapsed
                lastRx = rx; lastTx = tx; lastAt = now

                // Ping is a blocking connect — measure every ~3rd tick, not
                // every second, so it can't visibly stall the throughput
                // readout while it's in flight.
                tick++
                if (tick % 3 == 1) {
                    pingMs = withContext(Dispatchers.IO) { measurePing() }
                }

                val pingLabel = pingMs?.let { getString(R.string.ping_label, it.toInt()) }
                    ?: getString(R.string.ping_pending)
                speedText.text = "↑ ${formatSpeed(txPerSec)}   ↓ ${formatSpeed(rxPerSec)}   •   $pingLabel"
            }
        }
    }

    private fun stopSpeedUpdates() {
        speedJob?.cancel()
        speedJob = null
    }

    // Matches VpnNotificationManager.formatSpeed()'s own unlocalized B/s
    // KB/s MB/s units exactly, so the in-app readout and the pinned
    // notification's speed indicator never disagree on formatting.
    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    private fun measurePing(): Long? {
        val (host, port) = currentDnsPingTarget()
        return runCatching {
            val start = SystemClock.elapsedRealtime()
            Socket().use { it.connect(InetSocketAddress(host, port), 2000) }
            SystemClock.elapsedRealtime() - start
        }.getOrNull()
    }

    // Same provider/custom-spec prefs the DNS settings sheet writes,
    // resolved into a host:port to actually measure. Falls back to
    // VPNConfig's own plain-DNS default (not a re-hardcoded literal) when
    // DoT is off, so this can never silently drift from what the tunnel
    // itself actually uses.
    private fun currentDnsPingTarget(): Pair<String, Int> {
        val providerId = prefs.getString(Constants.PREF_DNS_PROVIDER, null)
        val customSpec = prefs.getString(Constants.PREF_DNS_CUSTOM_SPEC, null)
        val spec = DnsProvider.resolveSpec(providerId, customSpec)
        if (spec.isBlank()) {
            val default = VPNConfig(name = "")
            return default.dns to default.dnsPort
        }
        val addrPart = spec.substringBefore("@")
        val host = addrPart.substringBefore(":")
        val port = addrPart.substringAfter(":", "853").toIntOrNull() ?: 853
        return host to port
    }

    // ---------------------------------------------------------------- profile dropdown

    private fun wireProfileDropdown() {
        profileTrigger.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (dropdownOpen) closeDropdown() else openDropdown()
        }
    }

    private fun openDropdown() {
        dropdownOpen = true
        profilePanel.isVisible = true
        triggerChev.animate().rotation(180f).setDuration(160).start()
    }

    private fun closeDropdown() {
        dropdownOpen = false
        profilePanel.isVisible = false
        triggerChev.animate().rotation(0f).setDuration(160).start()
    }

    private fun renderProfilePanel() {
        profilePanel.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        val list = vm.profiles.value
        list.forEachIndexed { index, ui ->
            val row = inflater.inflate(R.layout.item_profile_row, profilePanel, false)
            row.findViewById<TextView>(R.id.rowAvatar).text = ui.meta.initials
            row.findViewById<TextView>(R.id.rowName).text = ui.meta.displayName
            row.findViewById<TextView>(R.id.rowSub).text = ui.summary
            row.setBackgroundColor(
                if (ui.meta.transport == vm.activeProfileType.value)
                    ContextCompat.getColor(requireContext(), R.color.accent_wash)
                else android.graphics.Color.TRANSPARENT
            )
            row.setOnClickListener {
                selectProfile(ui.meta.transport)
                closeDropdown()
            }
            row.findViewById<View>(R.id.rowEdit).setOnClickListener {
                closeDropdown()
                openEditSheet(ui.meta.transport)
            }
            profilePanel.addView(row)
            if (index < list.lastIndex) {
                val divider = View(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.border))
                }
                profilePanel.addView(divider)
            }
        }
    }

    private fun selectProfile(type: TransportType) {
        val wasRunning = vm.active.value is TunnelState.Running
        vm.selectProfile(type)
        renderTrigger()
        renderProfilePanel()
        val meta = Profiles.of(type)
        if (wasRunning) {
            Logx.i("Home", getString(R.string.reconnecting_via, meta.displayName))
            vm.startCurrent()
        } else {
            Logx.i("Home", getString(R.string.profile_selected, meta.displayName))
        }
    }

    private fun renderTrigger() {
        val meta = vm.activeProfileMeta()
        val ui = vm.profiles.value.firstOrNull { it.meta.transport == meta.transport }
        triggerAvatar.text = meta.initials
        triggerName.text = meta.displayName
        val running = vm.active.value is TunnelState.Running
        triggerDot.background?.mutate()?.setTint(
            ContextCompat.getColor(requireContext(), if (running) R.color.success else R.color.ink_faint)
        )
        val summary = ui?.summary ?: getString(R.string.not_configured)
        triggerSub.text = when {
            vm.active.value is TunnelState.Running -> "$summary · ${getString(R.string.running).lowercase()}"
            vm.active.value.isActive -> "$summary · ${getString(R.string.connecting).lowercase()}"
            else -> summary
        }
    }

    // ---------------------------------------------------------------- availability

    private fun wireAvailability() {
        btnAvailability.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.testProfile(vm.activeProfileType.value)
        }
    }

    private fun renderTestResult(result: TestResult?) {
        if (result == null || result.type != vm.activeProfileType.value) {
            availResultBox.isVisible = false
            return
        }
        availResultBox.isVisible = true
        when (result) {
            is TestResult.Running -> {
                availSpinner.isVisible = true
                availResultBox.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_test_result_pending)
                availResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.ink_muted))
                availResult.text = getString(R.string.testing_availability)
                if (verboseUi) Logx.d("Home", "test request via SOCKS5 → ${result.type.name} …")
            }
            is TestResult.Success -> {
                availSpinner.isVisible = false
                availResultBox.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_test_result_ok)
                availResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.success))
                availResult.text = getString(R.string.test_ok, result.elapsedMs)
            }
            is TestResult.Failure -> {
                availSpinner.isVisible = false
                availResultBox.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_test_result_fail)
                availResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
                availResult.text = when (result.reason) {
                    "not_configured" -> getString(R.string.test_fail_not_configured)
                    "already_connected" -> getString(R.string.test_fail_busy)
                    "timeout", "bind_timeout" -> getString(R.string.test_fail_timeout)
                    else -> getString(R.string.test_fail_generic)
                }
            }
        }
    }

    // ---------------------------------------------------------------- log dock

    private fun wireDock() {
        dockBar.setOnClickListener { toggleConsole() }
        switchVerbose.setOnCheckedChangeListener { _, checked ->
            verboseUi = checked
            prefs.edit().putBoolean(KEY_VERBOSE, checked).apply()
        }
        applyDockInsets()
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { ev -> if (ev is AppEvent.LogMessage) appendLog(ev.message) }
            }
        }
    }

    // Same fix as the old bottom bars: the dock sits a fixed 14dp above
    // this layout's own bottom edge, which on some devices (gesture-nav
    // MIUI, seen with screenshots) isn't enough clearance from the real
    // system nav bar — the log ends up drawn partly under it. Read the
    // actual bottom system-bar inset and add it on top of the XML margin.
    private fun applyDockInsets() {
        val baseMargin = (dock.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(dock) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseMargin + bars.bottom
            }
            insets
        }
        dock.requestApplyInsets()
    }

    private fun toggleConsole() {
        consoleOpen = !consoleOpen
        prefs.edit().putBoolean(KEY_CONSOLE_OPEN, consoleOpen).apply()
        animateDock(consoleOpen)
    }

    private fun animateDock(open: Boolean) {
        val target = if (open) (DOCK_LOG_HEIGHT_DP * resources.displayMetrics.density).toInt() else 0
        val current = dockLogScroll.layoutParams.height.coerceAtLeast(0)
        ValueAnimator.ofInt(current, target).apply {
            duration = 220
            addUpdateListener { a ->
                dockLogScroll.layoutParams = dockLogScroll.layoutParams.apply { height = a.animatedValue as Int }
                dockLogScroll.requestLayout()
            }
            start()
        }
        dockChev.animate().rotation(if (open) 180f else 0f).setDuration(160).start()
    }

    private fun appendLog(message: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logInner.append("\n[$ts] $message")
        dockPreview.text = message
        dockLogScroll.post { (dockLogScroll as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun restorePrefs() {
        verboseUi = prefs.getBoolean(KEY_VERBOSE, false)
        switchVerbose.isChecked = verboseUi
        consoleOpen = prefs.getBoolean(KEY_CONSOLE_OPEN, false)
        if (consoleOpen) {
            dockLogScroll.layoutParams = dockLogScroll.layoutParams.apply {
                height = (DOCK_LOG_HEIGHT_DP * resources.displayMetrics.density).toInt()
            }
            dockChev.rotation = 180f
        }
        dockPreview.text = "OpenFlux ready"
    }

    // ---------------------------------------------------------------- observe

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.profiles.collect {
                        renderProfilePanel()
                        renderTrigger()
                    }
                }
                launch { vm.active.collect { applyState(it) } }
                launch { vm.uptimeSeconds.collect { renderUptime(it) } }
                launch { vm.activeProfileType.collect { renderTrigger(); renderTestResult(vm.testResult.value) } }
                launch { vm.testResult.collect { renderTestResult(it) } }
            }
        }
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    // ==================================================================
    // Add / Edit sheet
    // ==================================================================

    private fun openAddSheet() {
        sheetMode = "auto"
        sheetTargetType = null
        autoMatchType = null
        showAddEditSheet()
    }

    private fun openEditSheet(type: TransportType) {
        sheetMode = "edit"
        sheetTargetType = type
        showAddEditSheet()
    }

    private fun showAddEditSheet() {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_add_edit, null)
        dialog.setContentView(v)
        addSheet = dialog

        val title = v.findViewById<TextView>(R.id.addSheetTitle)
        val linkFields = v.findViewById<View>(R.id.sheetLinkFields)
        val credFields = v.findViewById<View>(R.id.sheetCredFields)
        val linkInput = v.findViewById<EditText>(R.id.linkInput)
        val credToken = v.findViewById<EditText>(R.id.credToken)
        val credUserId = v.findViewById<EditText>(R.id.credUserId)
        val detectChip = v.findViewById<View>(R.id.detectChip)
        val detectText = v.findViewById<TextView>(R.id.detectText)
        val testBtn = v.findViewById<View>(R.id.btnTestAdd)
        val saveBtn = v.findViewById<View>(R.id.btnSaveAdd)
        val saveBtnText = v.findViewById<TextView>(R.id.btnSaveAddText)
        val testResultBox = v.findViewById<View>(R.id.addTestResultBox)
        val testSpinner = v.findViewById<ProgressBar>(R.id.addTestSpinner)
        val testResultText = v.findViewById<TextView>(R.id.addTestResult)

        fun currentKind(): ProfileKind =
            sheetTargetType?.let { Profiles.of(it).kind } ?: ProfileKind.LINK

        fun applyFieldKind(kind: ProfileKind) {
            sheetFieldKind = kind
            linkFields.isVisible = kind == ProfileKind.LINK
            credFields.isVisible = kind == ProfileKind.CREDENTIALS
        }

        fun refreshState() {
            testResultBox.isVisible = false
            if (sheetFieldKind == ProfileKind.CREDENTIALS) {
                val target = sheetTargetType?.let { Profiles.of(it) } ?: return
                val tok = credToken.text.toString().trim()
                val uid = credUserId.text.toString().trim()
                val valid = tok.isNotEmpty() && uid.matches(Regex("^[0-9]+$"))
                detectChip.isVisible = true
                detectChip.background = ContextCompat.getDrawable(ctx, R.drawable.bg_detect_chip)
                detectText.text = getString(R.string.editing_profile, target.displayName)
                testBtn.alpha = if (valid) 1f else 0.5f
                testBtn.isEnabled = valid
                saveBtn.alpha = if (valid) 1f else 0.5f
                saveBtn.isEnabled = valid
                return
            }

            val link = linkInput.text.toString().trim()
            if (link.isEmpty()) {
                detectChip.isVisible = false
                testBtn.alpha = 0.5f; testBtn.isEnabled = false
                saveBtn.alpha = 0.5f; saveBtn.isEnabled = false
                autoMatchType = null
                return
            }
            val valid = isValidUrl(link)

            if (sheetMode == "edit") {
                val target = sheetTargetType?.let { Profiles.of(it) } ?: return
                detectChip.isVisible = true
                detectChip.background = ContextCompat.getDrawable(ctx, R.drawable.bg_detect_chip)
                detectText.text = getString(R.string.editing_profile, target.displayName)
                testBtn.alpha = if (valid) 1f else 0.5f; testBtn.isEnabled = valid
                saveBtn.alpha = if (valid) 1f else 0.5f; saveBtn.isEnabled = valid
            } else {
                val matched = if (valid) detectFromLink(link) else null
                detectChip.isVisible = true
                if (matched != null) {
                    detectChip.background = ContextCompat.getDrawable(ctx, R.drawable.bg_detect_chip)
                    detectText.text = getString(R.string.detected_profile, matched.displayName)
                    autoMatchType = matched.transport
                } else {
                    detectChip.background = ContextCompat.getDrawable(ctx, R.drawable.bg_detect_chip_unknown)
                    detectText.text = if (valid) getString(R.string.domain_not_recognized) else getString(R.string.invalid_link)
                    autoMatchType = null
                }
                testBtn.alpha = if (valid) 1f else 0.5f; testBtn.isEnabled = valid
                val canSave = valid && matched != null
                saveBtn.alpha = if (canSave) 1f else 0.5f; saveBtn.isEnabled = canSave
            }
        }

        linkInput.doAfterTextChanged { refreshState() }
        credToken.doAfterTextChanged { refreshState() }
        credUserId.doAfterTextChanged { refreshState() }

        if (sheetMode == "edit") {
            val type = requireNotNull(sheetTargetType)
            val meta = Profiles.of(type)
            applyFieldKind(meta.kind)
            title.text = getString(R.string.edit_link_title, meta.displayName)
            saveBtnText.text = getString(R.string.update_profile)
            if (meta.kind == ProfileKind.CREDENTIALS) {
                credToken.setText(vm.tokenFor(type))
                credUserId.setText(vm.userIdFor(type))
            } else {
                linkInput.setText(vm.urlFor(type))
            }
        } else {
            applyFieldKind(ProfileKind.LINK)
            title.text = getString(R.string.add_link_title)
            saveBtnText.text = getString(R.string.save)
        }
        refreshState()

        v.findViewById<View>(R.id.closeAdd).setOnClickListener { dialog.dismiss() }

        v.findViewById<View>(R.id.btnPasteClipboard).setOnClickListener {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (text.isNullOrEmpty()) {
                Toast.makeText(ctx, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                val target = if (currentKind() == ProfileKind.CREDENTIALS) credToken else linkInput
                target.setText(text.trim())
            }
        }

        v.findViewById<View>(R.id.btnScanQr).setOnClickListener {
            qrTarget = { text ->
                val target = if (currentKind() == ProfileKind.CREDENTIALS) credToken else linkInput
                target.setText(text)
            }
            qrScanner.launch(null)
        }

        testBtn.setOnClickListener {
            testResultBox.isVisible = true
            testSpinner.isVisible = true
            testResultText.setTextColor(ContextCompat.getColor(ctx, R.color.ink_muted))
            testResultText.text = getString(R.string.testing_availability)

            val type = sheetTargetType ?: autoMatchType
            if (type == null) {
                // Not yet saved anywhere to test against a real profile slot —
                // save first is required for a real test (test reuses the
                // saved Tunnel via TunnelsViewModel.testProfile).
                testSpinner.isVisible = false
                testResultText.setTextColor(ContextCompat.getColor(ctx, R.color.danger))
                testResultText.text = getString(R.string.test_fail_generic)
                return@setOnClickListener
            }
            persistCurrentFields(type, linkInput, credToken, credUserId)
            // testProfile() sets testResult to Running(type) synchronously
            // before returning, so starting the one-shot wait AFTER this
            // call can never match a stale terminal result left over from
            // a previous test of the same profile.
            vm.testProfile(type)
            observeInlineTestResult(type, testSpinner, testResultText)
        }

        saveBtn.setOnClickListener {
            val type = sheetTargetType ?: autoMatchType ?: return@setOnClickListener
            persistCurrentFields(type, linkInput, credToken, credUserId)
            Logx.i("Home", (if (sheetMode == "edit") "config updated for " else "config added for ") + Profiles.of(type).displayName)
            dialog.dismiss()
        }

        dialog.setOnDismissListener { addSheet = null; qrTarget = null }
        dialog.show()
    }

    private fun persistCurrentFields(type: TransportType, linkInput: EditText, credToken: EditText, credUserId: EditText) {
        val meta = Profiles.of(type)
        if (meta.kind == ProfileKind.CREDENTIALS) {
            vm.saveProfileCredentials(type, credToken.text.toString().trim(), credUserId.text.toString().trim())
        } else {
            vm.saveProfileLink(type, linkInput.text.toString().trim())
        }
    }

    private fun observeInlineTestResult(type: TransportType, spinner: ProgressBar, resultText: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            val r = vm.testResult.first { it != null && it.type == type && it !is TestResult.Running }
            spinner.isVisible = false
            when (r) {
                is TestResult.Success -> {
                    resultText.setTextColor(ContextCompat.getColor(requireContext(), R.color.success))
                    resultText.text = getString(R.string.test_ok, r.elapsedMs)
                }
                is TestResult.Failure -> {
                    resultText.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
                    resultText.text = getString(R.string.test_fail_generic)
                }
                else -> Unit
            }
        }
    }

    private fun isValidUrl(s: String): Boolean {
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return false
        return (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrEmpty()
    }

    private fun detectFromLink(link: String): ProfileMeta? {
        val host = runCatching { Uri.parse(link).host }.getOrNull() ?: return null
        return Profiles.detectByHost(host)
    }

    // ==================================================================
    // Settings sheet
    // ==================================================================

    private fun openSettingsSheet() {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_settings, null)
        dialog.setContentView(v)

        val port = v.findViewById<EditText>(R.id.socksPort)
        val udp = v.findViewById<SwitchMaterial>(R.id.switchUdp)
        port.setText(prefs.getString("socks_port", "10808"))
        udp.isChecked = prefs.getBoolean("udp_quic", false)

        v.findViewById<View>(R.id.closeSettings).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.btnSaveSettings).setOnClickListener {
            prefs.edit()
                .putString("socks_port", port.text.toString().trim())
                .putBoolean("udp_quic", udp.isChecked)
                .apply()
            Logx.i("Home", "settings saved: SOCKS5 port ${port.text}, UDP/QUIC ${if (udp.isChecked) "on" else "off"}")
            dialog.dismiss()
        }
        v.findViewById<View>(R.id.btnDnsSettings).setOnClickListener {
            dialog.dismiss()
            openDnsSettingsSheet()
        }
        v.findViewById<View>(R.id.btnEmailLogs).setOnClickListener {
            emailAllLogs()
        }
        v.findViewById<View>(R.id.btnAboutOpen).setOnClickListener {
            dialog.dismiss()
            openAboutSheet()
        }

        dialog.show()
    }

    // ==================================================================
    // DNS settings sheet — plain DNS or one of the fixed DNS-over-TLS
    // presets (Cloudflare/Yandex/Google/Quad9), or a custom "addr@sni"
    // spec. See data/DnsProviders.kt and native/dot-relay for how the
    // choice actually reaches the tunnel.
    // ==================================================================

    private fun openDnsSettingsSheet() {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_dns_settings, null)
        dialog.setContentView(v)

        val list = v.findViewById<LinearLayout>(R.id.dnsProviderList)
        val customFields = v.findViewById<View>(R.id.dnsCustomFields)
        val customInput = v.findViewById<EditText>(R.id.dnsCustomInput)

        var selected = DnsProvider.byId(prefs.getString(Constants.PREF_DNS_PROVIDER, null))
        customInput.setText(prefs.getString(Constants.PREF_DNS_CUSTOM_SPEC, ""))

        fun renderList() {
            list.removeAllViews()
            val inflater = LayoutInflater.from(ctx)
            DnsProvider.entries.forEachIndexed { index, provider ->
                val row = inflater.inflate(R.layout.item_dns_provider_row, list, false)
                row.findViewById<TextView>(R.id.dnsRowLabel).setText(provider.labelRes)
                row.findViewById<View>(R.id.dnsRowCheck).isVisible = provider == selected
                row.setOnClickListener {
                    selected = provider
                    customFields.isVisible = provider == DnsProvider.CUSTOM
                    renderList()
                }
                list.addView(row)
                if (index < DnsProvider.entries.lastIndex) {
                    list.addView(View(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                        setBackgroundColor(ContextCompat.getColor(ctx, R.color.border))
                    })
                }
            }
        }
        renderList()
        customFields.isVisible = selected == DnsProvider.CUSTOM

        v.findViewById<View>(R.id.closeDns).setOnClickListener { dialog.dismiss() }
        v.findViewById<View>(R.id.btnSaveDns).setOnClickListener {
            prefs.edit()
                .putString(Constants.PREF_DNS_PROVIDER, selected.id)
                .putString(Constants.PREF_DNS_CUSTOM_SPEC, customInput.text.toString().trim())
                .apply()
            Logx.i("Home", "DNS settings saved: provider=${selected.id}")
            dialog.dismiss()
        }

        dialog.show()
    }

    // ==================================================================
    // About sheet — version from BuildConfig, description fetched from GitHub
    // ==================================================================

    @Serializable
    private data class GithubRelease(val name: String? = null, val body: String? = null)

    private fun openAboutSheet() {
        val ctx = requireContext()
        val dialog = BottomSheetDialog(ctx)
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_about, null)
        dialog.setContentView(v)

        v.findViewById<TextView>(R.id.aboutVersion).text = "v${BuildConfig.VERSION_NAME}"
        v.findViewById<View>(R.id.closeAbout).setOnClickListener { dialog.dismiss() }

        val spinner = v.findViewById<ProgressBar>(R.id.aboutDescSpinner)
        val descText = v.findViewById<TextView>(R.id.aboutDesc)

        viewLifecycleOwner.lifecycleScope.launch {
            val body = fetchReleaseDescription()
            spinner.isVisible = false
            descText.isVisible = true
            descText.text = body ?: getString(R.string.about_desc_unavailable)
        }

        dialog.show()
    }

    private suspend fun fetchReleaseDescription(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/tags/v${BuildConfig.VERSION_NAME}")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.inputStream.bufferedReader().use { it.readText() }.let { text ->
                val release = Json { ignoreUnknownKeys = true }.decodeFromString<GithubRelease>(text)
                release.body?.takeIf { it.isNotBlank() } ?: release.name
            }
        }.getOrNull()
    }

    // ==================================================================
    // Send logs by email (replaces the old raw-text crash-log viewer —
    // per direct request, crash logs now go straight to email instead of
    // being shown in a text dialog first)
    // ==================================================================

    /**
     * Every crash report plus the full app_log.txt, attached to one
     * outgoing email Intent addressed to [LOGS_EMAIL]. Files are handed
     * off via FileProvider content:// Uris (see res/xml/file_paths.xml) —
     * a bare file:// path to app-private storage is blocked cross-process
     * since API 24. Hands off to whatever mail app the user has (Gmail on
     * their phone); this app never talks SMTP itself.
     */
    private fun emailAllLogs() {
        val ctx = requireContext()
        val crashFiles = CrashHandler.dir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val fullLog = Logx.file()?.takeIf { it.exists() }
        val files = crashFiles + listOfNotNull(fullLog)
        if (files.isEmpty()) {
            Toast.makeText(ctx, R.string.no_crash_logs, Toast.LENGTH_SHORT).show()
            return
        }

        val authority = "${ctx.packageName}.fileprovider"
        val uris = files.mapNotNull { f ->
            runCatching { FileProvider.getUriForFile(ctx, authority, f) }.getOrNull()
        }
        if (uris.isEmpty()) {
            Toast.makeText(ctx, R.string.email_logs_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val subject = "OpenFlux logs — ${Build.MANUFACTURER} ${Build.MODEL}, " +
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "message/rfc822"
            putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(LOGS_EMAIL))
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, getString(R.string.email_logs_body))
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.email_logs_chooser)))
    }
}
