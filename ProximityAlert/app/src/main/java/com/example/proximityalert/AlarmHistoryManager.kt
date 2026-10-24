package com.example.proximityalert

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * AlarmHistoryManager stores a log of every alarm that fired.
 * Uses SharedPreferences — survives app restarts.
 */
object AlarmHistoryManager {

    private const val PREFS_NAME = "proximity_history"
    private const val KEY_LIST   = "alarm_history"
    private const val MAX_ENTRIES = 50

    data class AlarmEntry(
        val id: String,
        val locationName: String,   // place name or "lat, lon"
        val lat: Double,
        val lon: Double,
        val distanceMeters: Int,    // distance when alarm fired
        val radiusMeters: Int,      // what the radius was set to
        val triggeredAt: Long = System.currentTimeMillis()
    ) {
        // Human readable time
        fun formattedTime(): String {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            return sdf.format(Date(triggeredAt))
        }
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Load all history entries ──────────────────────────────
    fun getAll(context: Context): List<AlarmEntry> {
        val json = getPrefs(context).getString(KEY_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AlarmEntry(
                    id             = obj.getString("id"),
                    locationName   = obj.getString("locationName"),
                    lat            = obj.getDouble("lat"),
                    lon            = obj.getDouble("lon"),
                    distanceMeters = obj.getInt("distanceMeters"),
                    radiusMeters   = obj.getInt("radiusMeters"),
                    triggeredAt    = obj.getLong("triggeredAt")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Add a new alarm entry ─────────────────────────────────
    fun add(
        context: Context,
        locationName: String,
        lat: Double,
        lon: Double,
        distanceMeters: Int,
        radiusMeters: Int
    ) {
        val existing = getAll(context).toMutableList()
        val entry = AlarmEntry(
            id             = System.currentTimeMillis().toString(),
            locationName   = locationName.take(60),
            lat            = lat,
            lon            = lon,
            distanceMeters = distanceMeters,
            radiusMeters   = radiusMeters,
            triggeredAt    = System.currentTimeMillis()
        )
        existing.add(0, entry) // newest first
        val trimmed = existing.take(MAX_ENTRIES)
        persist(context, trimmed)
    }

    // ── Clear all history ─────────────────────────────────────
    fun clearAll(context: Context) {
        getPrefs(context).edit().remove(KEY_LIST).apply()
    }

    // ── Persist to SharedPreferences ─────────────────────────
    private fun persist(context: Context, list: List<AlarmEntry>) {
        val arr = JSONArray()
        list.forEach { entry ->
            val obj = JSONObject().apply {
                put("id",             entry.id)
                put("locationName",   entry.locationName)
                put("lat",            entry.lat)
                put("lon",            entry.lon)
                put("distanceMeters", entry.distanceMeters)
                put("radiusMeters",   entry.radiusMeters)
                put("triggeredAt",    entry.triggeredAt)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }
}