package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONObject

object LocationFavoritesCodec {
    fun encode(locations: List<CzechLocation>): String {
        val array = JSONArray()
        locations.map(::normalizeLocationRegion).forEach { location ->
            array.put(
                JSONObject()
                    .put("name", location.name)
                    .put("region", location.region)
                    .put("latitude", location.latitude)
                    .put("longitude", location.longitude),
            )
        }
        return array.toString()
    }

    fun decode(json: String): List<CzechLocation> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val region = item.optString("region").trim()
                val latitude = item.optDouble("latitude", Double.NaN)
                val longitude = item.optDouble("longitude", Double.NaN)
                if (name.isBlank() || region.isBlank() || !latitude.isFinite() || !longitude.isFinite()) continue
                if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue
                add(normalizeLocationRegion(CzechLocation(name, region, latitude, longitude)))
            }
        }
    }
}
