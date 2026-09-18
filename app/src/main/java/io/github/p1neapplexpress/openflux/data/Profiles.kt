package io.github.p1neapplexpress.openflux.data

enum class ProfileKind { LINK, CREDENTIALS }

/**
 * Static metadata for the four fixed profiles the redesigned UI exposes —
 * one per [TransportType]. Unlike the old UI, a profile is never renamed
 * or created/deleted by the user: it always exists, and is either
 * configured (has a link, or MAX credentials) or not.
 */
data class ProfileMeta(
    val transport: TransportType,
    val displayName: String,
    val initials: String,
    val kind: ProfileKind,
    /** Hostnames used to auto-detect this profile from a pasted link. Empty for CREDENTIALS profiles. */
    val domains: List<String> = emptyList(),
)

object Profiles {
    val ALL: List<ProfileMeta> = listOf(
        ProfileMeta(
            transport = TransportType.yandex,
            displayName = "Yandex Docs",
            initials = "Я",
            kind = ProfileKind.LINK,
            domains = listOf("docs.yandex.ru", "docs.yandex.net"),
        ),
        ProfileMeta(
            transport = TransportType.vyandex,
            displayName = "VOLGA",
            initials = "V",
            kind = ProfileKind.LINK,
            domains = listOf("disk.yandex.ru"),
        ),
        ProfileMeta(
            transport = TransportType.mailru,
            displayName = "Mail.ru",
            initials = "M",
            kind = ProfileKind.LINK,
            domains = listOf("mail.ru"),
        ),
        ProfileMeta(
            transport = TransportType.max,
            displayName = "MAX",
            initials = "X",
            kind = ProfileKind.CREDENTIALS,
        ),
    )

    fun of(transport: TransportType): ProfileMeta = ALL.first { it.transport == transport }

    /** Best-effort domain match, for the add-link sheet's auto-detect chip. */
    fun detectByHost(host: String): ProfileMeta? {
        val h = host.lowercase()
        return ALL.firstOrNull { meta -> meta.domains.any { h == it || h.endsWith(".$it") } }
    }
}
