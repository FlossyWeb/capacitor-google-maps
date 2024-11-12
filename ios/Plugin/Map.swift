import Foundation
import GoogleMaps
import Capacitor
import GoogleMapsUtils
import CommonCrypto
import SDWebImage

public struct LatLng: Codable {
    let lat: Double
    let lng: Double
}

class GMViewController: UIViewController {
    var mapViewBounds: [String: Double]!
    var savedBounds: CGRect?
    var GMapView: GMSMapView!
    var cameraPosition: [String: Double]!
    var minimumClusterSize: Int?
    var mapId: String?

    private var clusterManager: GMUClusterManager?

    var clusteringEnabled: Bool {
        return clusterManager != nil
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        let camera = GMSCameraPosition.camera(withLatitude: cameraPosition["latitude"] ?? 0, longitude: cameraPosition["longitude"] ?? 0, zoom: Float(cameraPosition["zoom"] ?? 12))
        let frame = CGRect(x: mapViewBounds["x"] ?? 0, y: mapViewBounds["y"] ?? 0, width: mapViewBounds["width"] ?? 0, height: mapViewBounds["height"] ?? 0)
        if let id = mapId {
            let gmsId = GMSMapID(identifier: id)
            self.GMapView = GMSMapView(frame: frame, mapID: gmsId, camera: camera)
        } else {
            self.GMapView = GMSMapView(frame: frame, camera: camera)
        }

        self.view = GMapView
    }

    func initClusterManager(_ minClusterSize: Int?) {
        let iconGenerator = GMUDefaultClusterIconGenerator()
        let algorithm = GMUNonHierarchicalDistanceBasedAlgorithm()
        let renderer = GMUDefaultClusterRenderer(mapView: self.GMapView, clusterIconGenerator: iconGenerator)
        self.minimumClusterSize = minClusterSize
        if let minClusterSize = minClusterSize {
            renderer.minimumClusterSize = UInt(minClusterSize)
        }
        self.clusterManager = GMUClusterManager(map: self.GMapView, algorithm: algorithm, renderer: renderer)
    }

    func destroyClusterManager() {
        self.clusterManager = nil
    }

    func addMarkersToCluster(markers: [GMSMarker]) {
        if let clusterManager = clusterManager {
            clusterManager.add(markers)
            clusterManager.cluster()
        }
    }

    func removeMarkersFromCluster(markers: [GMSMarker]) {
        if let clusterManager = clusterManager {
            markers.forEach { marker in
                clusterManager.remove(marker)
            }
            clusterManager.cluster()
        }
    }
}

// swiftlint:disable type_body_length
public class Map {
    var id: String
    var savedBounds: CGRect? // Optional, as it may not always be set
    var config: GoogleMapConfig
    var mapViewController: GMViewController
    var targetViewController: UIView?
    var polygons = [Int: GMSPolygon]()
    var circles = [Int: GMSCircle]()
    var polylines = [Int: GMSPolyline]()
    var tileLayers: [Int: GMSTileLayer] = [:]

    var markers = [Int: GMSMarker]()
    var markerIcons = [String: UIImage]()
  
    // var groundOverlays = [Int: GMSGroundOverlay]()
    // var overlayImages = [String: UIImage]()  // Cache for ground overlay images
    
    var currentOverlay: GMSGroundOverlay?
    var multipleCurrentOverlays = [String: GMSGroundOverlay]()  // Dictionary to store multiple overlays
    

    var currentTileLayer: GMSTileLayer?  // Reference to the current tile layer

    // var nextOverlayId = 1
    // var mapView: GMSMapView
    // var groundOverlays = [String: GroundOverlay]()
    // var groundOverlays: [String: GroundOverlay] = [:]

    // swiftlint:disable identifier_name
    public static let MAP_TAG = 99999
    // swiftlint:enable identifier_name

    // swiftlint:disable weak_delegate
    private var delegate: CapacitorGoogleMapsPlugin

    init(id: String, config: GoogleMapConfig, delegate: CapacitorGoogleMapsPlugin) {
        self.id = id
        self.config = config
        self.delegate = delegate
        self.mapViewController = GMViewController()
        self.mapViewController.mapId = config.mapId

        self.render()
    }

