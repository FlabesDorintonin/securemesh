package dev.securemesh.commander.core.map

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.max

/** Local-first geographic renderer. Basemap files are installed by [OfflineMapManager]. */
object MapLibreMeshMapProvider : MeshMapProvider {
    override val providerName: String = "Офлайн-карта"
    override val offlineCapable: Boolean = true

    private const val NODE_SOURCE = "securemesh-nodes"
    private const val NODE_LAYER = "securemesh-node-layer"
    private const val STALE_SOURCE = "securemesh-stale-nodes"
    private const val STALE_LAYER = "securemesh-stale-node-layer"
    private const val SOS_SOURCE = "securemesh-sos"
    private const val SOS_LAYER = "securemesh-sos-layer"
    private const val SELECTED_SOURCE = "securemesh-selected"
    private const val SELECTED_LAYER = "securemesh-selected-layer"
    private const val TRACK_SOURCE = "securemesh-tracks"
    private const val TRACK_LAYER = "securemesh-track-layer"

    @Composable
    override fun Render(
        state: MapRenderState,
        modifier: Modifier,
        onPointSelected: (String) -> Unit,
    ) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val latestSelect by rememberUpdatedState(onPointSelected)
        val latestPoints by rememberUpdatedState(state.points)
        val tapRadiusPx = 52f * context.resources.displayMetrics.density
        val mapView = remember { MapView(context).apply { onCreate(null) } }
        var map by remember { mutableStateOf<MapLibreMap?>(null) }
        var styleReady by remember { mutableStateOf(false) }
        var loadedBasemapUri by remember { mutableStateOf<String?>(null) }

        DisposableEffect(mapView, lifecycle) {
            var started = false
            var resumed = false
            fun startIfNeeded() { if (!started) { mapView.onStart(); started = true } }
            fun resumeIfNeeded() { startIfNeeded(); if (!resumed) { mapView.onResume(); resumed = true } }
            fun pauseIfNeeded() { if (resumed) { mapView.onPause(); resumed = false } }
            fun stopIfNeeded() { pauseIfNeeded(); if (started) { mapView.onStop(); started = false } }

            when {
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> resumeIfNeeded()
                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> startIfNeeded()
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> startIfNeeded()
                    Lifecycle.Event.ON_RESUME -> resumeIfNeeded()
                    Lifecycle.Event.ON_PAUSE -> pauseIfNeeded()
                    Lifecycle.Event.ON_STOP -> stopIfNeeded()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                stopIfNeeded()
                mapView.onDestroy()
            }
        }

        LaunchedEffect(mapView) {
            mapView.getMapAsync { readyMap ->
                map = readyMap
                readyMap.uiSettings.isRotateGesturesEnabled = true
                readyMap.uiSettings.isTiltGesturesEnabled = true
                readyMap.uiSettings.isCompassEnabled = true
                readyMap.addOnMapClickListener { coordinate ->
                    val tap = readyMap.projection.toScreenLocation(coordinate)
                    val radiusSquared = tapRadiusPx * tapRadiusPx
                    val nearest = latestPoints.asSequence()
                        .filter { validMapCoordinate(it.latitude, it.longitude) }
                        .map { point ->
                            val projected = readyMap.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
                            val dx = projected.x - tap.x
                            val dy = projected.y - tap.y
                            point to (dx * dx + dy * dy)
                        }
                        .filter { (_, distanceSquared) -> distanceSquared <= radiusSquared }
                        .minByOrNull { (_, distanceSquared) -> distanceSquared }
                        ?.first
                    nearest?.let { latestSelect(it.id) }
                    nearest != null
                }
            }
        }

        LaunchedEffect(map, state.basemapUri) {
            val currentMap = map ?: return@LaunchedEffect
            val requested = state.basemapUri
            if (styleReady && loadedBasemapUri == requested) return@LaunchedEffect
            styleReady = false
            loadedBasemapUri = requested
            val style = if (requested == null) {
                Style.Builder().fromJson(DEFAULT_TACTICAL_STYLE)
            } else {
                Style.Builder().fromJson(localVectorStyle(requested))
            }
            currentMap.setStyle(style) { loaded ->
                if (loadedBasemapUri != requested) return@setStyle
                installOverlayLayers(loaded)
                updateOverlays(loaded, state)
                styleReady = true
                fitAvailable(currentMap, state.points, state.basemapBounds, state.threeDimensional)
            }
        }

        LaunchedEffect(styleReady, state.points, state.tracks, state.selectedPointId) {
            val currentMap = map ?: return@LaunchedEffect
            val style = currentMap.style ?: return@LaunchedEffect
            if (styleReady) updateOverlays(style, state)
        }

