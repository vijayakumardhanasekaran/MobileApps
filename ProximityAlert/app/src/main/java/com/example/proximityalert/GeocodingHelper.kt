package com.example.proximityalert

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GeocodingHelper using Photon (photon.komoot.io)
 * - Free, no API key
 * - Better fuzzy matching than Nominatim
 * - India bounding box bias for accurate local results
 * - Returns up to 3 results for user to choose from
 */
object GeocodingHelper {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // India bounding box: west, south, east, north
    private const val INDIA_BBOX = "68.0,8.0,97.5,37.5"

    data class GeoResult(
        val lat: Double,
        val lon: Double,
        val displayName: String,
        val type: String = ""
    )

    /**
     * Search for a place — returns up to 3 results.
     * MUST be called from a background thread.
     */
    fun search(query: String): List<GeoResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.length > 200) return emptyList()

        val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")

        val url = "https://photon.komoot.io/api/" +
                "?q=$encoded" +
                "&limit=3" +
                "&lang=en" +
                "&bbox=$INDIA_BBOX"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ProximityAlertApp/1.0")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            parsePhotonResponse(body)
        } catch (e: IOException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePhotonResponse(body: String): List<GeoResult> {
        return try {
            val json     = JSONObject(body)
            val features = json.getJSONArray("features")
            val results  = mutableListOf<GeoResult>()
            for (i in 0 until features.length()) {
                val feature  = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val coords   = geometry.getJSONArray("coordinates")
                val props    = feature.getJSONObject("properties")
                results.add(GeoResult(
                    lat         = coords.getDouble(1),
                    lon         = coords.getDouble(0),
                    displayName = buildDisplayName(props),
                    type        = props.optString("type", "")
                ))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildDisplayName(props: JSONObject): String {
        val parts    = mutableListOf<String>()
        val name     = props.optString("name", "")
        val street   = props.optString("street", "")
        val city     = props.optString("city", "")
        val district = props.optString("district", "")
        val state    = props.optString("state", "")
        val country  = props.optString("country", "")
        if (name.isNotEmpty())     parts.add(name)
        if (street.isNotEmpty())   parts.add(street)
        if (city.isNotEmpty())     parts.add(city)
        else if (district.isNotEmpty()) parts.add(district)
        if (state.isNotEmpty())    parts.add(state)
        if (country.isNotEmpty())  parts.add(country)
        return if (parts.isEmpty()) "Unknown location" else parts.joinToString(", ")
    }
}