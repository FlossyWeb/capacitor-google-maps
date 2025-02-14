import { WebPlugin } from '@capacitor/core';
import type {
  Cluster,
  onClusterClickHandler,
} from '@googlemaps/markerclusterer';
import {
  MarkerClusterer,
  SuperClusterAlgorithm,
} from '@googlemaps/markerclusterer';

import type { GroundOverlay, Marker } from './definitions';
import { MapType, LatLngBounds } from './definitions';
import type {
  AddMarkerArgs,
  CameraArgs,
  AddMarkersArgs,
  CapacitorGoogleMapsPlugin,
  CreateMapArgs,
  CurrentLocArgs,
  DestroyMapArgs,
  MapTypeArgs,
  PaddingArgs,
  RemoveMarkerArgs,
  TrafficLayerArgs,
  RemoveMarkersArgs,
  MapBoundsContainsArgs,
  EnableClusteringArgs,
  FitBoundsArgs,
  MapBoundsExtendArgs,
  AddPolygonsArgs,
  RemovePolygonsArgs,
  AddCirclesArgs,
  RemoveCirclesArgs,
  AddPolylinesArgs,
  RemovePolylinesArgs,
  AddGroundOverlayArgs,
  RemoveGroundOverlayArgs,
  SetOverlayOpacityArgs,
  RemoveAllGroundOverlaysArgs,
  SetCurrentOverlayImageArgs,
  UpdateMapOptionArgs,
  AddTileOverlayArgs,
  SetTileLayerOpacityArgs,
  RemoveTileLayerArgs,
  AddMultipleGroundOverlayArgs,
} from './implementation';

