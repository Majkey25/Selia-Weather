package cz.majkey.pocasicesko.ui

import android.animation.ValueAnimator
import android.util.Log
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.BuildConfig
import cz.majkey.pocasicesko.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.Point
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeRadarScreen(
    location: CzechLocation,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    topInsetDp: Int = 0,
    bottomInsetDp: Int = 0,
    timezone: String? = null,
) {
    var opened by rememberSaveable { mutableStateOf(active) }
    LaunchedEffect(active) { if (active) opened = true }
    if (!opened) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val visible = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val colors = MaterialTheme.colorScheme
    val styleUrl = "https://tiles.openfreemap.org/styles/" + if (LocalWeatherAppearance.current.dark) "dark" else "positron"
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember(timezone) { runCatching { ZoneId.of(timezone ?: "UTC") }.getOrDefault(ZoneId.of("UTC")) }
    val timeFormat = remember(locale, zone) { DateTimeFormatter.ofPattern("EEE d MMM · HH:mm z", locale).withZone(zone) }
    var mode by rememberSaveable { mutableStateOf(NativeRadarMode.FORECAST) }
    var camera by remember(location.latitude, location.longitude) {
        mutableStateOf(CameraPosition.Builder().target(LatLng(location.latitude, location.longitude)).zoom(6.0).build())
    }
    var viewport by remember(location.latitude, location.longitude) {
        val latitude = location.latitude.coerceIn(-82.0, 82.0)
        mutableStateOf(NativeRadarBounds(latitude - 2, location.longitude - 3, latitude + 2, location.longitude + 3))
    }
    var snapshot by remember { mutableStateOf<NativeRadarSnapshot?>(null) }
    var frameIndex by rememberSaveable { mutableIntStateOf(0) }
    var displayedFrame by remember { mutableStateOf<NativeRadarFrame?>(null) }
    var loading by remember { mutableStateOf(true) }
    var frameLoading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var styleAttempt by remember { mutableIntStateOf(0) }
    var showDetails by remember { mutableStateOf(false) }
    var legend by remember { mutableStateOf<Bitmap?>(null) }
    var legendFailed by remember { mutableStateOf(false) }
    var ready by remember(styleUrl, styleAttempt) { mutableStateOf(false) }
    val provider = remember { NativeRadarData() }
    val renderer = remember(styleUrl, styleAttempt) {
        NativeRadarRenderer(MapView(context.also { MapLibre.getInstance(it) }).also { it.onCreate(null) }) {
            failed = true; playing = false
        }
    }
    val currentViewport by rememberUpdatedState(viewport)
    val currentLocation by rememberUpdatedState(location)
    val currentCamera by rememberUpdatedState(camera)

    DisposableEffect(renderer) {
        val styleError = MapView.OnDidFailLoadingMapListener { if (!renderer.closed) failed = true }
        renderer.view.addOnDidFailLoadingMapListener(styleError)
        onDispose { renderer.close() }
    }
    LaunchedEffect(renderer) { displayedFrame = null; playing = false }
    LaunchedEffect(renderer, active, lifecycleState) {
        renderer.synchronize(lifecycleState, active)
        if (!active || !lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) playing = false
    }
    LaunchedEffect(renderer, visible) {
        if (!visible || renderer.map != null) return@LaunchedEffect
        ready = false
        renderer.view.getMapAsync { map ->
            if (renderer.closed) return@getMapAsync
            renderer.map = map
            map.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false
                setAttributionMargins(8, 0, 0, 8)
            }
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                if (!renderer.closed) {
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(currentCamera))
                    style.addSource(GeoJsonSource("selected-place", Point.fromLngLat(currentLocation.longitude, currentLocation.latitude)))
                    style.addLayer(CircleLayer("selected-place", "selected-place").withProperties(
                        circleColor("#286EA6"), circleRadius(5f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
                    ready = true
                }
            }
        }
        delay(15_000)
        if (!ready && !renderer.closed) failed = true
    }
    DisposableEffect(renderer, ready) {
        val map = renderer.map
        val idle = MapLibreMap.OnCameraIdleListener {
            if (!renderer.closed && map != null) {
                camera = map.cameraPosition
                val bounds = map.projection.visibleRegion.latLngBounds
                viewport = NativeRadarBounds(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth,
                    if (bounds.longitudeEast > bounds.longitudeWest) bounds.longitudeEast else bounds.longitudeEast + 360)
            }
        }
        map?.addOnCameraIdleListener(idle)
        if (ready) idle.onCameraIdle()
        onDispose { map?.removeOnCameraIdleListener(idle) }
    }
    LaunchedEffect(location.latitude, location.longitude) {
        renderer.map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 6.0))
    }
    LaunchedEffect(location.latitude, location.longitude, ready, renderer) {
        if (ready && !renderer.closed) renderer.map?.style?.getSourceAs<GeoJsonSource>("selected-place")
            ?.setGeoJson(Point.fromLngLat(location.longitude, location.latitude))
    }
    LaunchedEffect(mode, ready, renderer) {
        if (ready) renderer.showCoverage(mode == NativeRadarMode.OBSERVED)
    }
    LaunchedEffect(mode, viewport, reload, visible) {
        if (!visible) return@LaunchedEffect
        delay(350)
        playing = false
        while (visible) {
            loading = true
            when (val result = provider.fetch(mode, viewport)) {
                is NativeRadarResult.Success -> {
                    val previous = snapshot?.takeIf { it.mode == mode }?.frames?.getOrNull(frameIndex)?.validTime
                    snapshot = result.snapshot
                    frameIndex = previous?.let { time -> result.snapshot.frames.indexOfFirst { it.validTime >= time }.takeIf { it >= 0 } }
                        ?: if (mode == NativeRadarMode.OBSERVED) result.snapshot.frames.lastIndex else 0
                }
                is NativeRadarResult.Failure -> {
                    if (BuildConfig.DEBUG) Log.w("NativeRadar", "${result.source}: ${result.reason}: ${result.message}")
                    snapshot = null; failed = true
                }
            }
            loading = false
            delay(60_000)
        }
    }
    val frames = snapshot?.takeIf { it.mode == mode }?.frames.orEmpty()
    val selected = frames.getOrNull(frameIndex)
    val visibleFrame = displayedFrame?.takeIf { (it.source == NativeRadarSource.RAINVIEWER) == (mode == NativeRadarMode.OBSERVED) }
    val visibleSource = visibleFrame?.source ?: snapshot?.takeIf { it.mode == mode }?.source
    LaunchedEffect(selected, viewport, ready, visible, reload, renderer) {
        if (!ready || !visible || selected == null) return@LaunchedEffect
        if (!playing) delay(120)
        frameLoading = true
        try {
            renderer.load(selected, currentViewport)
            coroutineContext.ensureActive()
            renderer.display(selected, currentViewport)
            displayedFrame = selected
            failed = false
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (BuildConfig.DEBUG) Log.w("NativeRadar", "Frame load failed for ${selected.source}", error)
            failed = true
            playing = false
        } finally { frameLoading = false }
    }
    LaunchedEffect(playing, frameLoading, frameIndex, visible, frames) {
        if (playing && visible && !frameLoading && frames.isNotEmpty()) {
            delay(900)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }
    LaunchedEffect(displayedFrame, selected, viewport, visible, renderer) {
        if (!visible || displayedFrame == null || displayedFrame != selected || frames.size < 2) return@LaunchedEffect
        val next = frames.getOrNull((frameIndex + 1) % frames.size) ?: return@LaunchedEffect
        try { renderer.load(next, viewport) } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
        }
    }
    LaunchedEffect(showDetails, visibleSource) {
        legend = null; legendFailed = false
        val source = visibleSource ?: return@LaunchedEffect
        if (showDetails && source != NativeRadarSource.RAINVIEWER) {
            try { legend = radarBitmap(NativeRadarUrls.legend(source)) } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                legendFailed = true
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().testTag("native-radar")) {
        val compact = maxHeight < 450.dp
        key(renderer) { AndroidView(factory = { renderer.view }, modifier = Modifier.fillMaxSize(), update = { view ->
            view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            view.visibility = if (active) android.view.View.VISIBLE else android.view.View.INVISIBLE
        }) }
        FilledTonalIconButton(onClick = {
            renderer.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 7.0))
        }, modifier = Modifier.align(Alignment.TopEnd).padding(top = (topInsetDp + 12).dp, end = 12.dp).size(48.dp)) {
            Icon(Icons.Rounded.MyLocation, stringResource(R.string.native_radar_recenter))
        }
        Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp,
            bottom = (bottomInsetDp + 28).dp).widthIn(max = 640.dp).fillMaxWidth()
            .heightIn(max = (maxHeight - (topInsetDp + bottomInsetDp + 84).dp).coerceAtLeast(120.dp)),
            shape = RoundedCornerShape(24.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    NativeRadarMode.entries.forEachIndexed { index, choice ->
                        SegmentedButton(selected = mode == choice, onClick = {
                            if (choice != mode) {
                                mode = choice; playing = false; frameIndex = 0; displayedFrame = null; renderer.clear()
                            }
                        }, shape = SegmentedButtonDefaults.itemShape(index, 2),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = colors.primary, activeContentColor = colors.onPrimary), icon = {
                            Icon(if (choice == NativeRadarMode.FORECAST) Icons.Rounded.Schedule else Icons.Rounded.Radar,
                                contentDescription = null, modifier = Modifier.size(18.dp))
                        }) { Text(stringResource(if (choice == NativeRadarMode.FORECAST) R.string.native_radar_forecast else R.string.native_radar_observed), maxLines = 2) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledIconButton(onClick = { playing = !playing }, enabled = ready && frames.size > 1 && !failed,
                        modifier = Modifier.size(48.dp).testTag("radar-play")) {
                        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (playing) R.string.native_radar_pause else R.string.native_radar_play))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(visibleFrame?.let { timeFormat.format(it.validTime) }
                            ?: stringResource(if (loading) R.string.native_radar_loading else R.string.native_radar_select_time),
                            modifier = Modifier.testTag("radar-frame-time"),
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(visibleSource?.let { it.modelName + if (it.accumulationHours > 0) " · mm / ${it.accumulationHours} h" else "" }
                            ?: stringResource(R.string.native_radar_precipitation),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (!failed && (loading || frameLoading || !ready)) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (failed) IconButton(onClick = { if (!ready) styleAttempt++ else reload++ }) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.radar_retry))
                    }
                    IconButton(onClick = { playing = false; showDetails = true }) {
                        Icon(Icons.Rounded.Info, stringResource(R.string.native_radar_key))
                    }
                }
                if (failed) Text(stringResource(R.string.native_radar_failed), style = MaterialTheme.typography.bodySmall,
                    color = colors.error)
                if (frames.size > 1) {
                    Slider(value = frameIndex.toFloat(), onValueChange = { playing = false; frameIndex = it.toInt().coerceIn(frames.indices) },
                        valueRange = 0f..frames.lastIndex.toFloat(), steps = (frames.size - 2).coerceAtLeast(0),
                        track = { SliderDefaults.Track(it, drawStopIndicator = null, drawTick = { _, _ -> }) },
                        modifier = Modifier.fillMaxWidth().testTag("radar-timeline"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(timeFormat.format(frames.first().validTime), Modifier.weight(1f).testTag("radar-range-start"), style = MaterialTheme.typography.labelSmall)
                        Text(timeFormat.format(frames.last().validTime), Modifier.weight(1f).testTag("radar-range-end"), style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End)
                    }
                }
                if (!compact) Text(stringResource(if (mode == NativeRadarMode.FORECAST) R.string.native_radar_model_note else R.string.native_radar_observed_note),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
    if (showDetails) ModalBottomSheet(onDismissRequest = { showDetails = false }) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.native_radar_key), style = MaterialTheme.typography.headlineSmall)
            Text(visibleSource?.providerName.orEmpty(), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (mode == NativeRadarMode.FORECAST) R.string.native_radar_model_note else R.string.native_radar_observed_note))
            legend?.let { Image(it.asImageBitmap(), stringResource(R.string.native_radar_key),
                Modifier.fillMaxWidth().height(320.dp).background(Color.White)) }
            if (mode == NativeRadarMode.FORECAST && legend == null) {
                if (legendFailed || visibleSource == null) Text(stringResource(R.string.native_radar_failed))
                else CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
    }
}

