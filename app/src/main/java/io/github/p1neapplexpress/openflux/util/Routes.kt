package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.net.VpnService
import io.github.p1neapplexpress.openflux.R

object Routes {
    // directExcludeIps: already-resolved IPv4 addresses (see
    // DirectListResolver) for the "direct" bypass-VPN domain list.
    // VpnService.Builder has no "exclude this IP" primitive — once any
    // route covers an address, packets to it enter the TUN — so bypassing
    // these means routing the COMPLEMENT of the normal route set instead
    // (see Cidr.subtract's own doc comment for why and how). Composes with
    // either routing mode: subtracts from whichever base set (full
    // 0.0.0.0/0, or the ROUTE_CHN curated array) would otherwise apply.
    fun addRoutes(
        context: Context,
        builder: VpnService.Builder,
        name: String?,
        directExcludeIps: Set<String> = emptySet(),
    ) {
        val routes: Array<String> = if (Constants.ROUTE_CHN == name) {
            context.resources.getStringArray(R.array.simple_route)
        } else {
            arrayOf("0.0.0.0/0")
        }

        val base = routes.mapNotNull { r ->
            val parts = r.split("/")
            if (parts.size != 2) return@mapNotNull null
            val network = parts[0]
            if (network.startsWith("127")) return@mapNotNull null
            val prefix = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (prefix !in 0..32) return@mapNotNull null
            network to prefix
        }

        val finalRoutes = if (directExcludeIps.isNotEmpty()) {
            Cidr.subtract(base, directExcludeIps)
        } else {
            base
        }

        for ((network, prefix) in finalRoutes) {
            builder.addRoute(network, prefix)
        }
    }
}
