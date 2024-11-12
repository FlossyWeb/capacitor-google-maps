package com.capacitorjs.plugins.googlemaps

import android.graphics.BitmapFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import android.annotation.SuppressLint
import android.graphics.*
import android.location.Location
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.getcapacitor.Bridge
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.google.android.gms.maps.*
import com.google.android.gms.maps.GoogleMap.*
import com.google.android.gms.maps.model.*
import com.google.maps.android.clustering.Cluster
import com.google.maps.android.clustering.ClusterManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.net.URL
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import android.util.LruCache

import com.squareup.picasso.Picasso

class OverlayNotFoundError : GoogleMapsError("Overlay not found")

class CapacitorGoogleMap(
        val id: String,
        val config: GoogleMapConfig,
        val delegate: CapacitorGoogleMapsPlugin
) :
        OnCameraIdleListener,
        OnCameraMoveStartedListener,
        OnCameraMoveListener,
        OnMyLocationButtonClickListener,
        OnMyLocationClickListener,
        OnMapReadyCallback,
        OnMapClickListener,
        OnMarkerClickListener,
        OnMarkerDragListener,
        OnInfoWindowClickListener,
        OnCircleClickListener,
        OnPolylineClickListener,
        OnPolygonClickListener {
    private var mapView: MapView
    private var googleMap: GoogleMap? = null
    private val markers = HashMap<String, CapacitorGoogleMapMarker>()
    private val polygons = HashMap<String, CapacitorGoogleMapsPolygon>()
    private val circles = HashMap<String, CapacitorGoogleMapsCircle>()
    var currentOverlay: CapacitorGoogleMapGroundOverlay? = null

    // val overlays = HashMap<String, CapacitorGoogleMapGroundOverlay>()
    var overlays: MutableMap<String, CapacitorGoogleMapGroundOverlay> = mutableMapOf()
    // val imageCache = LruCache<String, Bitmap>(50)  // Cache for up to 50 images
    val imageCache = LruCache<String, BitmapDescriptor>(50)

    private val polylines = HashMap<String, CapacitorGoogleMapPolyline>()        
    private val markerIcons = HashMap<String, Bitmap>()
    private var clusterManager: ClusterManager<CapacitorGoogleMapMarker>? = null


    // Remove the overlays map for tile overlays. Only track the current tile overlay.
    private var currentTileOverlay: CapacitorGoogleMapTileOverlay? = null


    private val isReadyChannel = Channel<Boolean>()
    private var debounceJob: Job? = null

    init {
        val bridge = delegate.bridge

        mapView = MapView(bridge.context, config.googleMapOptions)
        initMap()
        setListeners()
    }

    private fun initMap() {
        runBlocking {
            val job =
                    CoroutineScope(Dispatchers.Main).launch {
                        mapView.onCreate(null)
                        mapView.onStart()
                        mapView.getMapAsync(this@CapacitorGoogleMap)
                        mapView.setWillNotDraw(false)
                        isReadyChannel.receive()

                        render()
                    }

            job.join()
        }
    }

    private fun render() {
        runBlocking {
            CoroutineScope(Dispatchers.Main).launch {
                val bridge = delegate.bridge
                val mapViewParent = FrameLayout(bridge.context)
                mapViewParent.minimumHeight = bridge.webView.height
                mapViewParent.minimumWidth = bridge.webView.width

                val layoutParams =
                        FrameLayout.LayoutParams(
                                getScaledPixels(bridge, config.width),
                                getScaledPixels(bridge, config.height),
                        )
                layoutParams.leftMargin = getScaledPixels(bridge, config.x)
                layoutParams.topMargin = getScaledPixels(bridge, config.y)

                mapViewParent.tag = id

                mapView.layoutParams = layoutParams
                mapViewParent.addView(mapView)

                ((bridge.webView.parent) as ViewGroup).addView(mapViewParent)

                bridge.webView.bringToFront()
                bridge.webView.setBackgroundColor(Color.TRANSPARENT)
                if (config.styles != null) {
                    googleMap?.setMapStyle(MapStyleOptions(config.styles!!))
                }
            }
        }
    }

    fun updateRender(updatedBounds: RectF) {
        this.config.x = updatedBounds.left.toInt()
        this.config.y = updatedBounds.top.toInt()
        this.config.width = updatedBounds.width().toInt()
        this.config.height = updatedBounds.height().toInt()

        runBlocking {
            CoroutineScope(Dispatchers.Main).launch {
                val bridge = delegate.bridge
                val mapRect = getScaledRect(bridge, updatedBounds)
                val mapView = this@CapacitorGoogleMap.mapView;
                mapView.x = mapRect.left
                mapView.y = mapRect.top
                if (mapView.layoutParams.width != config.width || mapView.layoutParams.height != config.height) {
                    mapView.layoutParams.width = getScaledPixels(bridge, config.width)
                    mapView.layoutParams.height = getScaledPixels(bridge, config.height)
                    mapView.requestLayout()
                }
            }
        }
    }

    fun dispatchTouchEvent(event: MotionEvent) {
        CoroutineScope(Dispatchers.Main).launch {
            val offsetViewBounds = getMapBounds()

            val relativeTop = offsetViewBounds.top
            val relativeLeft = offsetViewBounds.left

            event.setLocation(event.x - relativeLeft, event.y - relativeTop)
            mapView.dispatchTouchEvent(event)
        }
    }

    fun bringToFront() {
        CoroutineScope(Dispatchers.Main).launch {
            val mapViewParent =
                    ((delegate.bridge.webView.parent) as ViewGroup).findViewWithTag<ViewGroup>(
                            this@CapacitorGoogleMap.id
                    )
            mapViewParent.bringToFront()
        }
    }

    fun destroy() {
        runBlocking {
            val job =
                    CoroutineScope(Dispatchers.Main).launch {
                        val bridge = delegate.bridge

                        val viewToRemove: View? =
                                ((bridge.webView.parent) as ViewGroup).findViewWithTag(id)
                        if (null != viewToRemove) {
                            ((bridge.webView.parent) as ViewGroup).removeView(viewToRemove)
                        }
                        mapView.onDestroy()
                        googleMap = null
                        clusterManager = null
                    }

            job.join()
        }
    }


    private fun buildTileProvider(tileOverlay: CapacitorGoogleMapTileOverlay): TileProvider {
        // Create a TileProvider for the tile layer
        return object : UrlTileProvider(256, 256) {
            override fun getTileUrl(x: Int, y: Int, zoom: Int): URL? {
                return try {
                    // Interpolate the x, y, zoom into the tile URL
                    val urlString = tileOverlay.tileUrl.replace("{x}", x.toString())
                                                    .replace("{y}", y.toString())
                                                    .replace("{zoom}", zoom.toString())

                    // Return the URL for the tile
                    URL(urlString)
                } catch (e: Exception) {
                    Log.w("CapacitorGoogleMaps", "Invalid URL format: ${e.localizedMessage}")
                    null
                }
            }
        }
    }

    fun addTileLayer(tileOverlay: CapacitorGoogleMapTileOverlay, callback: (result: Result<String>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                // Create the tile provider using the provided URL
                val tileProvider = withContext(Dispatchers.IO) {
                    this@CapacitorGoogleMap.buildTileProvider(tileOverlay)
                }

                val tileOverlayOptions = TileOverlayOptions()
                    .tileProvider(tileProvider)
                    .transparency(1f - tileOverlay.opacity)
                    .visible(true)

                tileOverlay.maxZoom.let {
                    tileOverlayOptions.zIndex(it.toFloat())
                }

                // Add the new tile overlay
                val googleMapTileOverlay = googleMap?.addTileOverlay(tileOverlayOptions)
                tileOverlay.googleMapTileOverlay = googleMapTileOverlay

                // Ensure that googleMapTileOverlay is not null
                if (googleMapTileOverlay != null) {
                    // Track the current tile overlay
                    currentTileOverlay = tileOverlay

                    // Get the tile overlay ID from the newly added tile overlay
                    val tileOverlayId = googleMapTileOverlay.id

                    // Return the tile overlay ID in the callback
                    callback(Result.success(tileOverlayId))
                } else {
                    // If googleMapTileOverlay is null, return an error
                    callback(Result.failure(Throwable("Failed to add tile overlay")))
                }
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun setTileLayerOpacity(opacity: Float, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            // Check if there is a current tile overlay
            currentTileOverlay?.let { tileOverlay ->
                CoroutineScope(Dispatchers.Main).launch {
                    // Set the transparency based on the opacity (1 - opacity)
                    tileOverlay.googleMapTileOverlay?.transparency = 1 - opacity
                    Log.d("TileOverlay", "Updated opacity for tile overlay with id: ${tileOverlay.googleMapTileOverlay?.id}")

                    callback(null) // Call the callback with no errors
                }
            } ?: run {
                Log.e("TileOverlay", "No current tile overlay to update opacity.")
                callback(OverlayNotFoundError()) // If no tile overlay exists, return error
            }
        } catch (e: GoogleMapsError) {
            Log.e("TileOverlay", "Error setting tile layer opacity: ${e.message}")
            callback(e)
        }
    }


    fun removeTileLayer(callback: (result: Result<String>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                // Check if there's a current tile overlay
                if (currentTileOverlay?.googleMapTileOverlay != null) {
                    // Remove the current tile overlay
                    currentTileOverlay?.googleMapTileOverlay?.remove()

                    // Clear the current tile overlay reference
                    val tileOverlayId = currentTileOverlay?.googleMapTileOverlay?.id ?: "Unknown"
                    currentTileOverlay = null

                    // Return success with the ID of the removed tile
                    callback(Result.success(tileOverlayId))
                } else {
                    // No tile overlay found
                    callback(Result.success("No tile overlay found to remove"))
                    // callback(Result.failure(Throwable("No tile overlay found to remove")))
                }
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }


    fun updateMapOptions(zoom: Double?, center: JSObject?, styles: JSONArray?, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            val map = googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                zoom?.let {
                    if (it >= 0) {
                        map.moveCamera(CameraUpdateFactory.zoomTo(it.toFloat()))
                    }
                }

                center?.let {
                    val lat = it.getDouble("lat")
                    val lng = it.getDouble("lng")
                    map.moveCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lng)))
                }

                styles?.let {
                    val styleJson = it.toString()
                    val success = map.setMapStyle(MapStyleOptions(styleJson))
                    if (!success) {
                        throw GoogleMapsError("Failed to set map style")
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun generateOverlayHash(overlay: CapacitorGoogleMapGroundOverlay): String {
        val data = "${overlay.imageUrl}-${overlay.bounds.southwest.latitude},${overlay.bounds.southwest.longitude}-${overlay.bounds.northeast.latitude},${overlay.bounds.northeast.longitude}"
        return data.hashCode().toString()
    }

    fun addGroundOverlay(overlay: CapacitorGoogleMapGroundOverlay, index: String, callback: (result: Result<String>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                val existingOverlay = overlays[index]?.googleMapGroundOverlay

                if (existingOverlay != null) {
                    val bitmapDescriptor = withContext(Dispatchers.IO) {
                        try {
                            val urlConnection = URL(overlay.imageUrl).openConnection() as HttpURLConnection
                            urlConnection.connectTimeout = 5000
                            urlConnection.readTimeout = 5000
                            urlConnection.requestMethod = "GET"

                            if (urlConnection.responseCode == HttpURLConnection.HTTP_OK) {
                                val inputStream = urlConnection.inputStream
                                val bitmap = BitmapFactory.decodeStream(inputStream)
                                BitmapDescriptorFactory.fromBitmap(bitmap)
                            } else {
                                Log.e("CapacitorGoogleMaps", "Failed to load image from URL: ${overlay.imageUrl}, response code: ${urlConnection.responseCode}")
                                null
                            }
                        } catch (e: IOException) {
                            Log.e("CapacitorGoogleMaps", "Error loading image: ${e.message}")
                            null  // Return null to signal image loading failure
                        }
                    }

                    if (bitmapDescriptor != null) {
                        existingOverlay.setImage(bitmapDescriptor)
                        existingOverlay.transparency = 1 - overlay.opacity
                        callback(Result.success(index))
                    } else {
                        Log.w("CapacitorGoogleMaps", "Image could not be loaded for overlay with index $index.")
                        callback(Result.failure(InvalidArgumentsError("Image could not be loaded for overlay")))
                    }
                } else {
                    // Only proceed if overlayOptions is valid (image successfully loaded)
                    val overlayOptions = overlay.groundOverlayOptions
                    if (overlayOptions == null) {
                        Log.e("CapacitorGoogleMaps", "Skipping adding overlay due to missing image.")
                        callback(Result.failure(InvalidArgumentsError("Overlay creation skipped due to missing image.")))
                        return@launch  // Stop further execution if the image is missing
                    }

                    val googleMapGroundOverlay = googleMap?.addGroundOverlay(overlayOptions)

                    if (googleMapGroundOverlay == null) {
                        Log.e("CapacitorGoogleMaps", "Failed to add ground overlay.")
                        callback(Result.failure(InvalidArgumentsError("Failed to add ground overlay")))
                    } else {
                        overlay.googleMapGroundOverlay = googleMapGroundOverlay
                        overlays[index] = overlay
                        callback(Result.success(index))
                    }
                }
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }


    fun addOrUpdateGroundOverlay(overlay: CapacitorGoogleMapGroundOverlay, callback: (result: Result<String>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            var overlayId: String

            CoroutineScope(Dispatchers.Main).launch {
                // Build the overlay options using the provided parameters
                val overlayOptions = withContext(Dispatchers.IO) {
                    this@CapacitorGoogleMap.buildGroundOverlay(overlay)
                }

                // Check if there is an existing overlay
                val existingOverlay = currentOverlay

                if (existingOverlay != null) {
                    // Update the existing overlay
                    overlayOptions.bounds?.let { bounds ->
                        existingOverlay.googleMapGroundOverlay?.setPositionFromBounds(bounds)
                    }

                    if (overlayOptions.image != null) {
                        existingOverlay.googleMapGroundOverlay?.setImage(overlayOptions.image)
                    }
                    existingOverlay.googleMapGroundOverlay?.setTransparency(1 - overlay.opacity) // Convert opacity to transparency
                } else {
                    // Add a new overlay
                    if (overlayOptions.image != null) {
                        val googleMapGroundOverlay = googleMap?.addGroundOverlay(overlayOptions)

                        overlay.googleMapGroundOverlay = googleMapGroundOverlay
                        currentOverlay = overlay

                        overlays[googleMapGroundOverlay!!.id] = overlay
                    }
                }

                overlayId = currentOverlay?.googleMapGroundOverlay?.id ?: ""
                callback(Result.success(overlayId)) // Always succeed, even if the image wasn't set
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e)) // Handle other errors as usual
        }
    }



    fun addMultipleGroundOverlays(overlaysData: JSObject, callback: (result: Result<String>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            val imageUrls = overlaysData.getJSONArray("imageUrl")
            val boundsArray = overlaysData.getJSONArray("bounds")
            val opacity = overlaysData.optDouble("opacity", 1.0).toFloat()

            if (imageUrls.length() != boundsArray.length()) {
                callback(Result.failure(InvalidArgumentsError("Mismatched imageUrl and bounds count")))
                return
            }

            // Track used overlays to manage recycling
            val usedOverlayIds = mutableSetOf<String>()

            // Preload all images in parallel
            CoroutineScope(Dispatchers.Main).launch {
                val imageDescriptors = mutableListOf<BitmapDescriptor?>()

                withContext(Dispatchers.IO) {
                    val deferredBitmaps = (0 until imageUrls.length()).map { i ->
                        async {
                            val imageUrl = imageUrls.getString(i)
                            preloadImage(imageUrl)  // Preload BitmapDescriptor, not Bitmap
                        }
                    }

                    deferredBitmaps.forEachIndexed { index, deferred ->
                        imageDescriptors.add(deferred.await())
                    }
                }

                // Once all images are preloaded, proceed to add or update overlays
                var firstOverlayId: String? = null

                for (i in 0 until imageUrls.length()) {
                    val index = i.toString()

                    val overlayObj = JSObject().apply {
                        put("imageUrl", imageUrls.getString(i))
                        put("bounds", boundsArray.getJSONObject(i))
                        put("opacity", opacity)
                    }

                    val overlay = CapacitorGoogleMapGroundOverlay(overlayObj)
                    val imageUrl = imageUrls.getString(i)
                    val bitmapDescriptor = imageDescriptors[i]

                    if (bitmapDescriptor != null) {
                        // Check if the overlay already exists AND if the image URL matches
                        if (overlays.containsKey(index) && overlays[index]?.imageUrl == imageUrl) {
                            val existingOverlay = overlays[index]?.googleMapGroundOverlay
                            existingOverlay?.setPositionFromBounds(overlay.bounds)
                            existingOverlay?.transparency = 1 - overlay.opacity  // Update opacity if needed
                            Log.d("CapacitorGoogleMaps", "Reused existing overlay with ID: $index and URL: $imageUrl")
                        } else {
                            // Create or update the overlay if the URL doesn't match
                            if (overlays.containsKey(index)) {
                                // If the overlay exists but the URL doesn't match, update the image
                                val existingOverlay = overlays[index]?.googleMapGroundOverlay
                                existingOverlay?.setImage(bitmapDescriptor)
                                existingOverlay?.setPositionFromBounds(overlay.bounds)
                                existingOverlay?.transparency = 1 - overlay.opacity  // Update opacity
                                Log.d("CapacitorGoogleMaps", "Updated existing overlay with new image for ID: $index")
                            } else {
                                // Create new overlay if it doesn't exist
                                overlay.groundOverlayOptions = GroundOverlayOptions()
                                    .image(bitmapDescriptor)
                                    .positionFromBounds(overlay.bounds)
                                    .transparency(1 - overlay.opacity)

                                val overlayOptions = overlay.groundOverlayOptions
                                if (overlayOptions != null) {
                                    val newOverlay = googleMap?.addGroundOverlay(overlayOptions)
                                    newOverlay?.let {
                                        overlays[index] = overlay.apply {
                                            googleMapGroundOverlay = newOverlay  // Store the newly created overlay
                                            this.imageUrl = imageUrl  // Store the image URL to reuse later
                                        }
                                        Log.d("CapacitorGoogleMaps", "Added new overlay with ID: $index and URL: $imageUrl")
                                        if (firstOverlayId == null) {
                                            firstOverlayId = index
                                        }
                                    }
                                }
                            }
                        }
                    }

                    usedOverlayIds.add(index)  // Mark the overlay as used
                }

                // Hide or recycle any unused overlays
                overlays.keys.forEach { key ->
                    if (!usedOverlayIds.contains(key)) {
                        overlays[key]?.googleMapGroundOverlay?.isVisible = false  // Hide the unused overlay
                        Log.d("CapacitorGoogleMaps", "Hiding unused overlay with ID: $key")
                    }
                }

                firstOverlayId?.let {
                    callback(Result.success(it))  // Return the first overlay's ID
                } ?: run {
                    val defaultId = "0"  // Fallback ID
                    callback(Result.success(defaultId))
                }
            }

        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }




    suspend fun preloadImage(imageUrl: String): BitmapDescriptor? {
        return try {
            Log.d("ImageCache", "Loading image from URL using Picasso: $imageUrl")
            val cachedDescriptor = imageCache.get(imageUrl)
            if (cachedDescriptor != null) {
                Log.d("ImageCache", "ImageDescriptor loaded from cache for URL: $imageUrl")
                return cachedDescriptor
            }

            // Load the image using Picasso and convert it to BitmapDescriptor
            val bitmap = Picasso.get()
                .load(imageUrl)
                .get()  // Blocking call to load the image

            val bitmapDescriptor = BitmapDescriptorFactory.fromBitmap(bitmap)

            // Cache the BitmapDescriptor for future use
            imageCache.put(imageUrl, bitmapDescriptor)
            Log.d("ImageCache", "ImageDescriptor loaded and cached from URL: $imageUrl")
            return bitmapDescriptor
        } catch (e: Exception) {
            Log.e("ImageCache", "Failed to load image with Picasso: $e")
            null
        }
    }



    fun addOverlayToMap(overlay: CapacitorGoogleMapGroundOverlay, index: String, bitmapDescriptor: BitmapDescriptor?): String? {
        try {
            if (bitmapDescriptor != null) {
                Log.d("CapacitorGoogleMaps", "Adding overlay with BitmapDescriptor")

                val existingOverlay = overlays[index]?.googleMapGroundOverlay

                if (existingOverlay != null) {
                    existingOverlay.setImage(bitmapDescriptor)
                    existingOverlay.transparency = 1 - overlay.opacity  // Ensure opacity is updated if changed
                    Log.d("CapacitorGoogleMaps", "Updated existing overlay with ID: $index and opacity: ${overlay.opacity}")
                    return index
                } else {
                    // Ensure that the GroundOverlayOptions is created after the bitmap is ready
                    overlay.groundOverlayOptions = GroundOverlayOptions()
                        .image(bitmapDescriptor)
                        .positionFromBounds(overlay.bounds)
                        .transparency(1 - overlay.opacity)  // Convert opacity to transparency

                    val overlayOptions = overlay.groundOverlayOptions
                    if (overlayOptions != null) {
                        val newOverlay = googleMap?.addGroundOverlay(overlayOptions)
                        newOverlay?.let {
                            overlays[index] = overlay.apply {
                                googleMapGroundOverlay = newOverlay  // Store the newly created overlay
                            }
                            Log.d("CapacitorGoogleMaps", "Added new overlay with ID: $index and opacity: ${overlay.opacity}")
                            return index
                        }
                    } else {
                        Log.e("OverlayError", "GroundOverlayOptions is null, cannot add overlay at index $index")
                    }
                }
            } else {
                Log.e("OverlayError", "BitmapDescriptor is null for overlay at index $index")
            }
        } catch (e: Exception) {
            Log.e("OverlayError", "Error updating overlay: $e")
        }
        return null
    }



    fun setOverlayOpacity(opacity: Float, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            if (overlays.isEmpty()) {
                throw OverlayNotFoundError()
            }

            CoroutineScope(Dispatchers.Main).launch {
                // Loop through all overlays and update their opacity
                overlays.values.forEach { overlay ->
                    overlay.googleMapGroundOverlay?.setTransparency(1 - opacity)
                    Log.d("GroundOverlay", "Updated opacity for overlay with id: ${overlay.googleMapGroundOverlay?.id}")
                }

                callback(null)  // No errors, so call the callback with null error
            }
        } catch (e: GoogleMapsError) {
            Log.e("GroundOverlay", "Error setting overlay opacity: ${e.message}")
            callback(e)
        }
    }





    fun removeGroundOverlay(id: String, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            val overlay = overlays[id]
            overlay ?: throw OverlayNotFoundError()

            CoroutineScope(Dispatchers.Main).launch {
                overlay.googleMapGroundOverlay?.remove()
                overlays.remove(id)

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removeAllGroundOverlays(callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                overlays.values.forEach { overlay ->
                    if (overlay.googleMapGroundOverlay != null) {
                        // Remove the ground overlay from the map
                        overlay.googleMapGroundOverlay?.remove()
                    } else {
                        Log.w("CapacitorGoogleMaps", "Overlay is missing or was not created properly, skipping removal.")
                    }
                }
                overlays.clear()

                // Reset the currentOverlay
                currentOverlay = null

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        } catch (e: Exception) {
            Log.e("CapacitorGoogleMaps", "Unexpected error while removing overlays: ${e.message}")
            callback(InvalidArgumentsError("Unexpected error occurred: ${e.message}"))
        }
    }


    fun setCurrentOverlayImage(imageUrl: String, opacity: Float, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            val overlay = currentOverlay
            overlay ?: throw OverlayNotFoundError()

            CoroutineScope(Dispatchers.Main).launch {
                val bitmapDescriptor = withContext(Dispatchers.IO) {
                    try {
                        val bitmap = BitmapFactory.decodeStream(URL(imageUrl).openStream())
                        BitmapDescriptorFactory.fromBitmap(bitmap)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (bitmapDescriptor != null) {
                    overlay.googleMapGroundOverlay?.setImage(bitmapDescriptor)
                    overlay.googleMapGroundOverlay?.setTransparency(1 - opacity) // Convert opacity to transparency
                    callback(null)
                } else {
                    callback(null)
                    // throw InvalidArgumentsError("AA Could not load image for ground overlay")
                }
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun addMarkers(
            newMarkers: List<CapacitorGoogleMapMarker>,
            callback: (ids: Result<List<String>>) -> Unit
    ) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val markerIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                newMarkers.forEach {
                    val markerOptions: Deferred<MarkerOptions> =
                            CoroutineScope(Dispatchers.IO).async {
                                this@CapacitorGoogleMap.buildMarker(it)
                            }
                    val googleMapMarker = googleMap?.addMarker(markerOptions.await())
                    it.googleMapMarker = googleMapMarker

                    if (googleMapMarker != null) {
                        if (clusterManager != null) {
                            googleMapMarker.remove()
                        }

                        markers[googleMapMarker.id] = it
                        markerIds.add(googleMapMarker.id)
                    }
                }

                if (clusterManager != null) {
                    clusterManager?.addItems(newMarkers)
                    clusterManager?.cluster()
                }

                callback(Result.success(markerIds))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addMarker(marker: CapacitorGoogleMapMarker, callback: (result: Result<String>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            var markerId: String

            CoroutineScope(Dispatchers.Main).launch {
                val markerOptions: Deferred<MarkerOptions> =
                        CoroutineScope(Dispatchers.IO).async {
                            this@CapacitorGoogleMap.buildMarker(marker)
                        }
                val googleMapMarker = googleMap?.addMarker(markerOptions.await())

                marker.googleMapMarker = googleMapMarker

                if (clusterManager != null) {
                    googleMapMarker?.remove()
                    clusterManager?.addItem(marker)
                    clusterManager?.cluster()
                }

                markers[googleMapMarker!!.id] = marker

                markerId = googleMapMarker.id

                callback(Result.success(markerId))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addPolygons(newPolygons: List<CapacitorGoogleMapsPolygon>, callback: (ids: Result<List<String>>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val shapeIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                newPolygons.forEach {
                    val polygonOptions: Deferred<PolygonOptions> = CoroutineScope(Dispatchers.IO).async {
                        this@CapacitorGoogleMap.buildPolygon(it)
                    }

                    val googleMapsPolygon = googleMap?.addPolygon(polygonOptions.await())
                    googleMapsPolygon?.tag = it.tag

                    it.googleMapsPolygon = googleMapsPolygon

                    polygons[googleMapsPolygon!!.id] = it
                    shapeIds.add(googleMapsPolygon.id)
                }

                callback(Result.success(shapeIds))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addCircles(newCircles: List<CapacitorGoogleMapsCircle>,callback: (ids: Result<List<String>>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val circleIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                newCircles.forEach {
                    var circleOptions: Deferred<CircleOptions> = CoroutineScope(Dispatchers.IO).async {
                        this@CapacitorGoogleMap.buildCircle(it)
                    }

                    val googleMapsCircle = googleMap?.addCircle(circleOptions.await())
                    googleMapsCircle?.tag = it.tag

                    it.googleMapsCircle = googleMapsCircle

                    circles[googleMapsCircle!!.id] = it
                    circleIds.add(googleMapsCircle.id)
                }

                callback(Result.success(circleIds))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    fun addPolylines(newLines: List<CapacitorGoogleMapPolyline>, callback: (ids: Result<List<String>>) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            val lineIds: MutableList<String> = mutableListOf()

            CoroutineScope(Dispatchers.Main).launch {
                newLines.forEach {
                    val polylineOptions: Deferred<PolylineOptions> = CoroutineScope(Dispatchers.IO).async {
                        this@CapacitorGoogleMap.buildPolyline(it)
                    }
                    val googleMapPolyline = googleMap?.addPolyline(polylineOptions.await())
                    googleMapPolyline?.tag = it.tag

                    it.googleMapsPolyline = googleMapPolyline

                    polylines[googleMapPolyline!!.id] = it
                    lineIds.add(googleMapPolyline.id)
                }

                callback(Result.success(lineIds))
            }
        } catch (e: GoogleMapsError) {
            callback(Result.failure(e))
        }
    }

    private fun setClusterManagerRenderer(minClusterSize: Int?) {
        clusterManager?.renderer = CapacitorClusterManagerRenderer(
            delegate.bridge.context,
            googleMap,
            clusterManager,
            minClusterSize
        )
    }

    @SuppressLint("PotentialBehaviorOverride")
    fun enableClustering(minClusterSize: Int?, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                if (clusterManager != null) {
                    setClusterManagerRenderer(minClusterSize)
                    callback(null)
                    return@launch
                }

                val bridge = delegate.bridge
                clusterManager = ClusterManager(bridge.context, googleMap)

                setClusterManagerRenderer(minClusterSize)
                setClusterListeners()

                // add existing markers to the cluster
                if (markers.isNotEmpty()) {
                    for ((_, marker) in markers) {
                        marker.googleMapMarker?.remove()
                        // marker.googleMapMarker = null
                    }
                    clusterManager?.addItems(markers.values)
                    clusterManager?.cluster()
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    @SuppressLint("PotentialBehaviorOverride")
    fun disableClustering(callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                clusterManager?.clearItems()
                clusterManager?.cluster()
                clusterManager = null

                googleMap?.setOnMarkerClickListener(this@CapacitorGoogleMap)

                // add existing markers back to the map
                if (markers.isNotEmpty()) {
                    for ((_, marker) in markers) {
                        val markerOptions: Deferred<MarkerOptions> =
                                CoroutineScope(Dispatchers.IO).async {
                                    this@CapacitorGoogleMap.buildMarker(marker)
                                }
                        val googleMapMarker = googleMap?.addMarker(markerOptions.await())
                        marker.googleMapMarker = googleMapMarker
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removePolygons(ids: List<String>, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                ids.forEach {
                    val polygon = polygons[it]
                    if (polygon != null) {
                        polygon.googleMapsPolygon?.remove()
                        polygons.remove(it)
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removeMarker(id: String, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            val marker = markers[id]
            marker ?: throw MarkerNotFoundError()

            CoroutineScope(Dispatchers.Main).launch {
                if (clusterManager != null) {
                    clusterManager?.removeItem(marker)
                    clusterManager?.cluster()
                }

                marker.googleMapMarker?.remove()
                markers.remove(id)

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removeMarkers(ids: List<String>, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                val deletedMarkers: MutableList<CapacitorGoogleMapMarker> = mutableListOf()

                ids.forEach {
                    val marker = markers[it]
                    if (marker != null) {
                        marker.googleMapMarker?.remove()
                        markers.remove(it)

                        deletedMarkers.add(marker)
                    }
                }

                if (clusterManager != null) {
                    clusterManager?.removeItems(deletedMarkers)
                    clusterManager?.cluster()
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removeCircles(ids: List<String>, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                ids.forEach {
                    val circle = circles[it]
                    if (circle != null) {
                        circle.googleMapsCircle?.remove()
                        markers.remove(it)
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun removePolylines(ids: List<String>, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()

            CoroutineScope(Dispatchers.Main).launch {
                ids.forEach {
                    val polyline = polylines[it]
                    if (polyline != null) {
                        polyline.googleMapsPolyline?.remove()
                        polylines.remove(it)
                    }
                }

                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun setCamera(config: GoogleMapCameraConfig, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            CoroutineScope(Dispatchers.Main).launch {
                val currentPosition = googleMap!!.cameraPosition

                var updatedTarget = config.coordinate
                if (updatedTarget == null) {
                    updatedTarget = currentPosition.target
                }

                var zoom = config.zoom
                if (zoom == null) {
                    zoom = currentPosition.zoom.toDouble()
                }

                var bearing = config.bearing
                if (bearing == null) {
                    bearing = currentPosition.bearing.toDouble()
                }

                var angle = config.angle
                if (angle == null) {
                    angle = currentPosition.tilt.toDouble()
                }

                var animate = config.animate
                if (animate == null) {
                    animate = false
                }

                val updatedPosition =
                        CameraPosition.Builder()
                                .target(updatedTarget)
                                .zoom(zoom.toFloat())
                                .bearing(bearing.toFloat())
                                .tilt(angle.toFloat())
                                .build()

                if (animate) {
                    googleMap?.animateCamera(CameraUpdateFactory.newCameraPosition(updatedPosition))
                } else {
                    googleMap?.moveCamera(CameraUpdateFactory.newCameraPosition(updatedPosition))
                }
                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun getMapType(callback: (type: String, error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            CoroutineScope(Dispatchers.Main).launch {
                val mapType: String = when (googleMap?.mapType) {
                    MAP_TYPE_NORMAL -> "Normal"
                    MAP_TYPE_HYBRID -> "Hybrid"
                    MAP_TYPE_SATELLITE -> "Satellite"
                    MAP_TYPE_TERRAIN -> "Terrain"
                    MAP_TYPE_NONE -> "None"
                    else -> {
                        "Normal"
                    }
                }
                callback(mapType, null);
            }
        }  catch (e: GoogleMapsError) {
            callback("", e)
        }
    }

    fun setMapType(mapType: String, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            CoroutineScope(Dispatchers.Main).launch {
                val mapTypeInt: Int =
                        when (mapType) {
                            "Normal" -> MAP_TYPE_NORMAL
                            "Hybrid" -> MAP_TYPE_HYBRID
                            "Satellite" -> MAP_TYPE_SATELLITE
                            "Terrain" -> MAP_TYPE_TERRAIN
                            "None" -> MAP_TYPE_NONE
                            else -> {
                                Log.w(
                                        "CapacitorGoogleMaps",
                                        "unknown mapView type '$mapType'  Defaulting to normal."
                                )
                                MAP_TYPE_NORMAL
                            }
                        }

                googleMap?.mapType = mapTypeInt
                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun enableIndoorMaps(enabled: Boolean, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            CoroutineScope(Dispatchers.Main).launch {
                googleMap?.isIndoorEnabled = enabled
                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun enableTrafficLayer(enabled: Boolean, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            CoroutineScope(Dispatchers.Main).launch {
                googleMap?.isTrafficEnabled = enabled
                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    @SuppressLint("MissingPermission")
    fun enableCurrentLocation(enabled: Boolean, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            CoroutineScope(Dispatchers.Main).launch {
                googleMap?.isMyLocationEnabled = enabled
                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun setPadding(padding: GoogleMapPadding, callback: (error: GoogleMapsError?) -> Unit) {
        try {
            googleMap ?: throw GoogleMapNotAvailable()
            CoroutineScope(Dispatchers.Main).launch {
                googleMap?.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                callback(null)
            }
        } catch (e: GoogleMapsError) {
            callback(e)
        }
    }

    fun getMapBounds(): Rect {
        return Rect(
                getScaledPixels(delegate.bridge, config.x),
                getScaledPixels(delegate.bridge, config.y),
                getScaledPixels(delegate.bridge, config.x + config.width),
                getScaledPixels(delegate.bridge, config.y + config.height)
        )
    }

    fun getLatLngBounds(): LatLngBounds {
        return googleMap?.projection?.visibleRegion?.latLngBounds ?: throw BoundsNotFoundError()
    }

    fun fitBounds(bounds: LatLngBounds, padding: Int) {
        val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, padding)
        googleMap?.animateCamera(cameraUpdate)
    }

    private fun getScaledPixels(bridge: Bridge, pixels: Int): Int {
        // Get the screen's density scale
        val scale = bridge.activity.resources.displayMetrics.density
        // Convert the dps to pixels, based on density scale
        return (pixels * scale + 0.5f).toInt()
    }

    private fun getScaledPixelsF(bridge: Bridge, pixels: Float): Float {
        // Get the screen's density scale
        val scale = bridge.activity.resources.displayMetrics.density
        // Convert the dps to pixels, based on density scale
        return (pixels * scale + 0.5f)
    }

    private fun getScaledRect(bridge: Bridge, rectF: RectF): RectF {
        return RectF(
                getScaledPixelsF(bridge, rectF.left),
                getScaledPixelsF(bridge, rectF.top),
                getScaledPixelsF(bridge, rectF.right),
                getScaledPixelsF(bridge, rectF.bottom)
        )
    }

    private fun buildCircle(circle: CapacitorGoogleMapsCircle): CircleOptions {
        val circleOptions = CircleOptions()
        circleOptions.fillColor(circle.fillColor)
        circleOptions.strokeColor(circle.strokeColor)
        circleOptions.strokeWidth(circle.strokeWidth)
        circleOptions.zIndex(circle.zIndex)
        circleOptions.clickable(circle.clickable)
        circleOptions.radius(circle.radius.toDouble())
        circleOptions.center(circle.center)

        return circleOptions
    }

    private fun buildPolygon(polygon: CapacitorGoogleMapsPolygon): PolygonOptions {
        val polygonOptions = PolygonOptions()
        polygonOptions.fillColor(polygon.fillColor)
        polygonOptions.strokeColor(polygon.strokeColor)
        polygonOptions.strokeWidth(polygon.strokeWidth)
        polygonOptions.zIndex(polygon.zIndex)
        polygonOptions.geodesic(polygon.geodesic)
        polygonOptions.clickable(polygon.clickable)

        var shapeCounter = 0
        polygon.shapes.forEach {
            if (shapeCounter == 0) {
                // outer shape
                it.forEach {
                    polygonOptions.add(it)
                }
            } else {
                polygonOptions.addHole(it)
            }

            shapeCounter += 1
        }

        return polygonOptions
    }
    
    private fun buildPolyline(line: CapacitorGoogleMapPolyline): PolylineOptions {
        val polylineOptions = PolylineOptions()
        polylineOptions.width(line.strokeWidth * this.config.devicePixelRatio)
        polylineOptions.color(line.strokeColor)
        polylineOptions.clickable(line.clickable)
        polylineOptions.zIndex(line.zIndex)
        polylineOptions.geodesic(line.geodesic)

        line.path.forEach {
            polylineOptions.add(it)
        }

        line.styleSpans.forEach {
            if (it.segments != null) {
                polylineOptions.addSpan(StyleSpan(it.color, it.segments))
            } else {
                polylineOptions.addSpan(StyleSpan(it.color))
            }
        }

        return polylineOptions
    }

    private fun buildMarker(marker: CapacitorGoogleMapMarker): MarkerOptions {
        val markerOptions = MarkerOptions()
        markerOptions.position(marker.coordinate)
        markerOptions.title(marker.title)
        markerOptions.snippet(marker.snippet)
        markerOptions.alpha(marker.opacity)
        markerOptions.flat(marker.isFlat)
        markerOptions.draggable(marker.draggable)
        markerOptions.zIndex(marker.zIndex)
        if (marker.iconAnchor != null) {
            markerOptions.anchor(marker.iconAnchor!!.x, marker.iconAnchor!!.y)
        }


        if (!marker.iconUrl.isNullOrEmpty()) {
            if (this.markerIcons.contains(marker.iconUrl)) {
                val cachedBitmap = this.markerIcons[marker.iconUrl]
                markerOptions.icon(getResizedIcon(cachedBitmap!!, marker))
            } else {
                try {
                    var stream: InputStream? = null
                    if (marker.iconUrl!!.startsWith("https:")) {
                        stream = URL(marker.iconUrl).openConnection().getInputStream()
                    } else {
                        stream = this.delegate.context.assets.open("public/${marker.iconUrl}")
                    }
                    var bitmap = BitmapFactory.decodeStream(stream)
                    this.markerIcons[marker.iconUrl!!] = bitmap
                    markerOptions.icon(getResizedIcon(bitmap, marker))
                } catch (e: Exception) {
                    var detailedMessage = "${e.javaClass} - ${e.localizedMessage}"
                    if (marker.iconUrl!!.endsWith(".svg")) {
                        detailedMessage = "SVG not supported"
                    }

                    Log.w(
                            "CapacitorGoogleMaps",
                            "Could not load image '${marker.iconUrl}': ${detailedMessage}. Using default marker icon."
                    )
                }
            }
        } else {
            if (marker.colorHue != null) {
                markerOptions.icon(BitmapDescriptorFactory.defaultMarker(marker.colorHue!!))
            }
        }

        marker.markerOptions = markerOptions

        return markerOptions
    }

    private fun buildGroundOverlay(overlay: CapacitorGoogleMapGroundOverlay): GroundOverlayOptions {
        val overlayOptions = GroundOverlayOptions()
        val bounds = LatLngBounds(
            LatLng(overlay.bounds.southwest.latitude, overlay.bounds.southwest.longitude),
            LatLng(overlay.bounds.northeast.latitude, overlay.bounds.northeast.longitude)
        )
        overlayOptions.positionFromBounds(bounds)
        overlayOptions.transparency(1 - overlay.opacity) // Convert opacity to transparency

        if (!overlay.imageUrl.isNullOrEmpty()) {
            try {
                var bitmap: Bitmap? = null
                if (overlay.imageUrl!!.startsWith("https:")) {
                    val stream = URL(overlay.imageUrl).openConnection().getInputStream()
                    bitmap = BitmapFactory.decodeStream(stream)
                } else {
                    val stream = this.delegate.context.assets.open("public/${overlay.imageUrl}")
                    bitmap = BitmapFactory.decodeStream(stream)
                }
                overlayOptions.image(BitmapDescriptorFactory.fromBitmap(bitmap))
            } catch (e: Exception) {
                Log.w(
                    "CapacitorGoogleMaps",
                    "Could not load image '${overlay.imageUrl}': ${e.localizedMessage}. Using default ground overlay."
                )
            }
        }

        overlay.groundOverlayOptions = overlayOptions

        return overlayOptions
    }


    private fun getResizedIcon(
            _bitmap: Bitmap,
            marker: CapacitorGoogleMapMarker
    ): BitmapDescriptor {
        var bitmap = _bitmap
        if (marker.iconSize != null) {
            bitmap =
                    Bitmap.createScaledBitmap(
                            bitmap,
                            (marker.iconSize!!.width * this.config.devicePixelRatio).toInt(),
                            (marker.iconSize!!.height * this.config.devicePixelRatio).toInt(),
                            false
                    )
        }
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    fun onStart() {
        mapView.onStart()
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onStop() {
        mapView.onStop()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onDestroy() {
        mapView.onDestroy()
    }

    override fun onMapReady(map: GoogleMap) {
        runBlocking {
            googleMap = map

            val data = JSObject()
            data.put("mapId", this@CapacitorGoogleMap.id)
            delegate.notify("onMapReady", data)

            isReadyChannel.send(true)
            isReadyChannel.close()
        }
    }

    @SuppressLint("PotentialBehaviorOverride")
    fun setListeners() {
        CoroutineScope(Dispatchers.Main).launch {
            this@CapacitorGoogleMap.googleMap?.setOnCameraIdleListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnCameraMoveStartedListener(
                    this@CapacitorGoogleMap
            )
            this@CapacitorGoogleMap.googleMap?.setOnCameraMoveListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnMarkerClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnPolygonClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnCircleClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnMarkerDragListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnMapClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnMyLocationButtonClickListener(
                    this@CapacitorGoogleMap
            )
            this@CapacitorGoogleMap.googleMap?.setOnMyLocationClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnInfoWindowClickListener(this@CapacitorGoogleMap)
            this@CapacitorGoogleMap.googleMap?.setOnPolylineClickListener(this@CapacitorGoogleMap)
        }
    }

    fun setClusterListeners() {
        CoroutineScope(Dispatchers.Main).launch {
            clusterManager?.setOnClusterItemClickListener {
                if (null == it.googleMapMarker) false
                else this@CapacitorGoogleMap.onMarkerClick(it.googleMapMarker!!)
            }

            clusterManager?.setOnClusterItemInfoWindowClickListener {
                if (null != it.googleMapMarker) {
                    this@CapacitorGoogleMap.onInfoWindowClick(it.googleMapMarker!!)
                }
            }

            clusterManager?.setOnClusterInfoWindowClickListener {
                val data = this@CapacitorGoogleMap.getClusterData(it)
                delegate.notify("onClusterInfoWindowClick", data)
            }

            clusterManager?.setOnClusterClickListener {
                val data = this@CapacitorGoogleMap.getClusterData(it)
                delegate.notify("onClusterClick", data)
                false
            }
        }
    }

    private fun getClusterData(it: Cluster<CapacitorGoogleMapMarker>): JSObject {
        val data = JSObject()
        data.put("mapId", this.id)
        data.put("latitude", it.position.latitude)
        data.put("longitude", it.position.longitude)
        data.put("size", it.size)

        val items = JSArray()
        for (item in it.items) {
            val marker = item.googleMapMarker

            if (marker != null) {
                val jsItem = JSObject()
                jsItem.put("markerId", marker.id)
                jsItem.put("latitude", marker.position.latitude)
                jsItem.put("longitude", marker.position.longitude)
                jsItem.put("title", marker.title)
                jsItem.put("snippet", marker.snippet)

                items.put(jsItem)
            }
        }

        data.put("items", items)

        return data
    }

    override fun onMapClick(point: LatLng) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("latitude", point.latitude)
        data.put("longitude", point.longitude)
        delegate.notify("onMapClick", data)
    }

    override fun onMarkerClick(marker: Marker): Boolean {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onMarkerClick", data)
        return false
    }

    override fun onPolylineClick(polyline: Polyline) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("polylineId", polyline.id)
        data.put("tag", polyline.tag)
        delegate.notify("onPolylineClick", data)
    }

    override fun onMarkerDrag(marker: Marker) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onMarkerDrag", data)
    }

    override fun onMarkerDragStart(marker: Marker) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onMarkerDragStart", data)
    }

    override fun onMarkerDragEnd(marker: Marker) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onMarkerDragEnd", data)
    }

    override fun onMyLocationButtonClick(): Boolean {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        delegate.notify("onMyLocationButtonClick", data)
        return false
    }

    override fun onMyLocationClick(location: Location) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("latitude", location.latitude)
        data.put("longitude", location.longitude)
        delegate.notify("onMyLocationClick", data)
    }

    override fun onCameraIdle() {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("bounds", getLatLngBoundsJSObject(getLatLngBounds()))
        data.put("bearing", this@CapacitorGoogleMap.googleMap?.cameraPosition?.bearing)
        data.put("latitude", this@CapacitorGoogleMap.googleMap?.cameraPosition?.target?.latitude)
        data.put("longitude", this@CapacitorGoogleMap.googleMap?.cameraPosition?.target?.longitude)
        data.put("tilt", this@CapacitorGoogleMap.googleMap?.cameraPosition?.tilt)
        data.put("zoom", this@CapacitorGoogleMap.googleMap?.cameraPosition?.zoom)
        delegate.notify("onCameraIdle", data)
        delegate.notify("onBoundsChanged", data)
    }

    override fun onCameraMoveStarted(reason: Int) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("isGesture", reason == 1)
        delegate.notify("onCameraMoveStarted", data)
    }

    override fun onInfoWindowClick(marker: Marker) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("markerId", marker.id)
        data.put("latitude", marker.position.latitude)
        data.put("longitude", marker.position.longitude)
        data.put("title", marker.title)
        data.put("snippet", marker.snippet)
        delegate.notify("onInfoWindowClick", data)
    }

    override fun onCameraMove() {
        debounceJob?.cancel()
        debounceJob = CoroutineScope(Dispatchers.Main).launch {
            delay(100)
            clusterManager?.cluster()
        }
    }

    override fun onPolygonClick(polygon: Polygon) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("polygonId", polygon.id)
        data.put("tag", polygon.tag)
        delegate.notify("onPolygonClick", data)
    }

    override fun onCircleClick(circle: Circle) {
        val data = JSObject()
        data.put("mapId", this@CapacitorGoogleMap.id)
        data.put("circleId", circle.id)
        data.put("tag", circle.tag)
        data.put("latitude", circle.center.latitude)
        data.put("longitude", circle.center.longitude)
        data.put("radius", circle.radius)

        delegate.notify("onCircleClick", data)
    }
}

fun getLatLngBoundsJSObject(bounds: LatLngBounds): JSObject {
    val data = JSObject()

    val southwestJS = JSObject()
    val centerJS = JSObject()
    val northeastJS = JSObject()

    southwestJS.put("lat", bounds.southwest.latitude)
    southwestJS.put("lng", bounds.southwest.longitude)
    centerJS.put("lat", bounds.center.latitude)
    centerJS.put("lng", bounds.center.longitude)
    northeastJS.put("lat", bounds.northeast.latitude)
    northeastJS.put("lng", bounds.northeast.longitude)

    data.put("southwest", southwestJS)
    data.put("center", centerJS)
    data.put("northeast", northeastJS)

    return data
}
