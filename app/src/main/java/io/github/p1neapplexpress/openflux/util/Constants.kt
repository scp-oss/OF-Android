package io.github.p1neapplexpress.openflux.util

object Constants {
    const val ROUTE_ALL = "all"
    const val ROUTE_CHN = "chn"

    private const val INTENT_PREFIX = "SOCKS"
    const val INTENT_NAME = INTENT_PREFIX + "NAME"
    const val INTENT_SERVER = INTENT_PREFIX + "SERV"
    const val INTENT_PORT = INTENT_PREFIX + "PORT"
    const val INTENT_USERNAME = INTENT_PREFIX + "UNAME"
    const val INTENT_PASSWORD = INTENT_PREFIX + "PASSWD"
    const val INTENT_ROUTE = INTENT_PREFIX + "ROUTE"
    const val INTENT_DNS = INTENT_PREFIX + "DNS"
    const val INTENT_DNS_PORT = INTENT_PREFIX + "DNSPORT"
    const val INTENT_DOT_SPEC = INTENT_PREFIX + "DOTSPEC"
    const val INTENT_PER_APP = INTENT_PREFIX + "PERAPP"
    const val INTENT_APP_BYPASS = INTENT_PREFIX + "APPBYPASS"
    const val INTENT_APP_LIST = INTENT_PREFIX + "APPLIST"
    const val INTENT_IPV6_PROXY = INTENT_PREFIX + "IPV6"
    const val INTENT_UDP_GW = INTENT_PREFIX + "UDPGW"

    // Action name for the "Отключить" button on the pinned VPN notification
    // (VpnNotificationManager) — a PendingIntent targeting SocksVpnService
    // itself with this action, handled in onStartCommand() as a stop
    // request rather than a (re-)configure request.
    const val ACTION_STOP_VPN = "io.github.p1neapplexpress.openflux.ACTION_STOP_VPN"

    const val PREF = "profile"
    const val PREF_PROFILE = "profile"
    const val PREF_LAST_PROFILE = "last_profile"
    const val PREF_TUNNELS_KEY = "tunnels"
    const val PREF_SELECTED_TUNNEL_ID = "selected_tunnel_id"

    // Shared SharedPreferences file for HomeFragment's own settings (SOCKS
    // port, UDP/QUIC, DNS provider, console state, …) — a single named
    // constant here so a second reader (TunnelsViewModel, resolving DNS
    // settings when it builds VPNConfig) can't drift from the file name
    // HomeFragment itself writes to.
    const val PREF_HOME_UI = "home_ui"
    const val PREF_DNS_PROVIDER = "dns_provider"
    const val PREF_DNS_CUSTOM_SPEC = "dns_custom_spec"
}
