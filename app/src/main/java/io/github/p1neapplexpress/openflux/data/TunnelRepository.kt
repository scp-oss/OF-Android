package io.github.p1neapplexpress.openflux.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.p1neapplexpress.openflux.util.Constants
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TunnelRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<Tunnel> {
        val raw = prefs.getString(Constants.PREF_TUNNELS_KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Tunnel>>(raw) }
            .getOrElse { emptyList() }
    }

    fun save(tunnels: List<Tunnel>) {
        prefs.edit {
            putString(Constants.PREF_TUNNELS_KEY, json.encodeToString(tunnels))
        }
    }

    /** ID последнего выбранного туннеля, или null если не выбран. */
    fun getSelectedId(): Long? {
        val v = prefs.getLong(Constants.PREF_SELECTED_TUNNEL_ID, -1L)
        return if (v == -1L) null else v
    }

    fun setSelectedId(id: Long) {
        prefs.edit { putLong(Constants.PREF_SELECTED_TUNNEL_ID, id) }
    }

    /** Возвращает выбранный туннель, или первый из списка, или null. */
    fun getSelected(): Tunnel? {
        val all = load()
        if (all.isEmpty()) return null
        val id = getSelectedId()
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    /**
     * One [Tunnel] per [TransportType] — the redesigned UI has exactly four
     * fixed profiles, never an arbitrary user-named list. Storage format is
     * unchanged (still a plain `List<Tunnel>` in the same pref key), so this
     * reads/writes the same data the old free-form UI did.
     */
    fun loadForType(type: TransportType): Tunnel? =
        load().firstOrNull { TransportType.from(it.transportType) == type }

    fun saveForType(tunnel: Tunnel) {
        val type = TransportType.from(tunnel.transportType)
        val current = load().toMutableList()
        val idx = current.indexOfFirst { TransportType.from(it.transportType) == type }
        if (idx >= 0) current[idx] = tunnel else current.add(tunnel)
        save(current)
    }
}