    func render() {
        DispatchQueue.main.async {
            self.mapViewController.mapViewBounds = [
                "width": self.config.width,
                "height": self.config.height,
                "x": self.config.x,
                "y": self.config.y
            ]

            self.mapViewController.cameraPosition = [
                "latitude": self.config.center.lat,
                "longitude": self.config.center.lng,
                "zoom": self.config.zoom
            ]

            self.targetViewController = self.getTargetContainer(refWidth: self.config.width, refHeight: self.config.height)

            if let target = self.targetViewController {
                target.tag = Map.MAP_TAG
                target.removeAllSubview()
                self.mapViewController.view.frame = target.bounds
                target.addSubview(self.mapViewController.view)
                self.mapViewController.GMapView.delegate = self.delegate
            }

            if let styles = self.config.styles {
                do {
                    self.mapViewController.GMapView.mapStyle = try GMSMapStyle(jsonString: styles)
                } catch {
                    CAPLog.print("Invalid Google Maps styles")
                }
            }

            self.delegate.notifyListeners("onMapReady", data: [
                "mapId": self.id
            ])
        }
    }

    func updateRender(mapBounds: CGRect) {
        DispatchQueue.main.sync {
            let newWidth = round(Double(mapBounds.width))
            let newHeight = round(Double(mapBounds.height))
            let isWidthEqual = round(Double(self.mapViewController.view.bounds.width)) == newWidth
            let isHeightEqual = round(Double(self.mapViewController.view.bounds.height)) == newHeight

            if !isWidthEqual || !isHeightEqual {
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                self.mapViewController.view.frame.size.width = newWidth
                self.mapViewController.view.frame.size.height = newHeight
                CATransaction.commit()
            }
        }
    }

    func rebindTargetContainer(mapBounds: CGRect) {
        DispatchQueue.main.sync {
            if let target = self.getTargetContainer(refWidth: round(Double(mapBounds.width)), refHeight: round(Double(mapBounds.height))) {
                self.targetViewController = target
                target.tag = Map.MAP_TAG
                target.removeAllSubview()
                CATransaction.begin()
                CATransaction.setDisableActions(true)
                self.mapViewController.view.frame.size.width = mapBounds.width
                self.mapViewController.view.frame.size.height = mapBounds.height
                CATransaction.commit()
                target.addSubview(self.mapViewController.view)
            }
        }
    }

    private func getTargetContainer(refWidth: Double, refHeight: Double) -> UIView? {
        if let bridge = self.delegate.bridge {
            for item in bridge.webView!.getAllSubViews() {
                let isScrollView = item.isKind(of: NSClassFromString("WKChildScrollView")!) || item.isKind(of: NSClassFromString("WKScrollView")!)
                let isBridgeScrollView = item.isEqual(bridge.webView?.scrollView)

                if isScrollView && !isBridgeScrollView {
                    (item as? UIScrollView)?.isScrollEnabled = true

                    let height = Double((item as? UIScrollView)?.contentSize.height ?? 0)
                    let width = Double((item as? UIScrollView)?.contentSize.width ?? 0)
                    let actualHeight = round(height / 2)

                    let isWidthEqual = width == self.config.width
                    let isHeightEqual = actualHeight == self.config.height

                    if isWidthEqual && isHeightEqual && item.tag < self.targetViewController?.tag ?? Map.MAP_TAG {
                        return item
                    }
                }
            }
        }

        return nil
    }

    func destroy() {
        DispatchQueue.main.async {
            self.mapViewController.GMapView = nil
            self.targetViewController?.tag = 0
            self.mapViewController.view = nil
            self.enableTouch()

            self.currentOverlay?.map = nil
            self.currentOverlay = nil
            // self.overlayImages.removeAll()

            self.currentTileLayer?.map = nil  // Remove the current tile layer
            self.currentTileLayer = nil
        }
    }

    func enableTouch() {
        DispatchQueue.main.async {
            if let target = self.targetViewController, let itemIndex = WKWebView.disabledTargets.firstIndex(of: target) {
                WKWebView.disabledTargets.remove(at: itemIndex)
            }
        }
    }

    func disableTouch() {
        DispatchQueue.main.async {
            if let target = self.targetViewController, !WKWebView.disabledTargets.contains(target) {
                WKWebView.disabledTargets.append(target)
            }
        }
    }

    func addTileLayer(tileLayer: TileLayer) throws -> Int {
        var tileLayerHash: Int = 0
        print("begin adding a tile layer in map")
        DispatchQueue.main.sync {
            // let layer: GMSTileLayer

            self.currentTileLayer?.map = nil

            // Create and add the ACTUAL TILE OBJECT
            let newTileLayer = self.buildTile(tileLayer: tileLayer)
            newTileLayer.map = self.mapViewController.GMapView

            self.currentTileLayer = newTileLayer

            tileLayerHash = newTileLayer.hash.hashValue
            self.tileLayers[tileLayerHash] = newTileLayer
        }
        print("Tile layer hash ID: \(tileLayerHash)")
        return tileLayerHash
    }