        LaunchedEffect(styleReady, state.fitAllRequest, state.points, state.basemapBounds, state.threeDimensional) {
            val currentMap = map ?: return@LaunchedEffect
            if (styleReady) fitAvailable(currentMap, state.points, state.basemapBounds, state.threeDimensional)
        }

        LaunchedEffect(styleReady, state.followSelected, state.selectedPointId, state.points, state.threeDimensional) {
            if (!styleReady || !state.followSelected) return@LaunchedEffect
            val currentMap = map ?: return@LaunchedEffect
            val selected = state.points.firstOrNull { it.id == state.selectedPointId } ?: return@LaunchedEffect
            currentMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(selected.latitude, selected.longitude))
                        .zoom(max(currentMap.cameraPosition.zoom, 15.0))
                        .tilt(if (state.threeDimensional) 55.0 else 0.0)
                        .bearing(currentMap.cameraPosition.bearing)
                        .build()
                ),
                600,
            )
        }

        LaunchedEffect(styleReady, state.threeDimensional) {
            val currentMap = map ?: return@LaunchedEffect
            if (!styleReady) return@LaunchedEffect
            currentMap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(currentMap.cameraPosition)
                        .tilt(if (state.threeDimensional) 55.0 else 0.0)
                        .build()
                ),
                420,
            )
        }

        AndroidView(factory = { mapView }, modifier = modifier)
    }

    private fun installOverlayLayers(style: Style) {
        if (style.getSource(NODE_SOURCE) == null) style.addSource(GeoJsonSource(NODE_SOURCE, emptyFeatures()))
        if (style.getSource(STALE_SOURCE) == null) style.addSource(GeoJsonSource(STALE_SOURCE, emptyFeatures()))
        if (style.getSource(SOS_SOURCE) == null) style.addSource(GeoJsonSource(SOS_SOURCE, emptyFeatures()))
        if (style.getSource(SELECTED_SOURCE) == null) style.addSource(GeoJsonSource(SELECTED_SOURCE, emptyFeatures()))
        if (style.getSource(TRACK_SOURCE) == null) style.addSource(GeoJsonSource(TRACK_SOURCE, emptyFeatures()))

        if (style.getLayer(TRACK_LAYER) == null) style.addLayer(LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(lineColor("#32D7FF"), lineWidth(3f), lineOpacity(.60f)))
        if (style.getLayer(NODE_LAYER) == null) style.addLayer(CircleLayer(NODE_LAYER, NODE_SOURCE).withProperties(circleRadius(9f), circleColor("#45F0A5"), circleStrokeColor("#071820"), circleStrokeWidth(3f), circleOpacity(.96f)))
        if (style.getLayer(STALE_LAYER) == null) style.addLayer(CircleLayer(STALE_LAYER, STALE_SOURCE).withProperties(circleRadius(9f), circleColor("#F2B84B"), circleStrokeColor("#071820"), circleStrokeWidth(3f), circleOpacity(.82f)))
        if (style.getLayer(SOS_LAYER) == null) style.addLayer(CircleLayer(SOS_LAYER, SOS_SOURCE).withProperties(circleRadius(14f), circleColor("#FF4D67"), circleStrokeColor("#FFE8EC"), circleStrokeWidth(3f), circleOpacity(.96f)))
        if (style.getLayer(SELECTED_LAYER) == null) style.addLayer(CircleLayer(SELECTED_LAYER, SELECTED_SOURCE).withProperties(circleRadius(18f), circleColor("#32D7FF"), circleOpacity(.18f), circleStrokeColor("#32D7FF"), circleStrokeWidth(2.5f)))
    }

    private fun updateOverlays(style: Style, state: MapRenderState) {
        val fresh = state.points.filter { it.kind != MapPointKind.SOS && it.freshFix }.map(::pointFeature)
        val older = state.points.filter { it.kind != MapPointKind.SOS && !it.freshFix }.map(::pointFeature)
        val sos = state.points.filter { it.kind == MapPointKind.SOS }.map(::pointFeature)
        val selected = state.points.firstOrNull { it.id == state.selectedPointId }?.let(::pointFeature)
        style.getSourceAs<GeoJsonSource>(NODE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(fresh))
        style.getSourceAs<GeoJsonSource>(STALE_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(older))
        style.getSourceAs<GeoJsonSource>(SOS_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(sos))
        style.getSourceAs<GeoJsonSource>(SELECTED_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(selected?.let(::listOf).orEmpty()))

        val trackFeatures = state.tracks.mapNotNull { (nodeId, coords) ->
            val valid = coords.filter { validMapCoordinate(it.latitude, it.longitude) }
            if (valid.size < 2) null else Feature.fromGeometry(LineString.fromLngLats(valid.map { Point.fromLngLat(it.longitude, it.latitude) })).apply { addStringProperty("id", nodeId) }
        }
        style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(trackFeatures))
    }

    private fun pointFeature(point: MapPoint): Feature = Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
        addStringProperty("id", point.id)
        addStringProperty("label", point.label)
        addStringProperty("kind", point.kind.name)
        addBooleanProperty("online", point.online == true)
    }

    private fun fitAvailable(map: MapLibreMap, points: List<MapPoint>, basemap: OfflineMapBounds?, threeD: Boolean) {
        val valid = points.filter { validMapCoordinate(it.latitude, it.longitude) }
        if (valid.size == 1) {
            val p = valid.first()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(p.latitude, p.longitude)).zoom(15.0).tilt(if (threeD) 55.0 else 0.0).build()), 650)
            return
        }
        val bounds = when {
            valid.size >= 2 -> LatLngBounds.Builder().apply { valid.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
            basemap != null -> LatLngBounds.Builder()
                .include(LatLng(basemap.minLatitude, basemap.minLongitude))
                .include(LatLng(basemap.maxLatitude, basemap.maxLongitude))
                .build()
            else -> null
        } ?: return
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 72), 700)
        if (threeD) map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(map.cameraPosition).tilt(45.0).build()), 360)
    }

    private fun validMapCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun emptyFeatures(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())

    /**
     * A deliberately self-contained style: no remote glyphs/sprites, so an installed pack stays
     * usable with the phone fully offline. It supports the common Protomaps and OpenMapTiles
     * layer names; missing source-layers are simply ignored by MapLibre.
     */
    private fun localVectorStyle(pmtilesUri: String): String {
        val safeUri = pmtilesUri.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
        {
          "version":8,
          "name":"SecureMesh Offline",
          "sources":{"basemap":{"type":"vector","url":"$safeUri","attribution":"© OpenStreetMap contributors"}},
          "layers":[
            {"id":"background","type":"background","paint":{"background-color":"#07131D"}},
            {"id":"natural","type":"fill","source":"basemap","source-layer":"natural","paint":{"fill-color":"#102820","fill-opacity":0.72}},
            {"id":"landcover","type":"fill","source":"basemap","source-layer":"landcover","paint":{"fill-color":"#102820","fill-opacity":0.64}},
            {"id":"landuse","type":"fill","source":"basemap","source-layer":"landuse","paint":{"fill-color":"#172B2B","fill-opacity":0.55}},
            {"id":"water","type":"fill","source":"basemap","source-layer":"water","paint":{"fill-color":"#123A55","fill-opacity":0.95}},
            {"id":"boundary-a","type":"line","source":"basemap","source-layer":"boundaries","paint":{"line-color":"#5D7D8C","line-width":1.0,"line-opacity":0.45}},
            {"id":"boundary-b","type":"line","source":"basemap","source-layer":"boundary","paint":{"line-color":"#5D7D8C","line-width":1.0,"line-opacity":0.45}},
            {"id":"roads-a","type":"line","source":"basemap","source-layer":"roads","paint":{"line-color":"#8FA4AC","line-width":["interpolate",["linear"],["zoom"],6,0.4,12,1.2,16,3.4],"line-opacity":0.78}},
            {"id":"roads-b","type":"line","source":"basemap","source-layer":"transportation","paint":{"line-color":"#8FA4AC","line-width":["interpolate",["linear"],["zoom"],6,0.4,12,1.2,16,3.4],"line-opacity":0.78}},
            {"id":"buildings-a","type":"fill-extrusion","source":"basemap","source-layer":"buildings","minzoom":13,"paint":{"fill-extrusion-color":"#324955","fill-extrusion-height":["coalesce",["to-number",["get","height"]],5],"fill-extrusion-base":["coalesce",["to-number",["get","min_height"]],0],"fill-extrusion-opacity":0.82}},
            {"id":"buildings-b","type":"fill-extrusion","source":"basemap","source-layer":"building","minzoom":13,"paint":{"fill-extrusion-color":"#324955","fill-extrusion-height":["coalesce",["to-number",["get","render_height"]],["to-number",["get","height"]],5],"fill-extrusion-base":["coalesce",["to-number",["get","render_min_height"]],0],"fill-extrusion-opacity":0.82}}
          ]
        }
        """.trimIndent()
    }

    private const val DEFAULT_TACTICAL_STYLE = """
        {"version":8,"name":"SecureMesh Empty","sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#07131D"}}]}
    """
}
