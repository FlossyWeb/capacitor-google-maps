package com.capacitorjs.plugins.googlemaps

import com.google.android.gms.maps.model.TileOverlay
import org.json.JSONObject

class CapacitorGoogleMapTileOverlay(fromJSONObject: JSONObject) {
    var tileUrl: String
    var opacity: Float = 1.0f
    var maxZoom: Int = 20 // Default max zoom
    var googleMapTileOverlay: TileOverlay? = null

    init {
        if (!fromJSONObject.has("tileUrl")) {
            throw InvalidArgumentsError("Tile overlay object is missing the required 'tileUrl' property")
        }
        tileUrl = fromJSONObject.optString("tileUrl")

        // Optional properties
        opacity = fromJSONObject.optDouble("opacity", 1.0).toFloat()
        maxZoom = fromJSONObject.optInt("maxZoom", 20)
    }
}