    func buildTile(tileLayer: TileLayer) -> GMSTileLayer {
        // Implement GMSTileURLConstructor
        let urls: GMSTileURLConstructor = { (x, y, zoom) in
            let zoomLimit = (tileLayer.maxZoom != nil) ? tileLayer.maxZoom! + 1 : 19
            // avoids making http get calls for tiles beyond the limit
            if zoom > zoomLimit {
                print("Zoom level \(zoom) exceeds limit of \(zoomLimit). Not fetching new tiles.")
                return nil // Return nil to prevent fetching new tiles
            }

            // Construct the URL for the tile
            var url = tileLayer.tileUrl ?? ""
            
            // Replace placeholders with actual values
            url = url.replacingOccurrences(of: "{zoom}", with: "\(zoom)")
            url = url.replacingOccurrences(of: "{x}", with: "\(x)")
            url = url.replacingOccurrences(of: "{y}", with: "\(y)")
            
            print("Constructed URL: \(url)")
            return URL(string: url)
        }

        // Create the GMSTileLayer
        let newLayer = GMSURLTileLayer(urlConstructor: urls)
        newLayer.opacity = tileLayer.opacity ?? 1
        newLayer.zIndex = tileLayer.zIndex ?? 1

        return newLayer
    }


    // func buildTile(tileLayer: TileLayer) -> GMSTileLayer {
    //     // Implement GMSTileURLConstructor
    //     // Returns a Tile based on the x,y,zoom coordinates, and the requested floor
    //     let urls: GMSTileURLConstructor = { (x, y, zoom) in
    //       var url = tileLayer.tileUrl ?? ""
        
        

    //       // Replace placeholders with actual values
    //       url = url.replacingOccurrences(of: "{zoom}", with: "\(zoom)")
    //       url = url.replacingOccurrences(of: "{x}", with: "\(x)")
    //       url = url.replacingOccurrences(of: "{y}", with: "\(y)")
          
    //       print("Constructed URL: \(url)")
    //       return URL(string: url)
    //     }

    //     // Create the GMSTileLayer
    //     let newLayer = GMSURLTileLayer(urlConstructor: urls)

    //     newLayer.opacity = tileLayer.opacity ?? 1
    //     newLayer.zIndex = tileLayer.zIndex ?? 1

    //     return newLayer
    // }

    func removeTileLayer() {
        DispatchQueue.main.async {
            self.currentTileLayer?.map = nil
            self.currentTileLayer = nil
        }
    }

    func setTileLayerOpacity(opacity: Float) {
        // Ensure opacity is clamped between 0.0 and 1.0
        let clampedOpacity = max(0.0, min(1.0, opacity))

        // Check if there is a current tile layer
        if let currentTileLayer = self.currentTileLayer {
            DispatchQueue.main.sync {
                currentTileLayer.opacity = clampedOpacity
            }
            print("Tile layer opacity set to \(clampedOpacity)")
        } else {
            print("No current tile layer to update opacity.")
        }
    }


    func addMarker(marker: Marker) throws -> Int {
        var markerHash = 0

        DispatchQueue.main.sync {
            let newMarker = self.buildMarker(marker: marker)

            if self.mapViewController.clusteringEnabled {
                self.mapViewController.addMarkersToCluster(markers: [newMarker])
            } else {
                newMarker.map = self.mapViewController.GMapView
            }

            self.markers[newMarker.hash.hashValue] = newMarker

            markerHash = newMarker.hash.hashValue
        }

        return markerHash
    }

    func addMarkers(markers: [Marker]) throws -> [Int] {
        var markerHashes: [Int] = []

        DispatchQueue.main.sync {
            var googleMapsMarkers: [GMSMarker] = []

            markers.forEach { marker in
                let newMarker = self.buildMarker(marker: marker)

                if self.mapViewController.clusteringEnabled {
                    googleMapsMarkers.append(newMarker)
                } else {
                    newMarker.map = self.mapViewController.GMapView
                }

                self.markers[newMarker.hash.hashValue] = newMarker

                markerHashes.append(newMarker.hash.hashValue)
            }

            if self.mapViewController.clusteringEnabled {
                self.mapViewController.addMarkersToCluster(markers: googleMapsMarkers)
            }
        }

        return markerHashes
    }

