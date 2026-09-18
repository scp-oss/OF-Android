package io.github.p1neapplexpress.openflux.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity.RESULT_OK
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
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
import io.github.p1neapplexpress.openflux.data.ProfileKind
import io.github.p1neapplexpress.openflux.data.ProfileMeta
import io.github.p1neapplexpress.openflux.data.Profiles
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.CrashHandler
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.toUptimeHms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class HomeFragment : BaseFragment() {

    companion object {
        private const val PREFS = "home_ui"
        private const val KEY_VERBOSE = "verbose"
        private const val KEY_CONSOLE_OPEN = "console_open"
        private const val DOCK_LOG_HEIGHT_DP = 190
        private const val GITHUB_REPO = "scp-oss/OF-Android"
    }

    private val vm: TunnelsViewModel by activityViewModels()

    // ---- hero ----
    private lateinit var powerWrap: View
    private lateinit var powerRing: View
    private lateinit var powerBtn: View
    private lateinit var powerIcon: ImageView
    private lateinit var statusDot: View
    private lateinit var heroState: TextView
    private lateinit var uptimeText: TextView
    private var ringPulse: ObjectAnimator? = null

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
        powerRing = view.findViewById(R.id.powerRing)
        powerBtn = view.findViewById(R.id.powerBtn)
        powerIcon = view.findViewById(R.id.powerIcon)
        statusDot = view.findViewById(R.id.statusDot)
        heroState = view.findViewById(R.id.heroState)
        uptimeText = view.findViewById(R.id.uptimeText)

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
        powerBtn.setOnClickListener {
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

    private fun applyState(state: TunnelState) {
        powerWrap.isSelected = false
        when (state) {
            is TunnelState.Running -> {
                powerBtn.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_power_btn_on)
                powerIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent_ink))
                stopRingPulse()
                powerRing.alpha = 0f
            }
            is TunnelState.Connecting, is TunnelState.StartingTransport, is TunnelState.StartingTun2Socks -> {
                powerBtn.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_power_btn_connecting)
                powerIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.accent))
                startRingPulse()
            }
            else -> {
                powerBtn.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_power_btn_idle)
                powerIcon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.ink_faint))
                stopRingPulse()
                powerRing.alpha = 0f
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

        heroState.text = when (state) {
            is TunnelState.Running -> getString(R.string.running)
            is TunnelState.Connecting -> getString(R.string.connecting)
            is TunnelState.StartingTransport -> getString(R.string.starting_transport)
            is TunnelState.StartingTun2Socks -> getString(R.string.starting_tsocks)
            is TunnelState.Error -> state.message
            else -> getString(R.string.tap_to_connect)
        }

        uptimeText.isVisible = state is TunnelState.Running
        renderTrigger()
    }

    private fun startRingPulse() {
        if (ringPulse?.isRunning == true) return
        powerRing.alpha = 0.55f
        powerRing.scaleX = 0.86f
        powerRing.scaleY = 0.86f
        ringPulse = ObjectAnimator.ofPropertyValuesHolder(
            powerRing,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.86f, 1.28f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.86f, 1.28f),
            android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0f),
        ).apply {
            duration = 1600
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopRingPulse() {
        ringPulse?.cancel()
        ringPulse = null
    }

    private fun renderUptime(seconds: Long) {
        if (seconds <= 0L) return
        uptimeText.text = seconds.toUptimeHms()
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
        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { ev -> if (ev is AppEvent.LogMessage) appendLog(ev.message) }
            }
        }
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
        v.findViewById<View>(R.id.btnCrashLogs).setOnClickListener {
            showCrashLogPicker()
        }
        v.findViewById<View>(R.id.btnAboutOpen).setOnClickListener {
            dialog.dismiss()
            openAboutSheet()
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
    // Crash log picker (moved here from the old LogsFragment)
    // ==================================================================

    private fun showCrashLogPicker() {
        val ctx = requireContext()
        val crashFiles = CrashHandler.dir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val fullLog = Logx.file()
        val entries = buildList {
            crashFiles.forEach { add(it.name to it) }
            if (fullLog != null && fullLog.exists()) add("full app_log.txt" to fullLog)
        }
        if (entries.isEmpty()) {
            Toast.makeText(ctx, R.string.no_crash_logs, Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.crash_logs_title)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> showFileContent(entries[which].second) }
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
        val scroll = android.widget.ScrollView(ctx).apply { addView(textView) }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton(R.string.share) { _, _ -> shareText(file.name, content) }
            .setNeutralButton(R.string.copy) { _, _ -> copyToClipboard(file.name, content) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareText(subject: String, content: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
            putExtra(android.content.Intent.EXTRA_TEXT, content)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun copyToClipboard(label: String, content: String) {
        val ctx = requireContext()
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, content))
        Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
