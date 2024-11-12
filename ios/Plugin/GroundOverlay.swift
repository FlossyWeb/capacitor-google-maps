import Foundation
import GoogleMaps
import Capacitor

public struct GroundOverlay {
    let bounds: GMSCoordinateBounds
    let opacity: Float
    let imageUrl: String
    let zIndex: Int32?
    
    init(fromJSObject: JSObject) throws {
        // print("Received JSObject: \(fromJSObject)")

        guard let imageUrl = fromJSObject["imageUrl"] as? String,
              let boundsData = fromJSObject["bounds"] as? JSObject,
              let opacityNumber = fromJSObject["opacity"] as? NSNumber,
              let southWestData = boundsData["southwest"] as? JSObject,
              let northEastData = boundsData["northeast"] as? JSObject,
              let southWestLat = southWestData["lat"] as? Double,
              let southWestLng = southWestData["lng"] as? Double,
              let northEastLat = northEastData["lat"] as? Double,
              let northEastLng = northEastData["lng"] as? Double else {
                  throw GoogleMapErrors.invalidArguments("GroundOverlay object is missing required properties")
              }

        // guard let opacity = fromJSObject["opacity"] as? Float else {
        //     throw GoogleMapErrors.invalidArguments("GroundOverlay object is missing opacity")
        // }
        // guard let opacityNumber = fromJSObject["opacity"] as? NSNumber else {
        //     throw GoogleMapErrors.invalidArguments("GroundOverlay object is missing opacity")
        // }
        self.opacity = opacityNumber.floatValue

        let swCoordinate = CLLocationCoordinate2D(latitude: southWestLat, longitude: southWestLng)
        let neCoordinate = CLLocationCoordinate2D(latitude: northEastLat, longitude: northEastLng)
        self.bounds = GMSCoordinateBounds(coordinate: swCoordinate, coordinate: neCoordinate)
        self.imageUrl = imageUrl
        // self.opacity = opacity

        self.zIndex = Int32((fromJSObject["zIndex"] as? Int) ?? 1)
    }
}


public struct MultipleGroundOverlays {
    let boundsArray: [GMSCoordinateBounds]
    let opacity: Float
    let imageUrls: [String]

    init(fromJSObject: JSObject) throws {
        print("Received JSObject for multiple overlays: \(fromJSObject)")

        guard let imageUrls = fromJSObject["imageUrl"] as? [String],
              let boundsArrayData = fromJSObject["bounds"] as? [JSObject],
              let opacityNumber = fromJSObject["opacity"] as? NSNumber else {
            throw GoogleMapErrors.invalidArguments("MultipleGroundOverlays object is missing required properties")
        }

        self.opacity = opacityNumber.floatValue

        var tempBoundsArray = [GMSCoordinateBounds]()
        for boundsData in boundsArrayData {
            guard let southWestData = boundsData["southwest"] as? JSObject,
                  let northEastData = boundsData["northeast"] as? JSObject,
                  let southWestLat = southWestData["lat"] as? Double,
                  let southWestLng = southWestData["lng"] as? Double,
                  let northEastLat = northEastData["lat"] as? Double,
                  let northEastLng = northEastData["lng"] as? Double else {
                      throw GoogleMapErrors.invalidArguments("GroundOverlay object is missing required properties")
                  }

            let swCoordinate = CLLocationCoordinate2D(latitude: southWestLat, longitude: southWestLng)
            let neCoordinate = CLLocationCoordinate2D(latitude: northEastLat, longitude: northEastLng)
            tempBoundsArray.append(GMSCoordinateBounds(coordinate: swCoordinate, coordinate: neCoordinate))
        }
        self.boundsArray = tempBoundsArray
        self.imageUrls = imageUrls
    }
}