    func addPolygons(polygons: [Polygon]) throws -> [Int] {
        var polygonHashes: [Int] = []

        DispatchQueue.main.sync {
            polygons.forEach { polygon in
                let newPolygon = self.buildPolygon(polygon: polygon)
                newPolygon.map = self.mapViewController.GMapView

                self.polygons[newPolygon.hash.hashValue] = newPolygon

                polygonHashes.append(newPolygon.hash.hashValue)
            }
        }

        return polygonHashes
    }

    func addCircles(circles: [Circle]) throws -> [Int] {
        var circleHashes: [Int] = []

        DispatchQueue.main.sync {
            circles.forEach { circle in
                let newCircle = self.buildCircle(circle: circle)
                newCircle.map = self.mapViewController.GMapView

                self.circles[newCircle.hash.hashValue] = newCircle

                circleHashes.append(newCircle.hash.hashValue)
            }
        }

        return circleHashes
    }

    func addPolylines(lines: [Polyline]) throws -> [Int] {
        var polylineHashes: [Int] = []

        DispatchQueue.main.sync {
            lines.forEach { line in
                let newLine = self.buildPolyline(line: line)
                newLine.map = self.mapViewController.GMapView

                self.polylines[newLine.hash.hashValue] = newLine

                polylineHashes.append(newLine.hash.hashValue)
            }
        }

        return polylineHashes
    }

    // func addGroundOverlay(overlay: Overlay) throws -> String {
    //     var overlayId: String = ""

    //     DispatchQueue.main.sync {
    //         let newOverlay = self.buildGroundOverlay(overlay: overlay)
    //         newOverlay.map = self.mapViewController.GMapView

    //         self.groundOverlays[newOverlay.id] = newOverlay

    //         overlayId = newOverlay.id
    //     }

    //     return overlayId
    // }

    // func removeGroundOverlay(id: String) throws {
    //     DispatchQueue.main.sync {
    //         guard let overlay = self.groundOverlays[id] else {
    //             throw GoogleMapErrors.overlayNotFound
    //         }

    //         overlay.groundOverlay?.map = nil
    //         self.groundOverlays.removeValue(forKey: id)
    //     }
    // }