export class CapacitorGoogleMapsWeb
  extends WebPlugin
  implements CapacitorGoogleMapsPlugin
{
  private gMapsRef: typeof google.maps | undefined = undefined;
  private maps: {
    [id: string]: {
      element: HTMLElement;
      map: google.maps.Map;
      markers: {
        [id: string]: google.maps.Marker;
      };
      overlays: {
        [id: string]: google.maps.GroundOverlay;
      };
      tiles: {
        [id: string]: google.maps.ImageMapType;
      };
      polygons: {
        [id: string]: google.maps.Polygon;
      };
      circles: {
        [id: string]: google.maps.Circle;
      };
      polylines: {
        [id: string]: google.maps.Polyline;
      };
      markerClusterer?: MarkerClusterer;
      trafficLayer?: google.maps.TrafficLayer;
      tileLayer?: google.maps.ImageMapType;
    };
  } = {};
  private currMarkerId = 0;
  private currGroundOverlayId = 0;
  private currPolygonId = 0;
  private currCircleId = 0;
  private currPolylineId = 0;
  private currentOverlay: google.maps.GroundOverlay | null = null;
  private currentTileLayer: any | null = null;

  private currTileId = 0;
  // private tileLayers: any | null = null;

  private onClusterClickHandler: onClusterClickHandler = (
    _: google.maps.MapMouseEvent,
    cluster: Cluster,
    map: google.maps.Map,
  ): void => {
    const mapId = this.getIdFromMap(map);
    const items: any[] = [];

    if (cluster.markers != undefined) {
      for (const marker of cluster.markers) {
        const markerId = this.getIdFromMarker(mapId, marker);

        items.push({
          markerId: markerId,
          latitude: marker.getPosition()?.lat(),
          longitude: marker.getPosition()?.lng(),
          title: marker.getTitle(),
          snippet: '',
        });
      }
    }

    this.notifyListeners('onClusterClick', {
      mapId: mapId,
      latitude: cluster.position.lat(),
      longitude: cluster.position.lng(),
      size: cluster.count,
      items: items,
    });
  };

  private getIdFromMap(map: google.maps.Map): string {
    for (const id in this.maps) {
      if (this.maps[id].map == map) {
        return id;
      }
    }

    return '';
  }

  private getIdFromMarker(mapId: string, marker: google.maps.Marker): string {
    for (const id in this.maps[mapId].markers) {
      if (this.maps[mapId].markers[id] == marker) {
        return id;
      }
    }

    return '';
  }

  private async importGoogleLib(
    apiKey: string,
    region?: string,
    language?: string,
  ) {
    if (this.gMapsRef === undefined) {
      const lib = await import('@googlemaps/js-api-loader');
      const loader = new lib.Loader({
        apiKey: apiKey ?? '',
        version: 'weekly',
        libraries: ['places'],
        language,
        region,
      });
      const google = await loader.load();
      this.gMapsRef = google.maps;
      console.log('Loaded google maps API');
    }
  }

  async enableTouch(_args: { id: string }): Promise<void> {
    this.maps[_args.id].map.setOptions({ gestureHandling: 'auto' });
  }

  async disableTouch(_args: { id: string }): Promise<void> {
    this.maps[_args.id].map.setOptions({ gestureHandling: 'none' });
  }

  async setCamera(_args: CameraArgs): Promise<void> {
    // Animation not supported yet...
    this.maps[_args.id].map.moveCamera({
      center: _args.config.coordinate,
      heading: _args.config.bearing,
      tilt: _args.config.angle,
      zoom: _args.config.zoom,
    });
  }

  async getMapType(_args: { id: string }): Promise<{ type: string }> {
    let type = this.maps[_args.id].map.getMapTypeId();
    if (type !== undefined) {
      if (type === 'roadmap') {
        type = MapType.Normal;
      }
      return { type: `${type.charAt(0).toUpperCase()}${type.slice(1)}` };
    }
    throw new Error('Map type is undefined');
  }

  async setMapType(_args: MapTypeArgs): Promise<void> {
    let mapType = _args.mapType.toLowerCase();
    if (_args.mapType === MapType.Normal) {
      mapType = 'roadmap';
    }
    this.maps[_args.id].map.setMapTypeId(mapType);
  }

  async enableIndoorMaps(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async enableTrafficLayer(_args: TrafficLayerArgs): Promise<void> {
    const trafficLayer =
      this.maps[_args.id].trafficLayer ?? new google.maps.TrafficLayer();

    if (_args.enabled) {
      trafficLayer.setMap(this.maps[_args.id].map);
      this.maps[_args.id].trafficLayer = trafficLayer;
    } else if (this.maps[_args.id].trafficLayer) {
      trafficLayer.setMap(null);
      this.maps[_args.id].trafficLayer = undefined;
    }
  }

  async enableAccessibilityElements(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  dispatchMapEvent(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async enableCurrentLocation(_args: CurrentLocArgs): Promise<void> {
    if (_args.enabled) {
      if (navigator.geolocation) {
        navigator.geolocation.getCurrentPosition(
          (position: GeolocationPosition) => {
            const pos = {
              lat: position.coords.latitude,
              lng: position.coords.longitude,
            };

            this.maps[_args.id].map.setCenter(pos);

            this.notifyListeners('onMyLocationButtonClick', {});

            this.notifyListeners('onMyLocationClick', {});
          },
          () => {
            throw new Error('Geolocation not supported on web browser.');
          },
        );
      } else {
        throw new Error('Geolocation not supported on web browser.');
      }
    }
  }
  async setPadding(_args: PaddingArgs): Promise<void> {
    const bounds = this.maps[_args.id].map.getBounds();

    if (bounds !== undefined) {
      this.maps[_args.id].map.fitBounds(bounds, _args.padding);
    }
  }

  async getMapBounds(_args: { id: string }): Promise<LatLngBounds> {
    const bounds = this.maps[_args.id].map.getBounds();

    if (!bounds) {
      throw new Error('Google Map Bounds could not be found.');
    }

    return new LatLngBounds({
      southwest: {
        lat: bounds.getSouthWest().lat(),
        lng: bounds.getSouthWest().lng(),
      },
      center: {
        lat: bounds.getCenter().lat(),
        lng: bounds.getCenter().lng(),
      },
      northeast: {
        lat: bounds.getNorthEast().lat(),
        lng: bounds.getNorthEast().lng(),
      },
    });
  }

  async fitBounds(_args: FitBoundsArgs): Promise<void> {
    const map = this.maps[_args.id].map;
    const bounds = this.getLatLngBounds(_args.bounds);
    map.fitBounds(bounds, _args.padding);
  }

  async addMarkers(_args: AddMarkersArgs): Promise<{ ids: string[] }> {
    const markerIds: string[] = [];
    const map = this.maps[_args.id];

    for (const markerArgs of _args.markers) {
      const markerOpts = this.buildMarkerOpts(markerArgs, map.map);
      const marker = new google.maps.Marker(markerOpts);

      const id = '' + this.currMarkerId;

      map.markers[id] = marker;
      this.setMarkerListeners(_args.id, id, marker);

      markerIds.push(id);
      this.currMarkerId++;
    }

    return { ids: markerIds };
  }

  async addTileLayer(_args: AddTileOverlayArgs): Promise<{ id: string }>  {
    const map = this.maps[_args.id].map;
    const maxZoom = (_args.tileLayer.maxZoom) ? _args.tileLayer.maxZoom : 0;
    // const currentZoom = map.getZoom() || 0;
    // console.warn(currentZoom, maxZoom);
    const tileLayer = new google.maps.ImageMapType({
      getTileUrl: (coord, zoom) => {
        if(zoom > maxZoom) return null;
        const url = _args.tileLayer.tileUrl
          .replace('{x}', coord.x.toString())
          .replace('{y}', coord.y.toString())
          .replace('{zoom}', zoom.toString());
        return url;
      },
      tileSize: new google.maps.Size(256, 256),
      maxZoom: maxZoom,
    });
    if (this.currentTileLayer) {
      this.currentTileLayer = tileLayer;
    }
    // if (this.maps[_args.id].tileLayer) { // Remove previous overlay
    //   map.overlayMapTypes.removeAt(0);
    // }
    if(_args.tileLayer.opacity) tileLayer.setOpacity(_args.tileLayer.opacity);
    // Draw Tiles
    map.overlayMapTypes.push(tileLayer);
    // this.maps[_args.id].tileLayer = tileLayer;
    /*
    if (_args.tileLayer?.debug) { // Optionally, you can set debug mode if needed
      map.addListener('mousemove', function (event: any) {
        console.log('Mouse Coordinates: ', event.latLng.toString());
      });
    }
    if (!_args.tileLayer?.visible) { // Set visibility based on the 'visible' property
      map.overlayMapTypes.pop(); // Remove the last overlay (customMapOverlay) from the stack
    }
    */
    if (_args.tileLayer?.zIndex !== undefined) { // Set zIndex based on the 'zIndex' property
      // Move the customMapOverlay to the specified index in the overlay stack
      map.overlayMapTypes.setAt(
        map.overlayMapTypes.getLength() - 1,
        tileLayer,
      );
    }
    const id = '' + this.currTileId;
    this.maps[_args.id].tiles[id] = tileLayer;
    this.currTileId++;
    return { id: id };
  }

  async removeTileLayer(_args: RemoveTileLayerArgs): Promise<void> {
    if (this.maps[_args.id].tiles[_args.tileId]) {
      this.maps[_args.id].tiles[_args.tileId].setOpacity(0);
      delete this.maps[_args.id].tiles[_args.tileId];
    }
    // if (this.maps[_args.id].tileLayer) {
    //   this.maps[_args.id].map.overlayMapTypes.removeAt(0);
    //   delete this.maps[_args.id].tileLayer;
    // }
  }

  async removeAllTileLayers(_args: RemoveTileLayerArgs): Promise<void> {
    Object.keys(this.maps[_args.id].tiles).forEach(tileId => {
      this.maps[_args.id].tiles[tileId]?.setOpacity(0);
      delete this.maps[_args.id].tiles[tileId];
    });
    this.currentTileLayer = null; // Reset the currentTileLayer
  }

  async setTileLayerOpacity(_args: SetTileLayerOpacityArgs): Promise<void> {
    const map = this.maps[_args.id];
    if (map && map.tileLayer) {
      const clampedOpacity = Math.max(0, Math.min(1, _args.opacity)); // Clamp opacity between 0 and 1
      map.tileLayer.setOpacity(clampedOpacity); // Assuming your tileLayer object has a setOpacity method
    } else {
      console.error('No tile layer found for the given map ID');
    }
  }

  async addMarker(_args: AddMarkerArgs): Promise<{ id: string }> {
    const markerOpts = this.buildMarkerOpts(
      _args.marker,
      this.maps[_args.id].map,
    );

    const marker = new google.maps.Marker(markerOpts);

    const id = '' + this.currMarkerId;

    this.maps[_args.id].markers[id] = marker;
    this.setMarkerListeners(_args.id, id, marker);

    this.currMarkerId++;

    return { id: id };
  }

  async removeMarkers(_args: RemoveMarkersArgs): Promise<void> {
    const map = this.maps[_args.id];

    for (const id of _args.markerIds) {
      map.markers[id].setMap(null);
      delete map.markers[id];
    }
  }

  async removeMarker(_args: RemoveMarkerArgs): Promise<void> {
    this.maps[_args.id].markers[_args.markerId].setMap(null);
    delete this.maps[_args.id].markers[_args.markerId];
  }

  async updateMapOptions(_args: UpdateMapOptionArgs): Promise<void> {
    const map = this.maps[_args.id];
    if (!map) {
      throw new Error('Map not found');
    }

    const googleMapOptions: google.maps.MapOptions = {};
    if (_args.options.zoom !== undefined) {
      googleMapOptions.zoom = _args.options.zoom;
    }

    if (_args.options.center) {
      googleMapOptions.center = _args.options.center;
    }

    if (_args.options.styles) {
      googleMapOptions.styles = _args.options.styles;
    }

    if (_args.options.disableDefaultUI !== undefined) {
      googleMapOptions.disableDefaultUI = _args.options.disableDefaultUI;
      googleMapOptions.streetViewControl = false; // Force no streetViewControl
    }

    map.map.setOptions(googleMapOptions);
  }

  // async updateMapOptions(options: UpdateMapOptions): Promise<void> {
  //   const map = this.maps[options.id];
  //   if (!map) {
  //     throw new Error('Map not found');
  //   }

  //   const cameraUpdate: CameraUpdate = {};
  //   if (options.zoom !== undefined) {
  //     cameraUpdate.zoom = options.zoom;
  //   }

  //   if (options.center) {
  //     cameraUpdate.target = options.center;
  //   }

  //   await map.moveCamera(cameraUpdate);
  // }

  async addGroundOverlay(_args: AddGroundOverlayArgs): Promise<{ id: string }> {
    // Remove all existing ground overlays
    // Object.keys(this.maps[_args.id].overlays).forEach(overlayId => {
    //   this.maps[_args.id].overlays[overlayId].setMap(null);
    //   delete this.maps[_args.id].overlays[overlayId];
    // });

    const bounds = new google.maps.LatLngBounds(
      new google.maps.LatLng(
        _args.overlay.bounds.southwest.lat,
        _args.overlay.bounds.southwest.lng
      ),
      new google.maps.LatLng(
        _args.overlay.bounds.northeast.lat,
        _args.overlay.bounds.northeast.lng
      )
    );

    const overlayOpts = this.buildGroundOverlay(
      _args.overlay,
      this.maps[_args.id].map
    );

    const overlay = new google.maps.GroundOverlay(
      _args.overlay.imageUrl,
      bounds,
      overlayOpts
    );

    overlay.setMap(this.maps[_args.id].map);
    // this.currentOverlay = overlay;

    const id = '' + this.currGroundOverlayId;

    this.maps[_args.id].overlays[id] = overlay;
    // You can add event listeners here if needed, similar to setMarkerListeners for markers.

    this.currGroundOverlayId++;

    return { id: id };
  }

  async addOrUpdateGroundOverlay(_args: AddGroundOverlayArgs): Promise<{ id: string }> {
    const bounds = new google.maps.LatLngBounds(
      new google.maps.LatLng(
        _args.overlay.bounds.southwest.lat,
        _args.overlay.bounds.southwest.lng
      ),
      new google.maps.LatLng(
        _args.overlay.bounds.northeast.lat,
        _args.overlay.bounds.northeast.lng
      )
    );

    const overlayOpts = this.buildGroundOverlay(
      _args.overlay,
      this.maps[_args.id].map
    );

    if (this.currentOverlay) {
      this.currentOverlay.setMap(null);
    }

    const overlay = new google.maps.GroundOverlay(
      _args.overlay.imageUrl,
      bounds,
      overlayOpts
    );

    overlay.setMap(this.maps[_args.id].map);
    this.currentOverlay = overlay;

    const id = '' + this.currGroundOverlayId;

    this.maps[_args.id].overlays[id] = overlay;
    this.currGroundOverlayId++;

    return { id: id };
  }

  async addMultipleGroundOverlays(_args: AddMultipleGroundOverlayArgs): Promise<{ id: string }> {
    let firstId: string = '';

    // Loop through the arrays of image URLs and bounds to add each overlay
    for (let i = 0; i < _args.overlays.imageUrl.length; i++) {
      // Create a single GroundOverlay object by restructuring the data
      const singleOverlay: GroundOverlay = {
        imageUrl: _args.overlays.imageUrl[i],
        bounds: {
          southwest: _args.overlays.bounds[i].southwest,
          northeast: _args.overlays.bounds[i].northeast,
        },
        opacity: _args.overlays.opacity,
      };

      // Now we can call buildGroundOverlay with a properly structured GroundOverlay object
      const bounds = new google.maps.LatLngBounds(
        new google.maps.LatLng(
          singleOverlay.bounds.southwest.lat,
          singleOverlay.bounds.southwest.lng
        ),
        new google.maps.LatLng(
          singleOverlay.bounds.northeast.lat,
          singleOverlay.bounds.northeast.lng
        )
      );

      const overlayOpts = this.buildGroundOverlay(singleOverlay, this.maps[_args.id].map);

      const overlay = new google.maps.GroundOverlay(
        singleOverlay.imageUrl,
        bounds,
        overlayOpts
      );

      overlay.setMap(this.maps[_args.id].map);

      const id = '' + this.currGroundOverlayId;
      if (i === 0) {
        firstId = id; // Store the first ID
      }

      this.maps[_args.id].overlays[id] = overlay;
      this.currGroundOverlayId++;
    }

    return { id: firstId }; // Return only the first ID
  }

  async setOverlayOpacity(_args: SetOverlayOpacityArgs): Promise<void> {
    if (this.currentOverlay) {
      this.currentOverlay.setOpacity(_args.opacity);
    } else {
      console.warn('No existing overlay to update opacity');
    }
  }

  async removeGroundOverlay(_args: RemoveGroundOverlayArgs): Promise<void> {
    this.maps[_args.id].overlays[_args.overlayId].setMap(null);
    delete this.maps[_args.id].overlays[_args.overlayId];
  }

  async removeAllGroundOverlays(_args: RemoveAllGroundOverlaysArgs): Promise<void> {
    Object.keys(this.maps[_args.id].overlays).forEach(overlayId => {
      this.maps[_args.id].overlays[overlayId].setMap(null);
      delete this.maps[_args.id].overlays[overlayId];
    });
    // Reset the currentOverlay
    this.currentOverlay = null;
  }

  async setCurrentOverlayImage(_args: SetCurrentOverlayImageArgs): Promise<void> {
    if (!this.currentOverlay) {
      throw new Error('No overlay has been added to update its image.');
    }

    // Remove the current overlay
    this.currentOverlay.setMap(null);

    // Create a new overlay with the updated image URL
    const newOverlay = new google.maps.GroundOverlay(
      _args.imageUrl,
      this.currentOverlay.getBounds(),
      { opacity: _args.opacity }
    );

    // Add the new overlay to the map
    newOverlay.setMap(this.maps[_args.id].map);

    // Update the currentOverlay reference
    this.currentOverlay = newOverlay;
  }

  private buildGroundOverlay(
    overlay: GroundOverlay,
    map: google.maps.Map,
  ): google.maps.GroundOverlayOptions {
    const opts: google.maps.GroundOverlayOptions = {
      map: map,
      opacity: overlay.opacity,
      clickable: false,
    };

    return opts;
  }

  async addPolygons(args: AddPolygonsArgs): Promise<{ ids: string[] }> {
    const polygonIds: string[] = [];
    const map = this.maps[args.id];

    for (const polygonArgs of args.polygons) {
      const polygon = new google.maps.Polygon(polygonArgs);
      polygon.setMap(map.map);

      const id = '' + this.currPolygonId;
      this.maps[args.id].polygons[id] = polygon;
      this.setPolygonListeners(args.id, id, polygon);

      polygonIds.push(id);
      this.currPolygonId++;
    }

    return { ids: polygonIds };
  }

  async removePolygons(args: RemovePolygonsArgs): Promise<void> {
    const map = this.maps[args.id];

    for (const id of args.polygonIds) {
      map.polygons[id].setMap(null);
      delete map.polygons[id];
    }
  }

  async addCircles(args: AddCirclesArgs): Promise<{ ids: string[] }> {
    const circleIds: string[] = [];
    const map = this.maps[args.id];

    for (const circleArgs of args.circles) {
      const circle = new google.maps.Circle(circleArgs);
      circle.setMap(map.map);

      const id = '' + this.currCircleId;
      this.maps[args.id].circles[id] = circle;
      this.setCircleListeners(args.id, id, circle);

      circleIds.push(id);
      this.currCircleId++;
    }

    return { ids: circleIds };
  }

  async removeCircles(args: RemoveCirclesArgs): Promise<void> {
    const map = this.maps[args.id];

    for (const id of args.circleIds) {
      map.circles[id].setMap(null);
      delete map.circles[id];
    }
  }

  async addPolylines(args: AddPolylinesArgs): Promise<{ ids: string[] }> {
    const lineIds: string[] = [];
    const map = this.maps[args.id];

    for (const polylineArgs of args.polylines) {
      const polyline = new google.maps.Polyline(polylineArgs);
      polyline.set('tag', polylineArgs.tag);
      polyline.setMap(map.map);

      const id = '' + this.currPolylineId;
      this.maps[args.id].polylines[id] = polyline;
      this.setPolylineListeners(args.id, id, polyline);

      lineIds.push(id);
      this.currPolylineId++;
    }

    return {
      ids: lineIds,
    };
  }

  async removePolylines(args: RemovePolylinesArgs): Promise<void> {
    const map = this.maps[args.id];

    for (const id of args.polylineIds) {
      map.polylines[id].setMap(null);
      delete map.polylines[id];
    }
  }

  async enableClustering(_args: EnableClusteringArgs): Promise<void> {
    const markers: google.maps.Marker[] = [];

    for (const id in this.maps[_args.id].markers) {
      markers.push(this.maps[_args.id].markers[id]);
    }

    this.maps[_args.id].markerClusterer = new MarkerClusterer({
      map: this.maps[_args.id].map,
      markers: markers,
      algorithm: new SuperClusterAlgorithm({
        minPoints: _args.minClusterSize ?? 4,
      }),
      onClusterClick: this.onClusterClickHandler,
    });
  }

  async disableClustering(_args: { id: string }): Promise<void> {
    this.maps[_args.id].markerClusterer?.setMap(null);
    this.maps[_args.id].markerClusterer = undefined;
  }

  async onScroll(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async onResize(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async onDisplay(): Promise<void> {
    throw new Error('Method not supported on web.');
  }

  async create(_args: CreateMapArgs): Promise<void> {
    console.log(`Create map: ${_args.id}`);
    await this.importGoogleLib(_args.apiKey, _args.region, _args.language);

    this.maps[_args.id] = {
      map: new window.google.maps.Map(_args.element, { ..._args.config }),
      element: _args.element,
      markers: {},
      overlays: {},
      polygons: {},
      tiles: {},
      circles: {},
      polylines: {},
      tileLayer: undefined, // Initialize with undefined
    };
    this.setMapListeners(_args.id);
  }

  async destroy(_args: DestroyMapArgs): Promise<void> {
    console.log(`Destroy map: ${_args.id}`);
    const mapItem = this.maps[_args.id];
    mapItem.element.innerHTML = '';
    mapItem.map.unbindAll();
    delete this.maps[_args.id];
  }

  async mapBoundsContains(
    _args: MapBoundsContainsArgs,
  ): Promise<{ contains: boolean }> {
    const bounds = this.getLatLngBounds(_args.bounds);
    const point = new google.maps.LatLng(_args.point.lat, _args.point.lng);
    return { contains: bounds.contains(point) };
  }

  async mapBoundsExtend(
    _args: MapBoundsExtendArgs,
  ): Promise<{ bounds: LatLngBounds }> {
    const bounds = this.getLatLngBounds(_args.bounds);
    const point = new google.maps.LatLng(_args.point.lat, _args.point.lng);
    bounds.extend(point);
    const result = new LatLngBounds({
      southwest: {
        lat: bounds.getSouthWest().lat(),
        lng: bounds.getSouthWest().lng(),
      },
      center: {
        lat: bounds.getCenter().lat(),
        lng: bounds.getCenter().lng(),
      },
      northeast: {
        lat: bounds.getNorthEast().lat(),
        lng: bounds.getNorthEast().lng(),
      },
    });
    return { bounds: result };
  }

  private getLatLngBounds(_args: LatLngBounds): google.maps.LatLngBounds {
    return new google.maps.LatLngBounds(
      new google.maps.LatLng(_args.southwest.lat, _args.southwest.lng),
      new google.maps.LatLng(_args.northeast.lat, _args.northeast.lng),
    );
  }

  async setCircleListeners(
    mapId: string,
    circleId: string,
    circle: google.maps.Circle,
  ): Promise<void> {
    circle.addListener('click', () => {
      this.notifyListeners('onCircleClick', {
        mapId: mapId,
        circleId: circleId,
        tag: circle.get('tag'),
      });
    });
  }

  async setPolygonListeners(
    mapId: string,
    polygonId: string,
    polygon: google.maps.Polygon,
  ): Promise<void> {
    polygon.addListener('click', () => {
      this.notifyListeners('onPolygonClick', {
        mapId: mapId,
        polygonId: polygonId,
        tag: polygon.get('tag'),
      });
    });
  }

  async setPolylineListeners(
    mapId: string,
    polylineId: string,
    polyline: google.maps.Polyline,
  ): Promise<void> {
    polyline.addListener('click', () => {
      this.notifyListeners('onPolylineClick', {
        mapId: mapId,
        polylineId: polylineId,
        tag: polyline.get('tag'),
      });
    });
  }

  async setMarkerListeners(
    mapId: string,
    markerId: string,
    marker: google.maps.Marker,
  ): Promise<void> {
    marker.addListener('click', () => {
      this.notifyListeners('onMarkerClick', {
        mapId: mapId,
        markerId: markerId,
        latitude: marker.getPosition()?.lat(),
        longitude: marker.getPosition()?.lng(),
        title: marker.getTitle(),
        snippet: '',
      });
    });

    marker.addListener('dragstart', () => {
      this.notifyListeners('onMarkerDragStart', {
        mapId: mapId,
        markerId: markerId,
        latitude: marker.getPosition()?.lat(),
        longitude: marker.getPosition()?.lng(),
        title: marker.getTitle(),
        snippet: '',
      });
    });

    marker.addListener('drag', () => {
      this.notifyListeners('onMarkerDrag', {
        mapId: mapId,
        markerId: markerId,
        latitude: marker.getPosition()?.lat(),
        longitude: marker.getPosition()?.lng(),
        title: marker.getTitle(),
        snippet: '',
      });
    });

    marker.addListener('dragend', () => {
      this.notifyListeners('onMarkerDragEnd', {
        mapId: mapId,
        markerId: markerId,
        latitude: marker.getPosition()?.lat(),
        longitude: marker.getPosition()?.lng(),
        title: marker.getTitle(),
        snippet: '',
      });
    });
  }

  async setMapListeners(mapId: string): Promise<void> {
    const map = this.maps[mapId].map;

    map.addListener('idle', async () => {
      const bounds = await this.getMapBounds({ id: mapId });
      this.notifyListeners('onCameraIdle', {
        mapId: mapId,
        bearing: map.getHeading(),
        bounds: bounds,
        latitude: map.getCenter()?.lat(),
        longitude: map.getCenter()?.lng(),
        tilt: map.getTilt(),
        zoom: map.getZoom(),
      });
    });

    map.addListener('center_changed', () => {
      this.notifyListeners('onCameraMoveStarted', {
        mapId: mapId,
        isGesture: true,
      });
    });

    map.addListener('bounds_changed', async () => {
      const bounds = await this.getMapBounds({ id: mapId });
      this.notifyListeners('onBoundsChanged', {
        mapId: mapId,
        bearing: map.getHeading(),
        bounds: bounds,
        latitude: map.getCenter()?.lat(),
        longitude: map.getCenter()?.lng(),
        tilt: map.getTilt(),
        zoom: map.getZoom(),
      });
    });

    map.addListener(
      'click',
      (e: google.maps.MapMouseEvent | google.maps.IconMouseEvent) => {
        this.notifyListeners('onMapClick', {
          mapId: mapId,
          latitude: e.latLng?.lat(),
          longitude: e.latLng?.lng(),
        });
      },
    );

    this.notifyListeners('onMapReady', {
      mapId: mapId,
    });
  }

  private buildMarkerOpts(
    marker: Marker,
    map: google.maps.Map,
  ): google.maps.MarkerOptions {
    let iconImage: google.maps.Icon | undefined = undefined;
    if (marker.iconUrl) {
      iconImage = {
        url: marker.iconUrl,
        scaledSize: marker.iconSize
          ? new google.maps.Size(marker.iconSize.width, marker.iconSize.height)
          : null,
        anchor: marker.iconAnchor
          ? new google.maps.Point(marker.iconAnchor.x, marker.iconAnchor.y)
          : null,
        origin: marker.iconOrigin
          ? new google.maps.Point(marker.iconOrigin.x, marker.iconOrigin.y)
          : null,
      };
    }

    const opts: google.maps.MarkerOptions = {
      position: marker.coordinate,
      map: map,
      opacity: marker.opacity,
      title: marker.title,
      icon: iconImage,
      draggable: marker.draggable,
      zIndex: marker.zIndex ?? 0,
    };

    return opts;
  }
}
