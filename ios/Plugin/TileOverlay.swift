import Foundation
import Capacitor
import GoogleMaps

public struct TileLayer {
    let tileUrl: String
    let opacity: Float
    let zIndex: Int32?
    let maxZoom: Int32?
    
    init(fromJSObject: JSObject) throws {
      print("Received JSObject: \(fromJSObject)")

      guard let tileUrl = fromJSObject["tileUrl"] as? String,
            let opacityNumber = fromJSObject["opacity"] as? NSNumber else {
              throw GoogleMapErrors.invalidArguments("TileLayer object is missing required properties")
            }
  
      self.tileUrl = tileUrl
      self.opacity = opacityNumber.floatValue  
      self.zIndex = Int32((fromJSObject["zIndex"] as? Int) ?? 1)
      self.maxZoom = Int32((fromJSObject["maxZoom"] as? Int) ?? 19)
    }

}
