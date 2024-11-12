package com.capacitorjs.plugins.googlemaps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.GroundOverlay
import com.google.android.gms.maps.model.GroundOverlayOptions
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Log
import java.net.URL
import java.net.HttpURLConnection
import java.io.IOException

class CapacitorGoogleMapGroundOverlay(fromJSONObject: JSONObject) {
    var bounds: LatLngBounds
    var opacity: Float = 1.0f
    var imageUrl: String? = null
    var groundOverlayOptions: GroundOverlayOptions? = null
    var googleMapGroundOverlay: GroundOverlay? = null

    init {
        if (!fromJSONObject.has("imageUrl")) {
            throw InvalidArgumentsError("Ground overlay object is missing the required 'imageUrl' property")
        }
        imageUrl = fromJSONObject.optString("imageUrl")
        Log.d("CapacitorGoogleMaps", "Loading image for ground overlay: $imageUrl")

        if (!fromJSONObject.has("bounds")) {
            throw InvalidArgumentsError("GroundOverlay object is missing the required 'bounds' property")
        }

        // Extract the opacity from the JSON object
        opacity = fromJSONObject.optDouble("opacity", 1.0).toFloat()
        Log.d("CapacitorGoogleMap", "Opacity value extracted: $opacity")

        val boundsObj = fromJSONObject.getJSONObject("bounds")
        val southwestObj = boundsObj.getJSONObject("southwest")
        val northeastObj = boundsObj.getJSONObject("northeast")

        val southWest = LatLng(southwestObj.getDouble("lat"), southwestObj.getDouble("lng"))
        val northEast = LatLng(northeastObj.getDouble("lat"), northeastObj.getDouble("lng"))
        bounds = LatLngBounds(southWest, northEast)

        // Start image loading in coroutine
        CoroutineScope(Dispatchers.Main).launch {
            val bitmapDescriptor = withContext(Dispatchers.IO) { loadImage(imageUrl) }  // Ensure image is loaded before creating overlay

            if (bitmapDescriptor != null) {
                // Ensure that the overlay creation happens **only after** the image is fully loaded
                createGroundOverlay(bitmapDescriptor)
            } else {
                Log.e("CapacitorGoogleMap", "Skipping overlay creation due to missing or failed image load.")
            }
        }
    }

    private fun createGroundOverlay(bitmapDescriptor: BitmapDescriptor) {
        Log.d("CapacitorGoogleMap", "Creating GroundOverlayOptions for bounds: $bounds and image: $bitmapDescriptor")

        // Create the ground overlay options
        groundOverlayOptions = GroundOverlayOptions()
            .image(bitmapDescriptor)
            .positionFromBounds(bounds)
            .transparency(1 - opacity)  // Convert opacity to transparency

        if (groundOverlayOptions == null) {
            Log.e("CapacitorGoogleMap", "GroundOverlayOptions is null after trying to create it.")
        } else {
            Log.d("CapacitorGoogleMap", "GroundOverlayOptions created successfully.")
        }
    }

    // Load the image from the URL asynchronously
    private suspend fun loadImage(imageUrl: String?): BitmapDescriptor? {
        return try {
            Log.d("ImageCache", "Loading image from URL: $imageUrl")
            val urlConnection = URL(imageUrl).openConnection() as HttpURLConnection
            urlConnection.connectTimeout = 5000
            urlConnection.readTimeout = 5000
            urlConnection.requestMethod = "GET"

            if (urlConnection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = urlConnection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                BitmapDescriptorFactory.fromBitmap(bitmap)
            } else {
                Log.e("ImageCache", "Failed to load image: Response code = ${urlConnection.responseCode}")
                null
            }
        } catch (e: IOException) {
            Log.e("ImageCache", "Error loading image: ${e.message}")
            null
        }
    }
}
