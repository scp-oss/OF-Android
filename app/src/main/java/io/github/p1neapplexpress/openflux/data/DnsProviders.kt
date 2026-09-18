package io.github.p1neapplexpress.openflux.data

import io.github.p1neapplexpress.openflux.R

/**
 * Fixed DNS-over-TLS provider presets, plus "off" (plain DNS, the
 * pre-existing behavior) and "custom" (a user-entered spec). The [spec]
 * values use the same "addr@sni" format native/dot-relay's own
 * `-servers` flag parses — see that module's doc comment — reused
 * verbatim from a working iOS build of this same core
 * (saharev1/OpenFlux, ios-testflight branch) rather than guessed.
 *
 * [labelRes] rather than a raw string, matching how every other
 * user-visible label in this app goes through strings.xml (EN default +
 * RU translation) — a hardcoded Russian label here would quietly break
 * that parity.
 *
 * Shared between the DNS settings sheet (which only needs [id]/[labelRes]
 * to render a picker and persists the chosen [id]) and TunnelsViewModel
 * (which resolves a persisted id back to the actual spec string that
 * goes into VPNConfig.dotSpec).
 */
enum class DnsProvider(val id: String, val labelRes: Int, val spec: String) {
    OFF("off", R.string.dns_provider_off, ""),
    CLOUDFLARE("cloudflare", R.string.dns_provider_cloudflare, "1.1.1.1@cloudflare-dns.com"),
    YANDEX("yandex", R.string.dns_provider_yandex, "77.88.8.8@common.dot.dns.yandex.net"),
    GOOGLE("google", R.string.dns_provider_google, "8.8.8.8@dns.google"),
    QUAD9("quad9", R.string.dns_provider_quad9, "9.9.9.9@dns.quad9.net"),
    CUSTOM("custom", R.string.dns_provider_custom, ""); // spec comes from a separate user-entered field

    companion object {
        fun byId(id: String?): DnsProvider = entries.firstOrNull { it.id == id } ?: OFF

        /** Resolves a persisted provider id + custom-field text into the spec VPNConfig.dotSpec needs. Blank = DoT disabled. */
        fun resolveSpec(providerId: String?, customSpec: String?): String =
            when (val p = byId(providerId)) {
                CUSTOM -> customSpec?.trim().orEmpty()
                OFF -> ""
                else -> p.spec
            }
    }
}
