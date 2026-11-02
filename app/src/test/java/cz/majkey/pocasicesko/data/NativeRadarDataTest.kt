package cz.majkey.pocasicesko.data

import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRadarDataTest {
    private val now = Instant.parse("2026-11-02T10:15:00Z")
    private val run = Instant.parse("2026-11-02T06:00:00Z")
    private val prague = NativeRadarBounds(47.0, 11.0, 53.0, 17.0)
    private val japan = NativeRadarBounds(30.0, 135.0, 40.0, 145.0)

    @Test
    fun observedFramesKeepRealTimesPathsAndBoundedHistory() {
        val json = manifest((0..29).map { now.minusSeconds((29 - it) * 600L) })
        val snapshot = parseNativeRadarObserved(json, now)
        assertEquals(NativeRadarMode.OBSERVED, snapshot.mode)
        assertEquals(13, snapshot.frames.size)
        assertEquals(now.minusSeconds(7200), snapshot.frames.first().validTime)
        assertEquals(now, snapshot.horizon)
        assertTrue(snapshot.frames.all { it.run == null && it.accumulationHours == 0 })
        assertTrue(NativeRadarUrls.tileTemplate(snapshot.frames.last()).matches(
            Regex("https://tilecache\\.rainviewer\\.com/v2/radar/frame_29/512/\\{z}/\\{x}/\\{y}/2/1_1\\.png"),
        ))
    }

    @Test
    fun observedMetadataRejectsForeignHostsBadPathsTimesAndOrder() {
        val valid = manifest(listOf(now.minusSeconds(600), now))
        for (json in listOf(
            valid.replace("https://tilecache.rainviewer.com", "https://tilecache.rainviewer.com.evil.example"),
            valid.replace("https://tilecache.rainviewer.com", "http://tilecache.rainviewer.com"),
            valid.replace("/v2/radar/frame_0", "/v2/radar/../../private"),
            valid.replace("/v2/radar/frame_0", "/v2/radar/name%2fsecret"),
            manifest(listOf(now.plusSeconds(301))),
            manifest(listOf(now, now)),
            manifest(listOf(now, now.minusSeconds(600))),
            "[]",
            "x".repeat(MAX_RADAR_METADATA_BYTES + 1),
            JSONObject(valid).apply {
                getJSONObject("radar").getJSONArray("past").getJSONObject(0).put("time", "1793613600")
            }.toString(),
            JSONObject(valid).apply {
                getJSONObject("radar").getJSONArray("past").getJSONObject(0).put("time", now.epochSecond - 0.5)
            }.toString(),
        )) assertThrows(Exception::class.java) { parseNativeRadarObserved(json, now) }
        assertThrows(IOException::class.java) {
            parseNativeRadarObserved(manifest(listOf(now.minusSeconds(3601))), now)
        }
    }

    @Test
    fun europeUsesFreshMainRunAndActualHourlyDomainThrough78Hours() {
        val intermediateRun = run.plusSeconds(3 * 3600)
        val xml = capabilities(
            NativeRadarSource.EUROPE,
            defaultRun = intermediateRun,
            runs = listOf(run.minusSeconds(6 * 3600), run, intermediateRun),
        )
        val snapshot = parseNativeRadarForecast(xml, NativeRadarSource.EUROPE, now)
        assertEquals(74, snapshot.frames.size)
        assertEquals(Instant.parse("2026-11-02T11:00:00Z"), snapshot.frames.first().validTime)
        assertEquals(run.plusSeconds(78 * 3600), snapshot.horizon)
        assertTrue(snapshot.frames.all { it.run == run && it.accumulationHours == 1 })
        assertEquals(NativeRadarBounds(29.46875, -23.53125, 70.53125, 62.53125), snapshot.coverage)
    }

    @Test
    fun globalSixHourTotalsRespectSelectedCycleInsteadOfMergedRunTail() {
        for ((selectedRun, hours) in listOf(run to 120L, run.minusSeconds(6 * 3600) to 180L)) {
            val xml = capabilities(NativeRadarSource.GLOBAL, defaultRun = selectedRun, runs = listOf(selectedRun))
            val snapshot = parseNativeRadarForecast(xml, NativeRadarSource.GLOBAL, now)
            assertEquals(selectedRun.plusSeconds(hours * 3600), snapshot.horizon)
            assertEquals(Instant.parse("2026-11-02T12:00:00Z"), snapshot.frames.first().validTime)
            assertTrue(snapshot.frames.all { it.accumulationHours == 6 && it.validTime.epochSecond % 21600 == 0L })
            assertTrue(snapshot.frames.zipWithNext().all { (a, b) -> b.validTime.epochSecond - a.validTime.epochSecond == 21600L })
        }
    }

    @Test
    fun forecastNeverExtendsBeyondAdvertisedEnd() {
        val end = Instant.parse("2026-11-03T12:00:00Z")
        val snapshot = parseNativeRadarForecast(capabilities(NativeRadarSource.EUROPE, end = end), NativeRadarSource.EUROPE, now)
        assertEquals(end, snapshot.horizon)
    }

    @Test
    fun forecastRejectsUnsafeXmlWrongLayerStyleAndMalformedDimensions() {
        val xml = capabilities(NativeRadarSource.EUROPE)
        for (invalid in listOf(
            "<!DOCTYPE WMS_Capabilities [<!ENTITY source SYSTEM 'file:///private'>]>$xml",
            "<!doctype WMS_Capabilities>$xml",
            xml.replace(NativeRadarSource.EUROPE.style, "other-style"),
            xml.replace(NativeRadarSource.EUROPE.layer, "other-layer"),
            xml.replace("PT1H", "PT0H"),
            xml.replace("units=\"ISO8601\"", "units=\"hours\""),
            xml.replace("minx=\"29.46875\"", "minx=\"NaN\""),
            xml.replace("maxy=\"62.53125\"", "maxy=\"181\""),
            xml.replace("2026-11-02T06:00:00Z", "2026-11-02T06:00:00+00:00"),
            xml.replace("</Layer>", "<Dimension name=\"time\" units=\"ISO8601\">bad</Dimension></Layer>"),
            xml.dropLast(10),
            "x".repeat(MAX_RADAR_METADATA_BYTES + 1),
        )) assertThrows(Exception::class.java) { parseNativeRadarForecast(invalid, NativeRadarSource.EUROPE, now) }
    }

    @Test
    fun sourceSelectionRequiresFullViewportWithinEuropeIncludingWrappedWorlds() {
        assertEquals(NativeRadarSource.EUROPE, NativeRadarData.sourceFor(prague))
        assertEquals(NativeRadarSource.EUROPE, NativeRadarData.sourceFor(prague.copy(west = 371.0, east = 377.0)))
        assertEquals(NativeRadarSource.GLOBAL, NativeRadarData.sourceFor(japan))
        assertEquals(NativeRadarSource.GLOBAL, NativeRadarData.sourceFor(prague.copy(west = -24.0)))
        assertEquals(NativeRadarSource.GLOBAL, NativeRadarData.sourceFor(NativeRadarBounds(-70.0, 170.0, 70.0, 190.0)))
        assertThrows(IllegalArgumentException::class.java) { NativeRadarBounds(53.0, 11.0, 47.0, 17.0) }
    }

    @Test
    fun urlsKeepCorrectStylesReferenceTimesAndMercatorAxisOrder() {
        val frame = NativeRadarFrame(now.plusSeconds(3600), run, NativeRadarSource.EUROPE)
        val url = URLDecoder.decode(NativeRadarUrls.wmsViewport(frame, NativeRadarBounds(0.0, 0.0, 1.0, 1.0), 390, 600), "UTF-8")
        assertTrue(url.startsWith("https://maps.dwd.de/geoserver/dwd/${frame.source.layer}/ows?"))
        assertTrue(url.contains("layers=dwd:${frame.source.layer}&styles=${frame.source.style}"))
        assertTrue(url.contains("crs=EPSG:3857&width=390&height=600"))
        assertTrue(url.contains("time=2026-11-02T11:15:00.000Z&dim_reference_time=2026-11-02T06:00:00.000Z"))
        val bbox = url.substringAfter("&bbox=").split(',').map(String::toDouble)
        assertEquals(0.0, bbox[0], 0.001)
        assertEquals(0.0, bbox[1], 0.001)
        assertEquals(111319.490793, bbox[2], 0.001)
        assertEquals(111325.142866, bbox[3], 0.001)
        assertTrue(NativeRadarUrls.tileTemplate(frame).contains("bbox={bbox-epsg-3857}"))
        assertTrue(URLDecoder.decode(NativeRadarUrls.legend(frame.source), "UTF-8").contains("style=${frame.source.style}"))
        assertThrows(IllegalArgumentException::class.java) {
            NativeRadarUrls.wmsViewport(frame, NativeRadarBounds(0.0, 170.0, 1.0, 190.0), 256, 256)
        }
        assertThrows(IllegalArgumentException::class.java) { NativeRadarUrls.wmsViewport(frame, prague, 8192, 8192) }
    }

    @Test
    fun providerProbesOnlyEndpointsAndReusesFreshSnapshot() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val provider = NativeRadarData { url, _ ->
            requests += url
            if (url.contains("GetCapabilities")) geometCapabilities().toByteArray() else PNG
        }
        val first = provider.fetch(NativeRadarMode.FORECAST, prague, now) as NativeRadarResult.Success
        assertEquals(3, requests.size)
        assertEquals(run.minusSeconds(6 * 3600).plusSeconds(240 * 3600), first.snapshot.horizon)
        assertEquals(NativeRadarSource.GDPS, first.snapshot.source)
        assertTrue(requests.drop(1).all { it.contains("width=1&height=1") })
        provider.fetch(NativeRadarMode.FORECAST, prague.copy(west = 12.0), now.plusSeconds(60))
        assertEquals(3, requests.size)
        provider.fetch(NativeRadarMode.FORECAST, prague, now.plusSeconds(601))
        assertEquals(6, requests.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (first.snapshot.frames as MutableList<NativeRadarFrame>).clear()
        }
    }

    @Test
    fun missingTailCapsHorizonAtVerifiedMiddleWithAtMostThreeProbes() = runBlocking<Unit> {
        var probes = 0
        val provider = NativeRadarData { url, _ ->
            if (url.startsWith("https://geo.weather.gc.ca/")) throw IOException("GDPS unavailable.")
            if (url.contains("GetCapabilities")) capabilities(NativeRadarSource.EUROPE).toByteArray()
            else if (++probes == 2) WMS_ERROR.toByteArray() else PNG
        }
        val result = provider.fetch(NativeRadarMode.FORECAST, prague, now) as NativeRadarResult.Success
        assertEquals(3, probes)
        assertEquals(37, result.snapshot.frames.size)
        assertEquals(Instant.parse("2026-11-03T23:00:00Z"), result.snapshot.horizon)
    }

    @Test
    fun globalWmsXmlReturnsTypedFailureWithoutInventedFrames() = runBlocking<Unit> {
        var probes = 0
        val provider = NativeRadarData { url, _ ->
            if (url.startsWith("https://geo.weather.gc.ca/")) throw IOException("GDPS unavailable.")
            if (url.contains("GetCapabilities")) capabilities(NativeRadarSource.GLOBAL).toByteArray()
            else { probes++; WMS_ERROR.toByteArray() }
        }
        val failure = provider.fetch(NativeRadarMode.FORECAST, japan, now) as NativeRadarResult.Failure
        assertEquals(NativeRadarSource.GLOBAL, failure.source)
        assertEquals(NativeRadarFailure.UNAVAILABLE, failure.reason)
        assertEquals(1, probes)
    }

    @Test
    fun unavailableNewCycleFallsBackOnlyToDeclaredFreshMainRun() = runBlocking<Unit> {
        val previousRun = run.minusSeconds(6 * 3600)
        val requests = mutableListOf<String>()
        val provider = NativeRadarData { url, _ ->
            if (url.startsWith("https://geo.weather.gc.ca/")) throw IOException("GDPS unavailable.")
            requests += url
            when {
                url.contains("GetCapabilities") -> capabilities(NativeRadarSource.GLOBAL, runs = listOf(previousRun, run)).toByteArray()
                URLDecoder.decode(url, "UTF-8").contains("dim_reference_time=2026-11-02T06:00:00.000Z") -> WMS_ERROR.toByteArray()
                else -> PNG
            }
        }
        val result = provider.fetch(NativeRadarMode.FORECAST, japan, now) as NativeRadarResult.Success
        assertEquals(4, requests.size)
        assertTrue(result.snapshot.frames.all { it.run == previousRun })
        assertEquals(previousRun.plusSeconds(180 * 3600), result.snapshot.horizon)
    }

    @Test
    fun providerPreservesTypedStalenessNetworkInvalidUtf8AndCancellation() = runBlocking<Unit> {
        val stale = NativeRadarData { _, _ ->
            capabilities(NativeRadarSource.GLOBAL, defaultRun = run.minusSeconds(86400), runs = listOf(run.minusSeconds(86400))).toByteArray()
        }.fetch(NativeRadarMode.FORECAST, japan, now) as NativeRadarResult.Failure
        assertEquals(NativeRadarFailure.STALE, stale.reason)
        val network = NativeRadarData { _, _ -> throw IOException("HTTP 503") }
            .fetch(NativeRadarMode.FORECAST, japan, now) as NativeRadarResult.Failure
        assertEquals(NativeRadarFailure.NETWORK, network.reason)
        val invalid = NativeRadarData { _, _ -> byteArrayOf(-61, 40) }
            .fetch(NativeRadarMode.OBSERVED, prague, now) as NativeRadarResult.Failure
        assertEquals(NativeRadarFailure.INVALID_METADATA, invalid.reason)
        assertThrows(CancellationException::class.java) {
            runBlocking { NativeRadarData { _, _ -> throw CancellationException("canceled") }.fetch(NativeRadarMode.OBSERVED, prague, now) }
        }
    }

    @Test
    fun childRequestTimeoutReturnsNetworkFailureWithoutCancelingCaller() = runBlocking<Unit> {
        val provider = NativeRadarData { _, _ -> withTimeout(10) { awaitCancellation() } }
        val result = provider.fetch(NativeRadarMode.OBSERVED, prague, now) as NativeRadarResult.Failure
        assertEquals(NativeRadarFailure.NETWORK, result.reason)
        assertEquals("Radar request timed out.", result.message)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun completeFetchDeadlineCancelsHungProbeAndReturnsNetworkFailure() = runBlocking<Unit> {
        var requests = 0
        var probeCanceled = false
        val provider = NativeRadarData(fetchTimeoutMillis = 100) { url, _ ->
            requests++
            if (url.contains("GetCapabilities")) geometCapabilities().toByteArray() else {
                try {
                    awaitCancellation()
                } finally {
                    probeCanceled = true
                }
            }
        }
        val result = provider.fetch(NativeRadarMode.FORECAST, prague, now) as NativeRadarResult.Failure
        assertEquals(NativeRadarFailure.NETWORK, result.reason)
        assertEquals("Radar update timed out.", result.message)
        assertEquals(2, requests)
        assertTrue(probeCanceled)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun stalePrimaryMetadataRetriesVerifiedAliasOnce() = runBlocking<Unit> {
        val requests = mutableListOf<String>()
        val provider = NativeRadarData { url, _ ->
            if (url.startsWith("https://geo.weather.gc.ca/")) throw IOException("GDPS unavailable.")
            requests += url
            when {
                url.contains("/wms?") -> capabilities(
                    NativeRadarSource.GLOBAL, defaultRun = run.minusSeconds(86400), runs = listOf(run.minusSeconds(86400)),
                ).toByteArray()
                url.contains("GetCapabilities") -> capabilities(NativeRadarSource.GLOBAL).toByteArray()
                else -> PNG
            }
        }
        val result = provider.fetch(NativeRadarMode.FORECAST, japan, now) as NativeRadarResult.Success
        assertEquals(4, requests.size)
        assertTrue(requests[0].contains("/wms?service=WMS&version=1.3.0&request=GetCapabilities"))
        assertTrue(requests[1].contains("/ows?service=WMS&version=1.3.0&request=GetCapabilities"))
        assertTrue(result.snapshot.frames.all { it.run == run })
    }

    @Test
    fun staleMetadataOnBothAliasesReturnsStaleWithoutProbing() = runBlocking<Unit> {
        var requests = 0
        val provider = NativeRadarData { url, _ ->
            if (url.startsWith("https://geo.weather.gc.ca/")) throw IOException("GDPS unavailable.")
            requests++
            capabilities(NativeRadarSource.GLOBAL, defaultRun = run.minusSeconds(86400), runs = listOf(run.minusSeconds(86400))).toByteArray()
        }
        val result = provider.fetch(NativeRadarMode.FORECAST, japan, now) as NativeRadarResult.Failure
        assertEquals(NativeRadarFailure.STALE, result.reason)
        assertEquals(2, requests)
    }

    @Test
    fun parentCancellationAndParentTimeoutRemainCancellation() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        var requestCanceled = false
        val provider = NativeRadarData { _, _ ->
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                requestCanceled = true
            }
        }
        val request = async { provider.fetch(NativeRadarMode.OBSERVED, prague, now) }
        entered.await()
        request.cancel(CancellationException("Screen closed."))
        assertThrows(CancellationException::class.java) { runBlocking { request.await() } }
        assertTrue(request.isCancelled)
        assertTrue(requestCanceled)
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { withTimeout(10) { provider.fetch(NativeRadarMode.OBSERVED, prague, now) } }
        }
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test
    fun gdpsUsesAdvertisedSixHourTimesAndTwelveHourReferenceDomain() {
        val modelRun = run.minusSeconds(21600)
        val times = listOf(12L, 18L, 30L, 36L, 240L).map { modelRun.plusSeconds(it * 3600) }
        val snapshot = parseNativeRadarForecast(geometCapabilities(times = times), NativeRadarSource.GDPS, now)
        assertEquals(times, snapshot.frames.map { it.validTime })
        assertEquals(modelRun.plusSeconds(240 * 3600), snapshot.horizon)
        assertEquals(NativeRadarBounds(-90.0, -180.0, 90.0, 180.0), snapshot.coverage)
        assertTrue(snapshot.frames.all { it.run == modelRun && it.accumulationHours == 6 })
        assertEquals("Environment and Climate Change Canada", snapshot.source.providerName)
        val url = URLDecoder.decode(NativeRadarUrls.wmsViewport(snapshot.frames.last(), prague, 256, 256), "UTF-8")
        assertTrue(url.startsWith("https://geo.weather.gc.ca/geomet?"))
        assertTrue(url.contains("layers=GDPS_15km_Precip-Accum6h&styles=PRECIPMM-LINEAR"))
        assertTrue(url.contains("time=2026-11-12T00:00:00Z&dim_reference_time=2026-11-02T00:00:00Z"))
        assertTrue(url.endsWith("&interpolation=true"))
        assertTrue(NativeRadarUrls.capabilities(NativeRadarSource.GDPS).endsWith("&layer=GDPS_15km_Precip-Accum6h"))
    }

    @Test
    fun gdpsRejectsInvalidReferenceDomainsRatesAndInventedTimes() {
        val valid = geometCapabilities()
        for (invalid in listOf(
            valid.replace("PT12H", "PT6H"),
            valid.replace("6-hour accumulation [mm]", "Instantaneous precipitation rate [kg/(m²s)]"),
            valid.replace("nearestValue=\"0\"", "nearestValue=\"1\""),
            valid.replace("2026-11-02T06:00:00Z,", "2026-11-02T07:00:00Z,"),
            valid.replace("2026-11-12T00:00:00Z", "2026-11-12T06:00:00Z"),
            valid.replace("default=\"2026-11-02T00:00:00Z\"", "default=\"2026-11-02T06:00:00Z\""),
        )) assertThrows(Exception::class.java) { parseNativeRadarForecast(invalid, NativeRadarSource.GDPS, now) }
        val modelRun = run.minusSeconds(21600)
        assertEquals(NativeRadarSource.GDPS, parseNativeRadarForecast(valid, NativeRadarSource.GDPS, modelRun.plusSeconds(17 * 3600)).source)
        assertThrows(IOException::class.java) {
            parseNativeRadarForecast(valid, NativeRadarSource.GDPS, modelRun.plusSeconds(18 * 3600 + 1))
        }
    }

    @Test
    fun rejectedGdpsTailFallsBackToOneDwdTimelineWithinFourProbes() = runBlocking<Unit> {
        val modelRun = run.minusSeconds(21600)
        var probes = 0
        val provider = NativeRadarData { url, _ ->
            when {
                url.contains("GetCapabilities") -> if (url.startsWith("https://geo.weather.gc.ca/")) geometCapabilities().toByteArray()
                    else capabilities(NativeRadarSource.EUROPE).toByteArray()
                else -> {
                    probes++
                    if (url.startsWith("https://geo.weather.gc.ca/") &&
                        URLDecoder.decode(url, "UTF-8").contains("time=2026-11-12T00:00:00Z")) WMS_ERROR.toByteArray() else PNG
                }
            }
        }
        val result = provider.fetch(NativeRadarMode.FORECAST, prague, now) as NativeRadarResult.Success
        assertEquals(4, probes)
        assertEquals(NativeRadarSource.EUROPE, result.snapshot.source)
        assertTrue(result.snapshot.frames.all { it.source == NativeRadarSource.EUROPE && it.run == run })
        assertTrue(result.snapshot.horizon < modelRun.plusSeconds(240 * 3600))
    }

    @Test
    fun providerNeverExceedsFourProbesAcrossFailedSources() = runBlocking<Unit> {
        var probes = 0
        val provider = NativeRadarData { url, _ ->
            if (url.contains("GetCapabilities")) {
                if (url.startsWith("https://geo.weather.gc.ca/")) geometCapabilities().toByteArray()
                else capabilities(NativeRadarSource.EUROPE, runs = listOf(run.minusSeconds(21600), run)).toByteArray()
            } else {
                probes++
                if (probes % 2 == 1) PNG else WMS_ERROR.toByteArray()
            }
        }
        val result = provider.fetch(NativeRadarMode.FORECAST, prague, now) as NativeRadarResult.Failure
        assertEquals(NativeRadarFailure.UNAVAILABLE, result.reason)
        assertEquals(4, probes)
    }

    @Test fun transportRetriesOneTransientFailureAndKeepsTheSuccessfulPayload() = runBlocking<Unit> {
        for (failure in listOf(NativeRadarHttpException(503), java.net.SocketTimeoutException("timeout"))) {
            var calls = 0
            val expected = byteArrayOf(1, 2, 3)
            val actual = retryNativeRadarRequest {
                if (++calls == 1) throw failure
                expected
            }
            assertTrue(expected.contentEquals(actual))
            assertEquals(2, calls)
        }
    }

    @Test fun transportNeverRetriesClientErrorsOrMoreThanOnce() = runBlocking<Unit> {
        for (status in listOf(400, 404, 429, 503)) {
            var calls = 0
            val failure = try {
                retryNativeRadarRequest { calls++; throw NativeRadarHttpException(status) }
                error("Expected HTTP failure")
            } catch (error: NativeRadarHttpException) { error }
            assertEquals(status, failure.status)
            assertEquals(if (status == 503) 2 else 1, calls)
        }
        var calls = 0
        try {
            retryNativeRadarRequest { calls++; throw IOException("Response is too large") }
            error("Expected invalid response failure")
        } catch (_: IOException) { assertEquals(1, calls) }
    }

    @Test fun transportDoesNotRetryCallerCancellation() = runBlocking<Unit> {
        var calls = 0
        try {
            withTimeout(10) { retryNativeRadarRequest { calls++; awaitCancellation() } }
            error("Expected caller timeout")
        } catch (_: TimeoutCancellationException) {
            assertEquals(1, calls)
        }
        assertTrue(currentCoroutineContext().isActive)
    }

    private fun geometCapabilities(
        modelRun: Instant = run.minusSeconds(21600),
        times: List<Instant> = (1..40).map { modelRun.plusSeconds(it * 21600L) },
    ): String = """<WMS_Capabilities xmlns="http://www.opengis.net/wms" version="1.3.0"><Capability><Layer>
        <BoundingBox CRS="EPSG:4326" minx="-90" miny="-180" maxx="90" maxy="180"/>
        <Layer><Name>GDPS_15km_Precip-Accum6h</Name><Title>GDPS - Precipitation - 6-hour accumulation [mm]</Title>
        <EX_GeographicBoundingBox><westBoundLongitude>-180.075</westBoundLongitude><eastBoundLongitude>179.925</eastBoundLongitude>
        <southBoundLatitude>-90.075</southBoundLatitude><northBoundLatitude>90.75</northBoundLatitude></EX_GeographicBoundingBox>
        <Dimension name="time" units="ISO8601" nearestValue="0">${times.joinToString(",")}</Dimension>
        <Dimension name="reference_time" units="ISO8601" default="$modelRun">${modelRun.minusSeconds(36 * 3600)}/$modelRun/PT12H</Dimension>
        <Style><Name>PRECIPMM-LINEAR</Name></Style></Layer></Layer></Capability></WMS_Capabilities>"""

    private fun manifest(times: List<Instant>): String = JSONObject().put("host", "https://tilecache.rainviewer.com")
        .put("radar", JSONObject().put("past", JSONArray(times.mapIndexed { index, time ->
            JSONObject().put("time", time.epochSecond).put("path", "/v2/radar/frame_$index")
        }))).toString()

    private fun capabilities(
        source: NativeRadarSource,
        defaultRun: Instant = run,
        runs: List<Instant> = listOf(defaultRun),
        end: Instant = run.plusSeconds(10 * 86400),
    ): String {
        val bounds = if (source == NativeRadarSource.EUROPE) "minx=\"29.46875\" miny=\"-23.53125\" maxx=\"70.53125\" maxy=\"62.53125\""
        else "minx=\"-90\" miny=\"-180\" maxx=\"90\" maxy=\"180\""
        val period = if (source == NativeRadarSource.EUROPE) "PT1H" else "PT3H"
        return """<WMS_Capabilities xmlns="http://www.opengis.net/wms" version="1.3.0"><Capability><Layer>
            <Name>${source.layer}</Name><BoundingBox CRS="EPSG:4326" $bounds/>
            <Dimension name="time" units="ISO8601">${run.minusSeconds(86400)}/$end/$period</Dimension>
            <Dimension name="REFERENCE_TIME" units="ISO8601" default="$defaultRun">${runs.joinToString(",")}</Dimension>
            <Style><Name>${source.style}</Name></Style></Layer></Capability></WMS_Capabilities>"""
    }

    companion object {
        private val PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlV8AAAAASUVORK5CYII=")
        private const val WMS_ERROR = """<ServiceExceptionReport><ServiceException>No data available for NinJo NWP data request</ServiceException></ServiceExceptionReport>"""
    }
}
