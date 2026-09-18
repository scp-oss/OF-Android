package io.github.p1neapplexpress.openflux.util

import android.content.Context
import io.github.p1neapplexpress.openflux.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub's Releases API for a newer version than this build, so the
 * app can prompt in-app instead of relying on the user to remember to open
 * Obtainium — direct request. Every app open runs a fresh background check
 * (no cooldown window); the only thing that stops re-prompting is the user
 * having already dismissed that SAME version once, so it isn't nagging on
 * every single open of the app right up until they actually update — a
 * genuinely newer release past that point prompts again regardless.
 *
 * Never downloads or installs anything itself — this app is deliberately
 * sideloaded via Obtainium (see the release workflow's own comments), which
 * already owns that job; "Update" just opens the GitHub release page.
 */
object UpdateChecker {
    private const val GITHUB_REPO = "scp-oss/OF-Android"
    private const val KEY_DISMISSED_VERSION = "update_dismissed_version"

    @Serializable
    private data class LatestRelease(
        val tag_name: String? = null,
        val html_url: String? = null,
    )

    data class UpdateInfo(val version: String, val url: String)

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val latest = runCatching {
            val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.inputStream.bufferedReader().use { it.readText() }.let { text ->
                Json { ignoreUnknownKeys = true }.decodeFromString<LatestRelease>(text)
            }
        }.getOrNull() ?: return@withContext null

        val version = latest.tag_name?.removePrefix("v")?.takeIf { it.isNotBlank() } ?: return@withContext null
        val url = latest.html_url ?: return@withContext null
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return@withContext null

        val prefs = context.getSharedPreferences(Constants.PREF_HOME_UI, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DISMISSED_VERSION, null) == version) return@withContext null

        UpdateInfo(version, url)
    }

    /** Stops re-prompting for this exact version; a later, genuinely newer release still prompts. */
    fun dismiss(context: Context, version: String) {
        context.getSharedPreferences(Constants.PREF_HOME_UI, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_VERSION, version)
            .apply()
    }

    // Plain dotted-numeric compare — matches this project's own "keep
    // release tags a bare vX.Y.Z" convention (release.yml's own comment on
    // the workflow_dispatch tag_name input), so every real release parses
    // cleanly here. A non-numeric component (shouldn't happen given that
    // convention) is treated as 0 rather than throwing.
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, l.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }
}
