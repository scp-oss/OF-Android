package io.github.p1neapplexpress.openflux.service

import android.content.Context
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.ProcessRunner
import java.io.File

class Tun2SocksLauncher(private val context: Context) {

    companion object {
        private const val TAG = "Tun2SocksLauncher"
        private const val SEND_FD_ATTEMPTS = 10
        private const val SEND_FD_BASE_DELAY_MS = 500L

        private const val NETIF_IPADDR = "26.26.26.2"
        private const val NETIF_NETMASK = "255.255.255.0"
        private const val NETIF_IP6ADDR = "fdfe:dcba:9876::2"
        private const val TUN_MTU = 1500
        private const val DNS_GW = "26.26.26.1:8091"
        private const val LOG_LEVEL = "3"

        // DNS-over-TLS: pdnsd itself has no TLS support (see dot-relay's
        // own doc comment), so when dotSpec is set, pdnsd's upstream is
        // repointed at this local relay instead of talking to a DNS
        // server directly. Loopback + a fixed port is fine — this is
        // strictly local, process-to-process on the device, never
        // reachable off-device.
        private const val DOT_RELAY_LISTEN = "127.0.0.1:8853"
        private const val DOT_RELAY_HOST = "127.0.0.1"
        private const val DOT_RELAY_PORT = 8853
    }

    fun start(
        fd: Int,
        server: String,
        port: Int,
        username: String?,
        password: String?,
        dns: String,
        dnsPort: Int,
        dotSpec: String?,
        ipv6: Boolean,
        udpgw: String?,
    ): Boolean {
        if (fd <= 0) {
            Logx.e(TAG, "invalid tun fd: $fd")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val pdnsdBin = "$nativeDir/libpdnsd.so"
        val tun2socksBin = "$nativeDir/libtun2socks.so"
        val dotRelayBin = "$nativeDir/libp1npplydtdot.so"

        val sockPath = File(context.applicationInfo.dataDir, "sock_path").apply {
            if (!exists()) createNewFile()
            setWritable(true, false)
            setReadable(true, false)
        }

        val (pdnsdUpstreamIp, pdnsdUpstreamPort) = if (!dotSpec.isNullOrBlank()) {
            startDotRelay(dotRelayBin, dotSpec)
            DOT_RELAY_HOST to DOT_RELAY_PORT
        } else {
            dns to dnsPort
        }

        makePdnsdConf(pdnsdUpstreamIp, pdnsdUpstreamPort)
        Logx.i(TAG, "starting pdnsd")
        ProcessRunner.execFireAndForget(
            command = listOf(pdnsdBin, "-c", "${context.filesDir}/pdnsd.conf"),
            workingDir = context.filesDir.absolutePath,
        )
        Thread.sleep(500L)

        
        Logx.i(TAG, "starting tun2socks")
        ProcessRunner.execFireAndForget(
            command = buildCommand(tun2socksBin, fd, server, port, username, password, ipv6, udpgw, sockPath),
            workingDir = context.filesDir.absolutePath,
        )
        Thread.sleep(500L)

        
        for (attempt in 1..SEND_FD_ATTEMPTS) {
            val r = NativeBridge.sendfd(fd, sockPath.absolutePath)
            if (r == 0) {
                Logx.i(TAG, "sendfd ok on attempt $attempt")
                return true
            }
            Logx.w(TAG, "sendfd attempt $attempt failed (ret=$r)")
            try {
                Thread.sleep(SEND_FD_BASE_DELAY_MS * attempt)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        Logx.e(TAG, "sendfd failed after $SEND_FD_ATTEMPTS attempts")
        return false
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        ProcessRunner.killPidFile("${context.filesDir}/tun2socks.pid")
        ProcessRunner.killPidFile("${context.filesDir}/pdnsd.pid")
        ProcessRunner.killPidFile("${context.filesDir}/dotrelay.pid")
        runCatching { File(context.applicationInfo.dataDir, "sock_path").delete() }
    }

    // Starts the DNS-over-TLS relay (native/dot-relay in this repo) that
    // pdnsd's upstream gets repointed at — pdnsd can't speak TLS itself, so
    // this sits between it and the real DoT server, relaying the identical
    // length-prefixed wire format DNS-over-TCP already uses (RFC 7858 §3.3).
    // dotSpec is passed straight through to the relay's own -servers flag —
    // a ";"-separated "addr[:port]@sni" list, parsed and validated there.
    private fun startDotRelay(bin: String, dotSpec: String) {
        Logx.i(TAG, "starting dot-relay")
        ProcessRunner.execFireAndForget(
            command = listOf(
                bin,
                "-listen", DOT_RELAY_LISTEN,
                "-servers", dotSpec,
                "-pidfile", "${context.filesDir}/dotrelay.pid",
            ),
            workingDir = context.filesDir.absolutePath,
        )
        // Give it time to bind its listener before pdnsd's first upstream
        // connection attempt — same pattern as the sleeps after pdnsd/
        // tun2socks below, just shorter: this only needs to win a race
        // against pdnsd starting, not settle a whole native subprocess.
        Thread.sleep(300L)
    }

    private fun buildCommand(
        bin: String,
        fd: Int,
        server: String,
        port: Int,
        user: String?,
        passwd: String?,
        ipv6: Boolean,
        udpgw: String?,
        sockPath: File,
    ): List<String> = buildList {
        add(bin)
        add("--netif-ipaddr"); add(NETIF_IPADDR)
        add("--netif-netmask"); add(NETIF_NETMASK)
        add("--socks-server-addr"); add("$server:$port")
        add("--tunfd"); add(fd.toString())
        add("--tunmtu"); add(TUN_MTU.toString())
        add("--loglevel"); add(LOG_LEVEL)
        add("--pid"); add("${context.filesDir}/tun2socks.pid")
        add("--sock"); add(sockPath.absolutePath)
        if (!user.isNullOrEmpty()) {
            add("--username"); add(user)
            add("--password"); add(passwd ?: "")
        }
        if (ipv6) { add("--netif-ip6addr"); add(NETIF_IP6ADDR) }
        add("--dnsgw"); add(DNS_GW)
        udpgw?.let { add("--udpgw-remote-server-addr"); add(it) }
    }

    private fun makePdnsdConf(dns: String, port: Int) {
        val conf = context.getString(io.github.p1neapplexpress.openflux.R.string.pdnsd_conf)
            .replace("{DIR}", context.filesDir.toString())
            .replace("{IP}", dns)
            .replace("{PORT}", port.toString())

        val f = File(context.filesDir, "pdnsd.conf")
        f.writeText(conf)

        val cache = File(context.filesDir, "pdnsd.cache")
        if (!cache.exists()) cache.createNewFile()
    }
}