    func updateMapOptions(options: [String: Any]) {
        DispatchQueue.main.sync {
            if let zoom = options["zoom"] as? Float {
                mapViewController.GMapView.animate(toZoom: zoom)
            }

            if let center = options["center"] as? [String: Double],
              let lat = center["lat"],
              let lng = center["lng"] {
                let coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lng)
                mapViewController.GMapView.animate(toLocation: coordinate)
            }

            if let styles = options["styles"] as? [[String: Any]] {
                do {
                    let jsonData = try JSONSerialization.data(withJSONObject: styles, options: [])
                    let jsonString = String(data: jsonData, encoding: .utf8) ?? ""
                    let mapStyle = try GMSMapStyle(jsonString: jsonString)
                    mapViewController.GMapView.mapStyle = mapStyle
                } catch {
                    print("Error setting map styles: \(error)")
                }
            }
        }
    }

    func shortHash(from url: String) -> String {
        // Convert the string to Data
        guard let urlData = url.data(using: .utf8) else {
            return ""
        }
        
        // Create a buffer for the hash
        var digest = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
        
        // Compute the MD5 hash
        urlData.withUnsafeBytes {
            _ = CC_MD5($0.baseAddress, CC_LONG(urlData.count), &digest)
        }
        
        // Convert the digest to a hex string
        let hash = digest.map { String(format: "%02x", $0) }.joined()
        
        // Return the first 6 characters of the hash (you can adjust the length)
        return String(hash.prefix(6))
    }

    func addOrUpdateGroundOverlay(overlay: GroundOverlay, index: String) throws -> String {
        var overlayId = ""

        if let url = URL(string: overlay.imageUrl) {
            // Use SDWebImage to load the image asynchronously
            SDWebImageManager.shared.loadImage(with: url, options: .highPriority, progress: nil) { [weak self] (image, data, error, cacheType, finished, url) in
                guard let self = self else { return }
                
                if let error = error {
                    print("Error loading image: \(error.localizedDescription)")
                    return
                }

                DispatchQueue.main.async {
                    if let iconImage = image {
                        // Check if an overlay already exists
                        if let existingOverlay = self.multipleCurrentOverlays[index] {
                            // Update the existing overlay with the new image
                            existingOverlay.icon = iconImage
                        } else {
                            // Create a new overlay and add it to the map
                            let newOverlay = self.buildGroundOverlay(overlay: overlay)
                            newOverlay.icon = iconImage
                            newOverlay.map = self.mapViewController.GMapView
                            self.multipleCurrentOverlays[index] = newOverlay
                        }
                    }
                }
            }
        }

        // Return the overlay index immediately
        overlayId = index
        return overlayId
    }

    func addMultipleGroundOverlays(overlaysData: JSObject) throws -> String {
        var firstOverlayId: String?

        guard let imageUrls = overlaysData["imageUrl"] as? [String],
              let boundsData = overlaysData["bounds"] as? [JSObject],
              let opacity = overlaysData["opacity"] as? NSNumber else {
            throw GoogleMapErrors.invalidArguments("Missing required imageUrl, bounds, or opacity in overlays object")
        }

        guard imageUrls.count == boundsData.count else {
            throw GoogleMapErrors.invalidArguments("Mismatched imageUrl and bounds count")
        }

        for (index, bounds) in boundsData.enumerated() {
            let singleOverlayData: JSObject = [
                "imageUrl": imageUrls[index],
                "bounds": bounds,
                "opacity": opacity
            ]

            let overlay = try GroundOverlay(fromJSObject: singleOverlayData)
            let overlayId = try self.addOrUpdateGroundOverlay(overlay: overlay, index: String(index))

            // this helps in avoiding tracking too many overlays
            firstOverlayId = firstOverlayId ?? overlayId  // Store the first overlay ID
        }

        if let firstId = firstOverlayId {
            return firstId
        } else {
            throw GoogleMapErrors.invalidArguments("Failed to add overlays")
        }
    }
    private func buildGroundOverlay(overlay: GroundOverlay) -> GMSGroundOverlay {
        let newOverlay = GMSGroundOverlay()
        newOverlay.bounds = overlay.bounds
        newOverlay.opacity = overlay.opacity
        newOverlay.zIndex = overlay.zIndex ?? 1

        // Load the image asynchronously from the URL
        if let url = URL(string: overlay.imageUrl) {
            URLSession.shared.dataTask(with: url) { (data, _, _) in
                DispatchQueue.main.async {
                    if let data = data, let iconImage = UIImage(data: data) {
                        newOverlay.icon = iconImage // Set the icon asynchronously
                    }
                }
            }.resume()
        }

        return newOverlay
    }


    // private func buildGroundOverlay(overlay: GroundOverlay) -> GMSGroundOverlay {
    //     let newOverlay = GMSGroundOverlay()
    //     newOverlay.bounds = overlay.bounds
    //     newOverlay.opacity = overlay.opacity
    //     newOverlay.zIndex = overlay.zIndex ?? 1

    //     // Check if the image is already cached in the Map class
    //     if let cachedImage = self.overlayImages[overlay.imageUrl] {
    //         newOverlay.icon = cachedImage
    //     } else {
    //         // Load the image from the URL
    //         if overlay.imageUrl.starts(with: "https:") {
    //             if let url = URL(string: overlay.imageUrl) {
    //                 URLSession.shared.dataTask(with: url) { (data, _, _) in
    //                     DispatchQueue.main.async {
    //                         if let data = data, let iconImage = UIImage(data: data) {
    //                             self.overlayImages[overlay.imageUrl] = iconImage  // Cache the image in the Map class
    //                             newOverlay.icon = iconImage
    //                         }
    //                     }
    //                 }.resume()
    //             }
    //         } else if let iconImage = UIImage(named: "public/\(overlay.imageUrl)") {
    //             self.overlayImages[overlay.imageUrl] = iconImage  // Cache the image in the Map class
    //             newOverlay.icon = iconImage
    //         } else {
    //             print("CapacitorGoogleMaps Warning: could not load image '\(overlay.imageUrl)'. Using default overlay icon.")
    //         }
    //     }

        
    //     return newOverlay
    // }
    
    // not working at the moment
    func setCurrentOverlayImage(imageUrl: String, opacity: Float) {
        DispatchQueue.main.sync {
            currentOverlay?.map = nil

            let newOverlay = GMSGroundOverlay(bounds: currentOverlay!.bounds, icon: UIImage(named: imageUrl))
            newOverlay.opacity = opacity
            newOverlay.map = mapViewController.GMapView

            currentOverlay = newOverlay
        }
    }


    func setOverlayOpacity(opacity: Float) {
        // Adjust the opacity of the single current overlay, if it exists
        if let currentOverlay = self.currentOverlay {
            DispatchQueue.main.sync {
                currentOverlay.opacity = opacity
            }
        }

        // Ensure there are multiple overlays to update
        guard !self.multipleCurrentOverlays.isEmpty else {
            print("No existing multiple overlays to update.")
            return
        }

        // Update the opacity of all overlays in multipleCurrentOverlays
        DispatchQueue.main.sync {
            for (_, overlay) in self.multipleCurrentOverlays {
                overlay.opacity = opacity
            }
        }
    }


    func removeAllGroundOverlays() {
        DispatchQueue.main.sync {
            currentOverlay?.map = nil
            currentOverlay = nil
            // for removing multiple..
            for (overlayId, overlay) in self.multipleCurrentOverlays {
                overlay.map = nil  // Remove the overlay from the map
            }
            self.multipleCurrentOverlays.removeAll()  // Clear the dictionary
        }
    }



    func enableClustering(_ minClusterSize: Int?) {
        if !self.mapViewController.clusteringEnabled {
            DispatchQueue.main.sync {
                self.mapViewController.initClusterManager(minClusterSize)

                // add existing markers to the cluster
                if !self.markers.isEmpty {
                    var existingMarkers: [GMSMarker] = []
                    for (_, marker) in self.markers {
                        marker.map = nil
                        existingMarkers.append(marker)
                    }

                    self.mapViewController.addMarkersToCluster(markers: existingMarkers)
                }
            }
        } else if self.mapViewController.minimumClusterSize != minClusterSize {
            self.mapViewController.destroyClusterManager()
            enableClustering(minClusterSize)
        }
    }

    func disableClustering() {
        DispatchQueue.main.sync {
            self.mapViewController.destroyClusterManager()

            // add existing markers back to the map
            if !self.markers.isEmpty {
                for (_, marker) in self.markers {
                    marker.map = self.mapViewController.GMapView
                }
            }
        }
    }

    func removeMarker(id: Int) throws {
        if let marker = self.markers[id] {
            DispatchQueue.main.async {
                if self.mapViewController.clusteringEnabled {
                    self.mapViewController.removeMarkersFromCluster(markers: [marker])
                }

                marker.map = nil
                self.markers.removeValue(forKey: id)

            }
        } else {
            throw GoogleMapErrors.markerNotFound
        }
    }

    func removePolygons(ids: [Int]) throws {
        DispatchQueue.main.sync {
            ids.forEach { id in
                if let polygon = self.polygons[id] {
                    polygon.map = nil
                    self.polygons.removeValue(forKey: id)
                }
            }
        }
    }

    func removeCircles(ids: [Int]) throws {
        DispatchQueue.main.sync {
            ids.forEach { id in
                if let circle = self.circles[id] {
                    circle.map = nil
                    self.circles.removeValue(forKey: id)
                }
            }
        }
    }

    func removePolylines(ids: [Int]) throws {
        DispatchQueue.main.sync {
            ids.forEach { id in
                if let line = self.polylines[id] {
                    line.map = nil
                    self.polylines.removeValue(forKey: id)
                }
            }
        }
    }

    func setCamera(config: GoogleMapCameraConfig) throws {
        let currentCamera = self.mapViewController.GMapView.camera

        let lat = config.coordinate?.lat ?? currentCamera.target.latitude
        let lng = config.coordinate?.lng ?? currentCamera.target.longitude

        let zoom = config.zoom ?? currentCamera.zoom
        let bearing = config.bearing ?? Double(currentCamera.bearing)
        let angle = config.angle ?? currentCamera.viewingAngle

        let animate = config.animate ?? false

        DispatchQueue.main.sync {
            let newCamera = GMSCameraPosition(latitude: lat, longitude: lng, zoom: zoom, bearing: bearing, viewingAngle: angle)

            if animate {
                self.mapViewController.GMapView.animate(to: newCamera)
            } else {
                self.mapViewController.GMapView.camera = newCamera
            }
        }

    }

    func getMapType() -> GMSMapViewType {
        return self.mapViewController.GMapView.mapType
    }

    func setMapType(mapType: GMSMapViewType) throws {
        DispatchQueue.main.sync {
            self.mapViewController.GMapView.mapType = mapType
        }
    }

    func enableIndoorMaps(enabled: Bool) throws {
        DispatchQueue.main.sync {
            self.mapViewController.GMapView.isIndoorEnabled = enabled
        }
    }

    func enableTrafficLayer(enabled: Bool) throws {
        DispatchQueue.main.sync {
            self.mapViewController.GMapView.isTrafficEnabled = enabled
        }
    }

    func enableAccessibilityElements(enabled: Bool) throws {
        DispatchQueue.main.sync {
            self.mapViewController.GMapView.accessibilityElementsHidden = enabled
        }
    }

    func enableCurrentLocation(enabled: Bool) throws {
        DispatchQueue.main.sync {
            self.mapViewController.GMapView.isMyLocationEnabled = enabled
        }
    }

    func setPadding(padding: GoogleMapPadding) throws {
        DispatchQueue.main.sync {
            let mapInsets = UIEdgeInsets(top: CGFloat(padding.top), left: CGFloat(padding.left), bottom: CGFloat(padding.bottom), right: CGFloat(padding.right))
            self.mapViewController.GMapView.padding = mapInsets
        }
    }

    func removeMarkers(ids: [Int]) throws {
        DispatchQueue.main.sync {
            var markers: [GMSMarker] = []
            ids.forEach { id in
                if let marker = self.markers[id] {
                    marker.map = nil
                    self.markers.removeValue(forKey: id)
                    markers.append(marker)
                }
            }

            if self.mapViewController.clusteringEnabled {
                self.mapViewController.removeMarkersFromCluster(markers: markers)
            }
        }
    }

    func getMapLatLngBounds() -> GMSCoordinateBounds? {
        return GMSCoordinateBounds(region: self.mapViewController.GMapView.projection.visibleRegion())
    }

    func fitBounds(bounds: GMSCoordinateBounds, padding: CGFloat) {
        DispatchQueue.main.sync {
            let cameraUpdate = GMSCameraUpdate.fit(bounds, withPadding: padding)
            self.mapViewController.GMapView.animate(with: cameraUpdate)
        }
    }

    private func getFrameOverflowBounds(frame: CGRect, mapBounds: CGRect) -> [CGRect] {
        var intersections: [CGRect] = []

        // get top overflow
        if mapBounds.origin.y < frame.origin.y {
            let height = frame.origin.y - mapBounds.origin.y
            let width = mapBounds.width
            intersections.append(CGRect(x: 0, y: 0, width: width, height: height))
        }

        // get bottom overflow
        if (mapBounds.origin.y + mapBounds.height) > (frame.origin.y + frame.height) {
            let height = (mapBounds.origin.y + mapBounds.height) - (frame.origin.y + frame.height)
            let width = mapBounds.width
            intersections.append(CGRect(x: 0, y: mapBounds.height, width: width, height: height))
        }

        return intersections
    }

    private func buildCircle(circle: Circle) -> GMSCircle {
        let newCircle = GMSCircle()
        newCircle.title = circle.title
        newCircle.strokeColor = circle.strokeColor
        newCircle.strokeWidth = circle.strokeWidth
        newCircle.fillColor = circle.fillColor
        newCircle.position = CLLocationCoordinate2D(latitude: circle.center.lat, longitude: circle.center.lng)
        newCircle.radius = CLLocationDistance(circle.radius)
        newCircle.isTappable = circle.tappable ?? false
        newCircle.zIndex = circle.zIndex
        newCircle.userData = circle.tag

        return newCircle
    }

    private func buildPolygon(polygon: Polygon) -> GMSPolygon {
        let newPolygon = GMSPolygon()
        newPolygon.title = polygon.title
        newPolygon.strokeColor = polygon.strokeColor
        newPolygon.strokeWidth = polygon.strokeWidth
        newPolygon.fillColor = polygon.fillColor
        newPolygon.isTappable = polygon.tappable ?? false
        newPolygon.geodesic = polygon.geodesic ?? false
        newPolygon.zIndex = polygon.zIndex
        newPolygon.userData = polygon.tag

        var shapeIndex = 0
        let outerShape = GMSMutablePath()
        var holes: [GMSMutablePath] = []

        polygon.shapes.forEach { shape in
            if shapeIndex == 0 {
                shape.forEach { coord in
                    outerShape.add(CLLocationCoordinate2D(latitude: coord.lat, longitude: coord.lng))
                }
            } else {
                let holeShape = GMSMutablePath()
                shape.forEach { coord in
                    holeShape.add(CLLocationCoordinate2D(latitude: coord.lat, longitude: coord.lng))
                }

                holes.append(holeShape)
            }

            shapeIndex += 1
        }

        newPolygon.path = outerShape
        newPolygon.holes = holes

        return newPolygon
    }

    private func buildPolyline(line: Polyline) -> GMSPolyline {
        let newPolyline = GMSPolyline()
        newPolyline.title = line.title
        newPolyline.strokeColor = line.strokeColor
        newPolyline.strokeWidth = line.strokeWidth
        newPolyline.isTappable = line.tappable ?? false
        newPolyline.geodesic = line.geodesic ?? false
        newPolyline.zIndex = line.zIndex
        newPolyline.userData = line.tag

        let path = GMSMutablePath()
        line.path.forEach { coord in
            path.add(CLLocationCoordinate2D(latitude: coord.lat, longitude: coord.lng))
        }

        newPolyline.path = path

        if line.styleSpans.count > 0 {
            var spans: [GMSStyleSpan] = []

            line.styleSpans.forEach { span in
                if let segments = span.segments {
                    spans.append(GMSStyleSpan(color: span.color, segments: segments))
                } else {
                    spans.append(GMSStyleSpan(color: span.color))
                }
            }

            newPolyline.spans = spans
        }

        return newPolyline
    }

    private func buildMarker(marker: Marker) -> GMSMarker {
        let newMarker = GMSMarker()
        newMarker.position = CLLocationCoordinate2D(latitude: marker.coordinate.lat, longitude: marker.coordinate.lng)
        newMarker.title = marker.title
        newMarker.snippet = marker.snippet
        newMarker.isFlat = marker.isFlat ?? false
        newMarker.opacity = marker.opacity ?? 1
        newMarker.isDraggable = marker.draggable ?? false
        newMarker.zIndex = marker.zIndex
        if let iconAnchor = marker.iconAnchor {
            newMarker.groundAnchor = iconAnchor
        }

        // cache and reuse marker icon uiimages
        if let iconUrl = marker.iconUrl {
            if let iconImage = self.markerIcons[iconUrl] {
                newMarker.icon = getResizedIcon(iconImage, marker)
            } else {
                if iconUrl.starts(with: "https:") {
                    if let url = URL(string: iconUrl) {
                        URLSession.shared.dataTask(with: url) { (data, _, _) in
                            DispatchQueue.main.async {
                                if let data = data, let iconImage = UIImage(data: data) {
                                    self.markerIcons[iconUrl] = iconImage
                                    newMarker.icon = getResizedIcon(iconImage, marker)
                                }
                            }
                        }.resume()
                    }
                } else if let iconImage = UIImage(named: "public/\(iconUrl)") {
                    self.markerIcons[iconUrl] = iconImage
                    newMarker.icon = getResizedIcon(iconImage, marker)
                } else {
                    var detailedMessage = ""

                    if iconUrl.hasSuffix(".svg") {
                        detailedMessage = "SVG not supported."
                    }

                    print("CapacitorGoogleMaps Warning: could not load image '\(iconUrl)'. \(detailedMessage)  Using default marker icon.")
                }
            }
        } else {
            if let color = marker.color {
                newMarker.icon = GMSMarker.markerImage(with: color)
            }
        }

        return newMarker
    }
}

