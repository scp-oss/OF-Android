package io.github.p1neapplexpress.openflux.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.data.DnsProvider
import io.github.p1neapplexpress.openflux.data.ProfileKind
import io.github.p1neapplexpress.openflux.data.ProfileMeta
import io.github.p1neapplexpress.openflux.data.Profiles
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelRepository
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import io.github.p1neapplexpress.openflux.vpn.VpnIntentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProfileUiState(
    val meta: ProfileMeta,
    val configured: Boolean,
    val summary: String,
    val tunnel: Tunnel?,
)

sealed interface TestResult {
    val type: TransportType
    data class Running(override val type: TransportType) : TestResult
    data class Success(override val type: TransportType, val elapsedMs: Long) : TestResult
    data class Failure(override val type: TransportType, val reason: String) : TestResult
}

class TunnelsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TunnelsViewModel"
        private const val TEST_BIND_TIMEOUT_MS = 5_000L
        private const val TEST_TRANSPORT_TIMEOUT_MS = 8_000L
    }

    private val repo = TunnelRepository(app)

    private var service: IUnifiedService? = null
    private var bound = false
    private var activeTunnelData: Tunnel? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUnifiedService.Stub.asInterface(binder)
            bound = true
            Logx.d(TAG, "service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Logx.d(TAG, "service disconnected")
            if (_active.value !is TunnelState.Idle) {
                _active.value = TunnelState.Idle
                stopUptimeCounter()
                refresh()
            }
        }
    }

    private val _profiles = MutableStateFlow<List<ProfileUiState>>(emptyList())
    val profiles: StateFlow<List<ProfileUiState>> = _profiles.asStateFlow()

    private val _activeProfileType = MutableStateFlow(TransportType.yandex)
    val activeProfileType: StateFlow<TransportType> = _activeProfileType.asStateFlow()

    private val _active = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val active: StateFlow<TunnelState> = _active.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private var uptimeJob: Job? = null

    init {
        val savedId = repo.getSelectedId()
        _activeProfileType.value = TransportType.entries.getOrNull(savedId?.toInt() ?: 0) ?: TransportType.yandex
        refresh()
    }

    fun activeProfileMeta(): ProfileMeta = Profiles.of(_activeProfileType.value)

    // Resolves the DNS provider chosen in HomeFragment's DNS settings sheet
    // (persisted to the same "home_ui" prefs it writes to — see
    // Constants.PREF_HOME_UI) into the spec string VPNConfig.dotSpec needs.
    // Read fresh on every startTunnel() call rather than cached, so a
    // settings change takes effect on the next connect without needing a
    // ViewModel restart.
    private fun currentDotSpec(): String {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences(Constants.PREF_HOME_UI, Context.MODE_PRIVATE)
        val providerId = prefs.getString(Constants.PREF_DNS_PROVIDER, null)
        val customSpec = prefs.getString(Constants.PREF_DNS_CUSTOM_SPEC, null)
        return DnsProvider.resolveSpec(providerId, customSpec)
    }

    fun startCurrent() {
        val tunnel = _active.value.tunnel
            ?: repo.loadForType(_activeProfileType.value)
            ?: return
        startTunnel(tunnel)
    }

    fun startTunnel(tunnel: Tunnel) {
        val running = _active.value
        if (running is TunnelState.Running && running.tunnel == tunnel) return
        if (running.isActive) stop()

        _active.value = TunnelState.Connecting(tunnel)

        val ctx = getApplication<Application>()
        val cfg = VPNConfig(name = tunnel.name, dotSpec = currentDotSpec())
        val intent = VpnIntentFactory.build(ctx, cfg)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }

        ctx.bindService(
            Intent(ctx, SocksVpnService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )

        activeTunnelData = tunnel

        CoroutineScope(Dispatchers.IO).launch {

            var attempts = 0
            while (!bound && attempts < 100) {
                delay(50)
                attempts++
            }
            if (!bound || service == null) {
                Logx.e(TAG, "failed to bind to service")
                _active.value = TunnelState.Error("Service not connected")
                return@launch
            }
            Logx.i(TAG, "service bound, starting transport")

            _active.value = TunnelState.StartingTransport(tunnel)
            try {
                service?.startOpenFluxNative(
                    tunnel.transportType,
                    tunnel.transportConnPayload.toTypedArray()
                )
            } catch (e: Exception) {
                Logx.e(TAG, "startOpenFluxNative failed", e)
                _active.value = TunnelState.Error("Transport failed: ${e.message}")
                return@launch
            }

            var transportReady = false
            for (i in 1..40) {
                delay(250)
                try {
                    if (service?.isFServiceRunning() == true) {
                        transportReady = true
                        Logx.i(TAG, "transport started after ${i * 250}ms")
                        break
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "isFServiceRunning threw", e)
                }
            }
            if (!transportReady) {
                Logx.e(TAG, "transport did not start")
                _active.value = TunnelState.Error("Transport did not start")
                return@launch
            }

            Logx.i(TAG, "starting tun2socks")
            _active.value = TunnelState.StartingTun2Socks(tunnel)
            try {
                service?.startTun2Socks()
            } catch (e: Exception) {
                Logx.e(TAG, "startTun2Socks failed", e)
                _active.value = TunnelState.Error("tun2socks failed: ${e.message}")
                return@launch
            }

            var vpnReady = false
            for (i in 1..40) {
                delay(250)
                try {
                    if (service?.isVpnRunning() == true) {
                        vpnReady = true
                        Logx.i(TAG, "tun2socks started after ${i * 250}ms")
                        break
                    }
                } catch (e: Exception) {
                    Logx.e(TAG, "isVpnRunning threw", e)
                }
            }

            if (vpnReady) {
                _active.value = TunnelState.Running(tunnel)
                startUptimeCounter()
            } else {
                Logx.e(TAG, "tun2socks did not start")
                _active.value = TunnelState.Error("tun2socks did not start")
            }
            refresh()
        }
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service?.stopOpenFluxNative()
                service?.stopVpn()
            } catch (e: Exception) {
                Logx.e(TAG, "stop failed", e)
            }
            val ctx = getApplication<Application>()
            try { ctx.unbindService(connection) } catch (_: Exception) {}
            bound = false
            service = null
            activeTunnelData = null
            _active.value = TunnelState.Idle
            stopUptimeCounter()
            refresh()
        }
    }

    fun refresh() {
        _profiles.value = Profiles.ALL.map { meta ->
            val tunnel = repo.loadForType(meta.transport)
            ProfileUiState(
                meta = meta,
                configured = tunnel != null,
                summary = summaryFor(meta, tunnel),
                tunnel = tunnel,
            )
        }
    }

    fun selectProfile(type: TransportType) {
        if (type == _activeProfileType.value) return
        if (_active.value.isActive) stop()
        _activeProfileType.value = type
        repo.setSelectedId(type.ordinal.toLong())
    }

    /** Saves a LINK-kind profile's connection URL and (re)builds its CLI payload. */
    fun saveProfileLink(type: TransportType, url: String) {
        val payload = buildList {
            add("--client"); add("--transport"); add(type.name)
            add("--url"); add(url)
            add("--debug")
        }
        val tunnel = Tunnel(
            id = type.ordinal.toLong(),
            name = Profiles.of(type).displayName,
            transportType = type.name,
            transportConnPayload = payload,
        )
        persistProfile(type, tunnel)
    }

    /** Saves a CREDENTIALS-kind profile (MAX) token/user id and (re)builds its CLI payload. */
    fun saveProfileCredentials(type: TransportType, token: String, userId: String) {
        val payload = buildList {
            add("--client"); add("--transport"); add(type.name)
            add("--maxToken"); add(token)
            add("--maxUid"); add(userId)
            add("--debug")
        }
        val tunnel = Tunnel(
            id = type.ordinal.toLong(),
            name = Profiles.of(type).displayName,
            transportType = type.name,
            transportConnPayload = payload,
        )
        persistProfile(type, tunnel)
    }

    private fun persistProfile(type: TransportType, tunnel: Tunnel) {
        val wasActiveAndRunning = _active.value.isActive && _activeProfileType.value == type
        if (wasActiveAndRunning) stop()
        repo.saveForType(tunnel)
        refresh()
    }

    /**
     * Starts just the transport (not the full tun2socks/VPN routing step),
     * waits for it to report connected, then tears everything back down —
     * a real reachability check, not a simulation, reusing the exact same
     * IUnifiedService surface startTunnel() uses. Refuses while a real
     * tunnel is already active: NativeProcessSupervisor.start() silently
     * no-ops on a second call while one is already running, which would
     * make this falsely report success against whatever IS already up.
     */
    fun testProfile(type: TransportType) {
        if (_active.value.isActive) {
            _testResult.value = TestResult.Failure(type, "already_connected")
            return
        }
        val tunnel = repo.loadForType(type) ?: run {
            _testResult.value = TestResult.Failure(type, "not_configured")
            return
        }

        _testResult.value = TestResult.Running(type)
        val ctx = getApplication<Application>()
        var testService: IUnifiedService? = null
        var testBound = false
        val testConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                testService = IUnifiedService.Stub.asInterface(binder)
                testBound = true
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                testService = null
                testBound = false
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            var ok = false
            try {
                val intent = VpnIntentFactory.build(ctx, VPNConfig(name = tunnel.name))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
                ctx.bindService(Intent(ctx, SocksVpnService::class.java), testConnection, Context.BIND_AUTO_CREATE)

                var waited = 0L
                while (!testBound && waited < TEST_BIND_TIMEOUT_MS) {
                    delay(50); waited += 50
                }
                if (!testBound || testService == null) {
                    _testResult.value = TestResult.Failure(type, "bind_timeout")
                    return@launch
                }

                testService?.startOpenFluxNative(tunnel.transportType, tunnel.transportConnPayload.toTypedArray())

                waited = 0L
                while (waited < TEST_TRANSPORT_TIMEOUT_MS) {
                    delay(500); waited += 500
                    if (testService?.isFServiceRunning() == true) { ok = true; break }
                }
            } catch (e: Exception) {
                Logx.e(TAG, "testProfile failed", e)
            } finally {
                runCatching { testService?.stopOpenFluxNative() }
                runCatching { testService?.stopVpn() }
                runCatching { ctx.unbindService(testConnection) }
                val elapsed = System.currentTimeMillis() - startedAt
                _testResult.value = if (ok) TestResult.Success(type, elapsed) else TestResult.Failure(type, "timeout")
            }
        }
    }

    fun clearTestResult() {
        _testResult.value = null
    }

    private fun summaryFor(meta: ProfileMeta, tunnel: Tunnel?): String {
        if (tunnel == null) return "не настроено"
        return when (meta.kind) {
            ProfileKind.CREDENTIALS -> {
                val uid = argValue(tunnel.transportConnPayload, "--maxUid")
                if (uid.isNullOrEmpty()) "не настроено" else "ID ···" + uid.takeLast(4)
            }
            ProfileKind.LINK -> {
                val url = argValue(tunnel.transportConnPayload, "--url")
                if (url.isNullOrEmpty()) "не настроено" else (runCatching { Uri.parse(url).host }.getOrNull() ?: "ссылка")
            }
        }
    }

    private fun argValue(payload: List<String>, flag: String): String? {
        val idx = payload.indexOf(flag)
        return if (idx >= 0 && idx + 1 < payload.size) payload[idx + 1] else null
    }

    fun urlFor(type: TransportType): String =
        repo.loadForType(type)?.let { argValue(it.transportConnPayload, "--url") } ?: ""

    fun tokenFor(type: TransportType): String =
        repo.loadForType(type)?.let { argValue(it.transportConnPayload, "--maxToken") } ?: ""

    fun userIdFor(type: TransportType): String =
        repo.loadForType(type)?.let { argValue(it.transportConnPayload, "--maxUid") } ?: ""

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        _uptimeSeconds.value = 0L
        uptimeJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                _uptimeSeconds.value = (System.currentTimeMillis() - startedAt) / 1000L
                delay(1_000L)
            }
        }
    }

    private fun stopUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = null
        _uptimeSeconds.value = 0L
    }

    override fun onCleared() {
        super.onCleared()
        stopUptimeCounter()
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}
