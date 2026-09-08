package cz.majkey.pocasicesko.data

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ChmiCurrentConditionsTest {
    @Test
    fun bundledCatalogSelectsNearestUsefulStations() {
        val asset = File(System.getProperty("user.dir"), "src/main/assets/chmi_current_stations.json")
        val stations = decodeCurrentStationCatalog(asset.readText())

        val nearest = nearestCurrentStations(
            CzechLocation("Březnice", REGION_ZLIN, 49.1877168, 17.6734827),
            stations,
            count = 3,
        )

        assertEquals(245, stations.size)
        assertEquals("0-203-0-11775", nearest[0].stationId)
        assertEquals("0-203-0-11777", nearest[1].stationId)
        assertTrue(nearest.any { it.sunshine })
    }

    @Test
    fun parserUsesLatestCompleteTenMinuteObservation() {
        val station = CurrentStation(
            stationId = "0-203-0-11775",
            name = "Station",
            latitude = 49.2,
            longitude = 17.7,
            elevation = 250.0,
            sunshine = true,
        )

        val observation = parseCurrentStationObservation(STATION_JSON, station)

        requireNotNull(observation)
        assertEquals(Instant.parse("2026-08-29T09:00:00Z"), observation.time)
        assertEquals(20.4, requireNotNull(observation.temperature), 0.0)
        assertEquals(64, observation.humidity)
        assertEquals(0.0, requireNotNull(observation.precipitation), 0.0)
        assertEquals(7.2, requireNotNull(observation.windSpeed), 0.0)
        assertEquals(270.0, requireNotNull(observation.windDirection), 0.0)
        assertEquals(600.0, requireNotNull(observation.sunshineSeconds), 0.0)
    }

    @Test
    fun parserAveragesRecentSunshineInsteadOfSingleCloud() {
        val station = CurrentStation(
            stationId = "0-203-0-11775",
            name = "Station",
            latitude = 49.2,
            longitude = 17.7,
            elevation = 250.0,
            sunshine = true,
        )

        val observation = parseCurrentStationObservation(SUNSHINE_HISTORY_JSON, station)

        requireNotNull(observation)
        assertEquals(480.0, requireNotNull(observation.sunshineSeconds), 0.0)
    }

    @Test
    fun parserDoesNotAttachOldSunshineToFreshTemperature() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, true)
        val gapped = SUNSHINE_HISTORY_JSON
            .replace("08:40:00", "06:40:00")
            .replace("08:50:00", "06:50:00")

        val observation = requireNotNull(parseCurrentStationObservation(gapped, station))

        assertEquals(Instant.parse("2026-08-29T09:00:00Z"), observation.time)
        assertEquals(300.0, requireNotNull(observation.sunshineSeconds), 0.0)
    }

    @Test
    fun parserLeavesSunshineUnknownWhenOnlyOldSensorReportsExist() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, true)
        val gapped = SUNSHINE_HISTORY_JSON
            .replace("08:40:00", "06:40:00")
            .replace("08:50:00", "06:50:00")
            .replace("\"SSV10M\",\"2026-08-29T09:00:00Z\"", "\"unsupported\",\"2026-08-29T09:00:00Z\"")

        val observation = requireNotNull(parseCurrentStationObservation(gapped, station))

        assertEquals(Instant.parse("2026-08-29T09:00:00Z"), observation.time)
        assertEquals(null, observation.sunshineSeconds)
        assertEquals(20.4, requireNotNull(observation.temperature), 0.0)
    }

    @Test
    fun parserExcludesIntervalEndingAtTheStartOfTheSunshineWindow() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, true)
        val boundary = SUNSHINE_HISTORY_JSON
            .replace("08:40:00", "08:00:00")
            .replace("08:50:00", "08:10:00")

        val observation = requireNotNull(parseCurrentStationObservation(boundary, station))

        assertEquals(420.0, requireNotNull(observation.sunshineSeconds), 0.0)
    }

    @Test
    fun stationSelectionDoesNotReplaceNearbySensorsForDistantSunshine() {
        val location = CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0)
        val stations = listOf(
            CurrentStation("0-1", "One", 50.00, 14.01, 200.0, false),
            CurrentStation("0-2", "Two", 50.00, 14.02, 200.0, false),
            CurrentStation("0-3", "Three", 50.00, 14.03, 200.0, false),
            CurrentStation("0-4", "Sun", 50.00, 14.04, 200.0, true),
        )

        val selected = nearestCurrentStations(location, stations, count = 3)

        assertEquals(3, selected.size)
        assertEquals(listOf("0-1", "0-2", "0-3"), selected.map { it.stationId })
    }

    @Test
    fun parserRejectsPayloadWithoutCompleteRequiredValues() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        listOf("T", "SRA10M").forEach { element ->
            val incomplete = STATION_JSON.replace(",\"$element\",", ",\"unsupported\",")
            assertEquals(null, parseCurrentStationObservation(incomplete, station))
        }
    }

    @Test
    fun nonFiniteAndOutOfRangeHumidityIsMissingRatherThanClamped() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        listOf("\"NaN\"", "\"Infinity\"", "-1", "101", "null").forEach { value ->
            val invalid = STATION_JSON.replace(",64,", ",$value,").replace(",66,", ",$value,")
            val observation = requireNotNull(parseCurrentStationObservation(invalid, station))
            assertEquals(null, observation.humidity)
            assertEquals(Instant.parse("2026-08-29T09:00:00Z"), observation.time)
        }
        listOf(0, 100).forEach { value ->
            val valid = STATION_JSON.replace(",64,", ",$value,")
            assertEquals(value, requireNotNull(parseCurrentStationObservation(valid, station)).humidity)
        }
    }

    @Test
    fun latestWetGaugeRemainsUsableWithoutHumidity() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        val model = CurrentWeather(
            time = "2026-08-29T11:05", temperature = 22.0, feelsLike = 22.0,
            humidity = 50, precipitation = 0.0, weatherCode = 0, cloudCover = 0,
            pressure = 1015.0, windSpeed = 5.0, windDirection = 270, windGusts = 8.0, isDay = true,
        )
        val wet = STATION_JSON.replace("\"SRA10M\",\"2026-08-29T09:00:00Z\",0.0", "\"SRA10M\",\"2026-08-29T09:00:00Z\",0.2")
        listOf(
            wet.replace("\"H\",\"2026-08-29T09:00:00Z\"", "\"unsupported\",\"2026-08-29T09:00:00Z\""),
            wet.replace("64,\"\",5]", "64,\"\",2]"),
            wet.replace("64,\"\",5]", "101,\"\",5]"),
        ).forEach { json ->
            val observation = requireNotNull(parseCurrentStationObservation(json, station))
            assertEquals(Instant.parse("2026-08-29T09:00:00Z"), observation.time)
            assertEquals(null, observation.humidity)
            assertEquals(0.2, requireNotNull(observation.precipitation), 0.0)
            val fused = fuseCurrentConditions(model,
                CzechLocation("Point", REGION_ZLIN, 49.2, 17.7), listOf(observation),
                Instant.parse("2026-08-29T09:05:00Z"))
            assertEquals(61, fused.weatherCode)
            assertEquals(50, fused.humidity)
            assertEquals(0.0, fused.precipitation, 0.0)
        }
    }

    @Test
    fun missingLatestGaugeDoesNotBorrowOlderRainForNewTemperature() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        val incomplete = STATION_JSON.replace("\"SRA10M\",\"2026-08-29T09:00:00Z\"", "\"unsupported\",\"2026-08-29T09:00:00Z\"")
        val observation = requireNotNull(parseCurrentStationObservation(incomplete, station))
        assertEquals(Instant.parse("2026-08-29T08:50:00Z"), observation.time)
        assertEquals(20.1, requireNotNull(observation.temperature), 0.0)
    }

    @Test
    fun currentDisplayAcceptsGoodAndProvisionalQualityOnly() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        listOf("0", "0.0", "5", "5.0", "\"5\"").forEach { quality ->
            val json = STATION_JSON.replace(",\"\",5]", ",\"\",$quality]")
            val observation = requireNotNull(parseCurrentStationObservation(json, station))
            assertEquals(20.4, requireNotNull(observation.temperature), 0.0)
        }
        listOf("1", "2", "3", "4", "6", "-1", "5.5", "true", "null", "\"NaN\"", "\"unknown\"")
            .forEach { quality ->
                val json = STATION_JSON.replace(",\"\",5]", ",\"\",$quality]")
                assertEquals(quality, null, parseCurrentStationObservation(json, station))
            }
    }

    @Test
    fun rejectedLatestQualityKeepsThePreviousCompleteObservationTimestamp() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        val badLatest = STATION_JSON.replace("20.4,\"\",5]", "20.4,\"\",2]")
        val observation = requireNotNull(parseCurrentStationObservation(badLatest, station))
        assertEquals(Instant.parse("2026-08-29T08:50:00Z"), observation.time)
        assertEquals(20.1, requireNotNull(observation.temperature), 0.0)
    }

    @Test
    fun variableWindIsNotNorthAndBackupGaugeRemainsUsable() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        val flagged = STATION_JSON
            .replace("270,\"\",5]", "0,\"V\",5]")
            .replace("0.0,\"\",5]", "0.0,\"Z\",5]")
        val observation = requireNotNull(parseCurrentStationObservation(flagged, station))
        assertEquals(null, observation.windDirection)
        assertEquals(7.2, requireNotNull(observation.windSpeed), 0.0)
        assertEquals(0.0, requireNotNull(observation.precipitation), 0.0)
        val badWindQuality = STATION_JSON.replace("270,\"\",5]", "270,\"\",1]")
        assertEquals(null, requireNotNull(parseCurrentStationObservation(badWindQuality, station)).windDirection)
    }

    @Test
    fun missingQualityHeaderAndUnknownRequiredMeasurementFlagsFailClosed() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        assertThrows(IllegalArgumentException::class.java) {
            parseCurrentStationObservation(STATION_JSON.replace("FLAG,QUALITY", "FLAG,UNSUPPORTED"), station)
        }
        assertEquals(null, parseCurrentStationObservation(
            STATION_JSON.replace(",\"\",5]", ",\"X\",5]"), station,
        ))
    }

    companion object {
        private val STATION_JSON = """
            {
              "data": {"data": {
                "header": "STATION,ELEMENT,DT,VAL,FLAG,QUALITY",
                "values": [
                  ["0-203-0-11775","T","2026-08-29T08:50:00Z",20.1,"",5],
                  ["0-203-0-11775","H","2026-08-29T08:50:00Z",66,"",5],
                  ["0-203-0-11775","SRA10M","2026-08-29T08:50:00Z",0.0,"",5],
                  ["0-203-0-11775","T","2026-08-29T09:00:00Z",20.4,"",5],
                  ["0-203-0-11775","H","2026-08-29T09:00:00Z",64,"",5],
                  ["0-203-0-11775","SRA10M","2026-08-29T09:00:00Z",0.0,"",5],
                  ["0-203-0-11775","F","2026-08-29T09:00:00Z",2.0,"",5],
                  ["0-203-0-11775","D","2026-08-29T09:00:00Z",270,"",5],
                  ["0-203-0-11775","SSV10M","2026-08-29T09:00:00Z",600,"",5],
                  ["0-203-0-11775","RGLB10","2026-08-29T09:00:00Z",575,"",5]
                ]
              }}
            }
        """.trimIndent()

        private val SUNSHINE_HISTORY_JSON = """
            {
              "data": {"data": {
                "header": "STATION,ELEMENT,DT,VAL,FLAG,QUALITY",
                "values": [
                  ["0-203-0-11775","T","2026-08-29T08:40:00Z",20.0,"",5],
                  ["0-203-0-11775","H","2026-08-29T08:40:00Z",65,"",5],
                  ["0-203-0-11775","SRA10M","2026-08-29T08:40:00Z",0.0,"",5],
                  ["0-203-0-11775","SSV10M","2026-08-29T08:40:00Z",600,"",5],
                  ["0-203-0-11775","T","2026-08-29T08:50:00Z",20.2,"",5],
                  ["0-203-0-11775","H","2026-08-29T08:50:00Z",64,"",5],
                  ["0-203-0-11775","SRA10M","2026-08-29T08:50:00Z",0.0,"",5],
                  ["0-203-0-11775","SSV10M","2026-08-29T08:50:00Z",540,"",5],
                  ["0-203-0-11775","T","2026-08-29T09:00:00Z",20.4,"",5],
                  ["0-203-0-11775","H","2026-08-29T09:00:00Z",64,"",5],
                  ["0-203-0-11775","SRA10M","2026-08-29T09:00:00Z",0.0,"",5],
                  ["0-203-0-11775","SSV10M","2026-08-29T09:00:00Z",300,"",5]
                ]
              }}
            }
        """.trimIndent()
    }
}
