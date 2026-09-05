package cz.majkey.pocasicesko.data

import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun stationSelectionKeepsOneSunshineSource() {
        val location = CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0)
        val stations = listOf(
            CurrentStation("0-1", "One", 50.00, 14.01, 200.0, false),
            CurrentStation("0-2", "Two", 50.00, 14.02, 200.0, false),
            CurrentStation("0-3", "Three", 50.00, 14.03, 200.0, false),
            CurrentStation("0-4", "Sun", 50.00, 14.04, 200.0, true),
        )

        val selected = nearestCurrentStations(location, stations, count = 3)

        assertEquals(3, selected.size)
        assertTrue(selected.any { it.sunshine })
        assertTrue(selected.none { it.stationId == "0-3" })
    }

    @Test
    fun parserRejectsPayloadWithoutCompleteRequiredValues() {
        val station = CurrentStation("0-203-0-11775", "Station", 49.2, 17.7, 250.0, false)
        val incomplete = STATION_JSON.replace(",\"H\",", ",\"unsupported\",")

        assertEquals(null, parseCurrentStationObservation(incomplete, station))
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