private suspend fun radarBitmap(url: String): Bitmap {
    val bytes = requestNativeRadarBytes(url, 4_000_000)
    return withContext(Dispatchers.Default) {
        ensureActive()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        check(options.outWidth in 1..1024 && options.outHeight in 1..1024)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Invalid radar image")
    }
}

internal fun radarImageBounds(bounds: NativeRadarBounds): List<NativeRadarBounds> {
    val west = ((bounds.west + 180) % 360 + 360) % 360 - 180
    val east = west + bounds.east - bounds.west
    return if (east <= 180) listOf(NativeRadarBounds(bounds.south, west, bounds.north, east)) else listOf(
        NativeRadarBounds(bounds.south, west, bounds.north, 180.0),
        NativeRadarBounds(bounds.south, -180.0, bounds.north, east - 360),
    )
}

private class NativeRadarRenderer(val view: MapView, onTileFailure: () -> Unit) {
    var map: MapLibreMap? = null
    var closed = false
    private var currentLayers = emptyList<String>()
    private var currentFrame: NativeRadarFrame? = null
    private var currentBounds: NativeRadarBounds? = null
    private data class TileFrame(val frame: NativeRadarFrame, val id: String, val bounds: NativeRadarBounds,
        var ready: Boolean = false)
    private var pendingTile: TileFrame? = null
    private var sequence = 0
    private var transition: ValueAnimator? = null
    private var started = false
    private var resumed = false
    private val loading = Mutex()
    private val tileErrors = MapView.OnTileActionListener { operation, _, _, _, _, _, source ->
        if (!closed && operation == TileOperation.Error && source in currentLayers) {
            currentBounds = null
            onTileFailure()
        }
    }
    init { view.addOnTileActionListener(tileErrors) }
    private val images = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 6
    }
    private fun imageUrl(frame: NativeRadarFrame, bounds: NativeRadarBounds) = NativeRadarUrls.wmsViewport(frame, bounds, 768, 768)

    suspend fun load(frame: NativeRadarFrame, bounds: NativeRadarBounds) = loading.withLock {
        check(!closed) { "Radar renderer is closed." }
        if (frame.source == NativeRadarSource.RAINVIEWER) {
            if (currentFrame == frame) {
                if (currentBounds != bounds) { awaitTiles(currentLayers.single()); currentBounds = bounds }
                return@withLock
            }
            val style = checkNotNull(map?.style)
            val tile = pendingTile?.takeIf { it.frame == frame && it.bounds == bounds } ?: run {
                removePendingTile()
                val id = "weather-${sequence++}"
                val tiles = TileSet("2.2.0", NativeRadarUrls.tileTemplate(frame)).apply {
                    maxZoom = 7f
                    attribution = "<a href=\"https://www.rainviewer.com/\">RainViewer</a>"
                }
                style.addSource(RasterSource(id, tiles, 512))
                addLayer(style, id)
                TileFrame(frame, id, bounds).also { pendingTile = it }
            }
            try {
                if (!tile.ready) { awaitTiles(tile.id); tile.ready = true }
            } catch (error: Exception) {
                if (pendingTile === tile) removePendingTile()
                throw error
            }
            return@withLock
        }
        for (part in radarImageBounds(bounds)) loadImage(frame, part)
    }

    private suspend fun awaitTiles(sourceId: String) {
        check(withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { continuation ->
                lateinit var rendered: MapView.OnDidFinishRenderingFrameListener
                lateinit var tileAction: MapView.OnTileActionListener
                fun removeListeners() {
                    view.removeOnDidFinishRenderingFrameListener(rendered)
                    view.removeOnTileActionListener(tileAction)
                }
                rendered = MapView.OnDidFinishRenderingFrameListener { fullyRendered, _, _ ->
                    if (fullyRendered && continuation.isActive) {
                        removeListeners()
                        continuation.resume(Unit)
                    }
                }
                tileAction = MapView.OnTileActionListener { operation, _, _, _, _, _, source ->
                    if (source == sourceId && operation == TileOperation.Error && continuation.isActive) {
                        removeListeners()
                        continuation.resumeWithException(java.io.IOException("Radar tile request failed."))
                    }
                }
                view.addOnDidFinishRenderingFrameListener(rendered)
                view.addOnTileActionListener(tileAction)
                map?.triggerRepaint()
                continuation.invokeOnCancellation { removeListeners() }
            }
            true
        } == true) { "Radar tiles did not finish loading." }
    }

    private fun addLayer(style: Style, id: String) {
        val layer = RasterLayer(id, id).withProperties(rasterOpacity(0.001f), rasterFadeDuration(0f))
        val label = style.layers.firstOrNull { it is SymbolLayer }?.id ?: "selected-place"
        style.addLayerBelow(layer, label)
    }

    private fun removePendingTile() {
        val id = pendingTile?.id ?: return
        if (!closed) { map?.style?.removeLayer(id); map?.style?.removeSource(id) }
        pendingTile = null
    }

    fun showCoverage(observed: Boolean) {
        val style = map?.style?.takeUnless { closed } ?: return
        if (!observed) {
            style.removeLayer("radar-coverage")
            style.removeSource("radar-coverage")
        } else if (style.getSource("radar-coverage") == null) {
            style.addSource(RasterSource("radar-coverage", TileSet("2.2.0", NativeRadarUrls.rainViewerCoverageTemplate)
                .apply { maxZoom = 7f; attribution = "<a href=\"https://www.rainviewer.com/\">RainViewer</a>" }, 256))
            style.addLayerBelow(RasterLayer("radar-coverage", "radar-coverage")
                .withProperties(rasterOpacity(0.28f), rasterFadeDuration(0f)), "selected-place")
        }
    }

    private suspend fun loadImage(frame: NativeRadarFrame, bounds: NativeRadarBounds) {
        val url = imageUrl(frame, bounds)
        if (images.containsKey(url)) return
        val bitmap = radarBitmap(url)
        coroutineContext.ensureActive()
        images[url] = bitmap
    }

    suspend fun display(frame: NativeRadarFrame, bounds: NativeRadarBounds) {
        val style = map?.style ?: return
        if (closed) return
        if (frame.source == NativeRadarSource.RAINVIEWER && currentFrame == frame) return
        transition?.end()
        val prefix = "weather-${sequence++}"
        val ids = mutableListOf<String>()
        if (frame.source == NativeRadarSource.RAINVIEWER) {
            ids += checkNotNull(pendingTile?.takeIf { it.frame == frame }).id
            pendingTile = null
        } else {
            radarImageBounds(bounds).forEachIndexed { index, part ->
                val image = checkNotNull(images[imageUrl(frame, part)])
                val id = "$prefix-$index"
                style.addSource(ImageSource(id, LatLngQuad(LatLng(part.north, part.west), LatLng(part.north, part.east),
                    LatLng(part.south, part.east), LatLng(part.south, part.west)), image))
                addLayer(style, id)
                ids += id
            }
        }
        val layers = ids.map { checkNotNull(style.getLayerAs<RasterLayer>(it)) }
        try {
            coroutineContext.ensureActive()
        } catch (error: Exception) {
            if (!closed) ids.forEach { style.removeLayer(it); style.removeSource(it) }
            throw error
        }
        val old = currentLayers
        val oldLayers = old.mapNotNull { style.getLayerAs<RasterLayer>(it) }
        currentLayers = ids
        currentFrame = frame
        currentBounds = bounds
        transition = ValueAnimator.ofFloat(0f, 0.72f).apply {
            duration = 180
            addUpdateListener {
                if (!closed) {
                    val opacity = it.animatedValue as Float
                    layers.forEach { layer -> layer.setProperties(rasterOpacity(opacity)) }
                    oldLayers.forEach { layer -> layer.setProperties(rasterOpacity(0.72f - opacity)) }
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!closed) old.forEach { style.removeLayer(it); style.removeSource(it) }
                }
            })
            start()
        }
    }
    fun clear() {
        transition?.end()
        currentLayers.forEach { map?.style?.removeLayer(it); map?.style?.removeSource(it) }
        currentLayers = emptyList()
        currentFrame = null
        currentBounds = null
        removePendingTile()
    }
    fun synchronize(state: Lifecycle.State, active: Boolean) {
        if (closed) return
        val shouldStart = active && state.isAtLeast(Lifecycle.State.STARTED)
        val shouldResume = active && state.isAtLeast(Lifecycle.State.RESUMED)
        if (!shouldResume && resumed) { view.onPause(); resumed = false }
        if (!shouldStart && started) { view.onStop(); started = false }
        if (shouldStart && !started) { view.onStart(); started = true }
        if (shouldResume && !resumed) { view.onResume(); resumed = true }
    }
    fun close() {
        transition?.cancel()
        if (resumed) view.onPause()
        if (started) view.onStop()
        closed = true
        view.removeOnTileActionListener(tileErrors)
        images.clear()
        view.onDestroy()
        map = null
    }
}
