package com.example.proximityalert

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * FavouritesManager stores named target locations permanently.
 * Uses SharedPreferences — survives app restarts.
 */
object FavouritesManager {

    private const val PREFS_NAME = "proximity_favourites"
    private const val KEY_LIST   = "favourites_list"

    data class Favourite(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val savedAt: Long = System.currentTimeMillis()
    )

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Load all favourites ───────────────────────────────────
    fun getAll(context: Context): List<Favourite> {
        val json = getPrefs(context).getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Favourite(
                    id      = obj.getString("id"),
                    name    = obj.getString("name"),
                    lat     = obj.getDouble("lat"),
                    lon     = obj.getDouble("lon"),
                    savedAt = obj.getLong("savedAt")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Save a new favourite ──────────────────────────────────
    fun save(context: Context, name: String, lat: Double, lon: Double): Favourite {
        val existing = getAll(context).toMutableList()

        // Trim name, max 30 chars
        val safeName = name.trim().take(30).ifEmpty { "Location" }

        val favourite = Favourite(
            id      = System.currentTimeMillis().toString(),
            name    = safeName,
            lat     = lat,
            lon     = lon,
            savedAt = System.currentTimeMillis()
        )
        existing.add(0, favourite) // newest first

        // Limit to 20 favourites
        val trimmed = existing.take(20)
        persist(context, trimmed)
        return favourite
    }

    // ── Delete a favourite by id ──────────────────────────────
    fun delete(context: Context, id: String) {
        val updated = getAll(context).filter { it.id != id }
        persist(context, updated)
    }

    // ── Clear all ─────────────────────────────────────────────
    fun clearAll(context: Context) {
        getPrefs(context).edit().remove(KEY_LIST).apply()
    }

    // ── Persist to SharedPreferences ─────────────────────────
    private fun persist(context: Context, list: List<Favourite>) {
        val arr = JSONArray()
        list.forEach { fav ->
            val obj = JSONObject().apply {
                put("id",      fav.id)
                put("name",    fav.name)
                put("lat",     fav.lat)
                put("lon",     fav.lon)
                put("savedAt", fav.savedAt)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }
}