private func getResizedIcon(_ iconImage: UIImage, _ marker: Marker) -> UIImage? {
    if let iconSize = marker.iconSize {
        return iconImage.resizeImageTo(size: iconSize)
    } else {
        return iconImage
    }
}

extension WKWebView {
    static var disabledTargets: [UIView] = []

    override open func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        var hitView = super.hitTest(point, with: event)

        if let tempHitView = hitView, WKWebView.disabledTargets.contains(tempHitView) {
            return nil
        }

        if let typeClass = NSClassFromString("WKChildScrollView"), let tempHitView = hitView, tempHitView.isKind(of: typeClass) {
            for item in tempHitView.subviews.reversed() {
                let convertPoint = item.convert(point, from: self)
                if let hitTestView = item.hitTest(convertPoint, with: event) {
                    hitView = hitTestView
                    break
                }
            }
        }

        return hitView
    }
}

extension UIView {
    private static var allSubviews: [UIView] = []

    private func viewArray(root: UIView) -> [UIView] {
        var index = root.tag
        for view in root.subviews {
            if view.tag == Map.MAP_TAG {
                // view already in use as in map
                continue
            }

            // tag the index depth of the uiview
            view.tag = index

            if view.isKind(of: UIView.self) {
                UIView.allSubviews.append(view)
            }
            _ = viewArray(root: view)

            index += 1
        }
        return UIView.allSubviews
    }

    fileprivate func getAllSubViews() -> [UIView] {
        UIView.allSubviews = []
        return viewArray(root: self).reversed()
    }

    fileprivate func removeAllSubview() {
        subviews.forEach {
            $0.removeFromSuperview()
        }
    }
}

extension UIImage {
    func resizeImageTo(size: CGSize) -> UIImage? {
        UIGraphicsBeginImageContextWithOptions(size, false, 0.0)
        self.draw(in: CGRect(origin: CGPoint.zero, size: size))
        let resizedImage = UIGraphicsGetImageFromCurrentImageContext()!
        UIGraphicsEndImageContext()
        return resizedImage
    }
}
