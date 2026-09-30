package io.github.p1neapplexpress.openflux.util

/**
 * IPv4 CIDR-block arithmetic for the "direct" (bypass-VPN) domain list.
 *
 * Android's VpnService has no native per-domain routing — VpnService.
 * Builder only understands IP/CIDR routes and per-app allow/deny. Once any
 * route covers an IP, packets to it enter the TUN; there is no "exclude
 * this IP" primitive. The standard technique (used by every Android VPN
 * app with a "bypass this site" feature) is therefore: instead of routing
 * the single block "0.0.0.0/0" (or whichever base set Routes.kt would
 * otherwise use) into the tunnel, route the COMPLEMENT of that block minus
 * the excluded IPs — i.e. every sub-range that does NOT contain an
 * excluded address. [subtract] computes exactly that.
 *
 * Correctness of [subtract] was verified with a brute-force Python
 * cross-check (enumerating every address in a base block and comparing
 * against the block set this same algorithm produces) before this file was
 * written — see the session history; a bug here doesn't just fail to
 * bypass a domain, it can miscompute routing for the WHOLE tunnel, so this
 * was checked before being trusted, not after.
 */
object Cidr {

    /**
     * Returns the minimal covering set of CIDR blocks representing [base]
     * minus every address in [excludedIps]. Any string in either input
     * that isn't a parseable IPv4 dotted-quad/CIDR is skipped rather than
     * thrown on — a single bad entry (typo in a user-entered IP, or a
     * malformed line from a synced list) shouldn't take down the whole
     * route computation.
     */
    fun subtract(base: List<Pair<String, Int>>, excludedIps: Set<String>): List<Pair<String, Int>> {
        val excludedLongs = excludedIps.mapNotNull { ipToLong(it) }
        if (excludedLongs.isEmpty()) return base

        return base.flatMap { (network, prefix) ->
            val start = ipToLong(network) ?: return@flatMap emptyList()
            if (prefix !in 0..32) return@flatMap emptyList()
            val size = 1L shl (32 - prefix)
            val relevant = excludedLongs.filter { it in start until (start + size) }
            splitExcluding(start, prefix, relevant).map { (s, p) -> longToIp(s) to p }
        }
    }

    // Recursively bisects [networkStart]/[prefix] to remove every address
    // in [excluded], returning every resulting sub-block that contains
    // none of them. Depth is bounded by 32 (IPv4 bit width) regardless of
    // how many addresses are excluded.
    private fun splitExcluding(networkStart: Long, prefix: Int, excluded: List<Long>): List<Pair<Long, Int>> {
        if (excluded.isEmpty()) return listOf(networkStart to prefix)
        if (prefix == 32) return emptyList() // this single address is itself excluded

        val half = 1L shl (32 - prefix - 1)
        val lowStart = networkStart
        val highStart = networkStart + half
        val lowExcluded = excluded.filter { it in lowStart until (lowStart + half) }
        val highExcluded = excluded.filter { it in highStart until (highStart + half) }

        return splitExcluding(lowStart, prefix + 1, lowExcluded) +
            splitExcluding(highStart, prefix + 1, highExcluded)
    }

    private fun ipToLong(ip: String): Long? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        var result = 0L
        for (p in parts) {
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            result = (result shl 8) or v.toLong()
        }
        return result
    }

    private fun longToIp(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
}
