package io.github.p1neapplexpress.openflux.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Resolves the "direct" (bypass-VPN) domain list to IPv4 addresses before
 * the tunnel is established — see Cidr.kt's own doc comment for why IP
 * resolution, not domain matching, is the only mechanism Android's
 * VpnService actually offers for this. Must run BEFORE
 * VpnServiceController.configure() builds routes, using the device's
 * normal (not-yet-tunneled) DNS resolver.
 *
 * A domain behind a CDN that rotates IPs can resolve to a different
 * address on a later connect than the one excluded here — this is
 * re-resolved fresh on every connect (never cached across sessions) to
 * keep that drift as small as it can be, but it's an inherent limitation
 * of IP-based bypass, not something this can fully close.
 */
object DirectListResolver {
    private const val TAG = "DirectListResolver"
    private const val DEFAULT_TIMEOUT_MS = 3000L

    /**
     * Resolves every domain concurrently, each bounded by [timeoutMs] —
     * run in parallel (not sequentially) so N slow/unreachable domains cost
     * roughly one timeout period total, not N of them stacked up. A
     * failure or timeout on one domain never affects the others; it's just
     * logged and excluded from the result.
     */
    suspend fun resolve(domains: Collection<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Set<String> = coroutineScope {
        domains.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { domain ->
                async(Dispatchers.IO) {
                    val addresses = withTimeoutOrNull(timeoutMs) {
                        runCatching { InetAddress.getAllByName(domain) }.getOrNull()
                    }
                    if (addresses == null) {
                        Logx.w(TAG, "resolve timed out or failed for $domain")
                        emptyList()
                    } else {
                        addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                    }
                }
            }
            .awaitAll()
            .flatten()
            .toSet()
    }
}
