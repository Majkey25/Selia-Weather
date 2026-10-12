package cz.majkey.pocasicesko.data

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CurrentConditionsTest {
    @Test
    fun stationSunshineDoesNotEraseModelRainOrInventCloudLayers() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 61, precipitation = 0.4, cloudCover = 100),
            location = CzechLocation("Březnice", REGION_ZLIN, 49.1877, 17.6735),
            observations = listOf(
                observation(49.20, 17.70, now.minusSeconds(600), sunshineSeconds = 600.0),
                observation(49.30, 17.60, now.minusSeconds(900), sunshineSeconds = 540.0),
            ),
            now = now,
        )

        assertEquals(61, fused.weatherCode)
        assertEquals(100, fused.cloudCover)
        assertEquals(null, fused.cloudCoverLow)
        assertEquals(null, fused.cloudCoverMid)
        assertEquals(null, fused.cloudCoverHigh)
        assertEquals(0.4, fused.precipitation, 0.0)
        assertEquals(20.0, fused.temperature, 0.0)
    }

    @Test
    fun freshMeasuredRainOverridesFalseClearSky() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 0),
            location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378),
            observations = listOf(
                observation(50.08, 14.44, now.minusSeconds(300), precipitation = 0.3),
                observation(50.10, 14.40, now.minusSeconds(600), precipitation = 0.2),
            ),
            now = now,
        )

        assertEquals(61, fused.weatherCode)
        assertEquals(0, fused.cloudCover)
        // A ten-minute station total is not the model's fifteen-minute total.
        assertEquals(0.0, fused.precipitation, 0.0)
    }

    @Test
    fun staleOrDistantObservationsDoNotChangeModel() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val model = current(weatherCode = 2, precipitation = 0.0, cloudCover = 50)

        val fused = fuseCurrentConditions(
            model = model,
            location = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378),
            observations = listOf(
                observation(50.08, 14.44, now.minusSeconds(7_200), sunshineSeconds = 600.0),
                observation(48.97, 17.67, now.minusSeconds(300), sunshineSeconds = 600.0),
            ),
            now = now,
        )

        assertEquals(model, fused)
    }

    @Test
    fun delayedStationTemperatureDoesNotTurnTheCurrentSkyClear() {
        val now = Instant.parse("2026-08-29T10:00:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 3, precipitation = 0.0, cloudCover = 100),
            location = CzechLocation("Březnice", REGION_ZLIN, 49.1877, 17.6735),
            observations = listOf(
                observation(49.20, 17.70, now.minusSeconds(3_600), sunshineSeconds = 600.0),
            ),
            now = now,
        )

        assertEquals(3, fused.weatherCode)
        assertEquals(20.0, fused.temperature, 0.0)
    }

    @Test
    fun sunshineIsNotATotalCloudCoverMeasurement() {
        val now = Instant.parse("2026-08-29T10:00:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 3, precipitation = 0.0, cloudCover = 100),
            location = CzechLocation("Praha", REGION_PRAGUE, 50.0, 14.0),
            observations = listOf(
                observation(50.01, 14.0, now.minusSeconds(300)),
                observation(50.02, 14.0, now.minusSeconds(300)),
                observation(50.03, 14.0, now.minusSeconds(300)),
                observation(50.04, 14.0, now.minusSeconds(300), sunshineSeconds = 600.0),
            ),
            now = now,
        )

        assertEquals(3, fused.weatherCode)
        assertEquals(100, fused.cloudCover)
    }

    @Test
    fun partialWorldwideObservationDoesNotInventPrecipitation() {
        val now = Instant.parse("2026-08-31T20:40:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 61, precipitation = 0.4, cloudCover = 100),
            location = CzechLocation("Delhi", "Delhi", 28.6139, 77.209, "IN"),
            observations = listOf(
                CurrentStationObservation(
                    stationId = "VIDP",
                    latitude = 28.567,
                    longitude = 77.117,
                    time = now.minusSeconds(600),
                    temperature = 30.0,
                    humidity = 66,
                    precipitation = null,
                    windSpeed = 9.26,
                    windDirection = 250.0,
                    sunshineSeconds = null,
                    dewPoint = 23.0,
                    pressureHpa = 1_003.0,
                    visibilityMeters = 4_506.1632,
                    cloudCoverPercent = 75,
                ),
            ),
            now = now,
        )

        assertEquals(30.0, fused.temperature, 0.0)
        assertEquals(0.4, fused.precipitation, 0.0)
        assertEquals(1_003.0, fused.pressure, 1e-9)
        assertEquals(4_506.1632, requireNotNull(fused.visibilityMeters), 0.0001)
        // The airport is outside the local sky-condition support radius.
        assertEquals(100, fused.cloudCover)
    }

    @Test
    fun observedCloudCoverCorrectsOnlyDryModelCloudCode() {
        val now = Instant.parse("2026-08-31T20:40:00Z")
        val fused = fuseCurrentConditions(
            model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 0),
            location = CzechLocation("Delhi airport", "Delhi", 28.567, 77.117, "IN"),
            observations = listOf(
                CurrentStationObservation(
                    stationId = "VIDP",
                    latitude = 28.567,
                    longitude = 77.117,
                    time = now.minusSeconds(600),
                    temperature = null,
                    humidity = null,
                    precipitation = null,
                    windSpeed = null,
                    windDirection = null,
                    sunshineSeconds = null,
                    cloudCoverPercent = 75,
                ),
            ),
            now = now,
        )

        assertEquals(2, fused.weatherCode)
        assertEquals(75, fused.cloudCover)
        assertEquals(0.0, fused.precipitation, 0.0)
    }

    @Test
    fun observedCloudCoverUsesProviderSkyCategoryBoundaries() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 0)
        for ((cloudCover, expectedCode) in listOf(19 to 0, 20 to 1, 49 to 1, 50 to 2, 79 to 2, 80 to 3)) {
            val fused = fuseCurrentConditions(
                model,
                CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
                listOf(observation(50.001, 14.0, now.minusSeconds(300)).copy(cloudCoverPercent = cloudCover)),
                now,
            )
            assertEquals("cloudCover=$cloudCover", expectedCode, fused.weatherCode)
            assertEquals(cloudCover, fused.cloudCover)
            assertEquals(model.precipitation, fused.precipitation, 0.0)
        }
    }

    @Test
    fun observedTotalCloudCoverInvalidatesModeledLayersThroughJson() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val original = JSONObject(FORECAST_WITH_CLOUD_LAYERS)
        val snapshot = WeatherParser.parseForecast(original.toString(), 0L)
        val model = snapshot.current
        val fused = fuseCurrentConditions(
            model,
            CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
            listOf(observation(50.001, 14.0, now.minusSeconds(300)).copy(cloudCoverPercent = 0)),
            now,
        )
        assertEquals(null, fused.cloudCoverLow)
        assertEquals(null, fused.cloudCoverMid)
        assertEquals(null, fused.cloudCoverHigh)

        val corrected = JSONObject(applyCurrentConditionsToForecastJson(original.toString(), fused))
        listOf("cloud_cover_low", "cloud_cover_mid", "cloud_cover_high").forEach { field ->
            assertFalse(corrected.getJSONObject("current").has(field))
        }
        val parsed = WeatherParser.parseForecast(corrected.toString(), 0L)
        assertEquals(snapshot.copy(current = fused), parsed)
        assertEquals(0, parsed.current.weatherCode)
        assertEquals(0, parsed.current.cloudCover)
        assertEquals(null, parsed.current.cloudCoverLow)
        assertEquals(null, parsed.current.cloudCoverMid)
        assertEquals(null, parsed.current.cloudCoverHigh)
        assertEquals(original.getJSONObject("hourly").toString(), corrected.getJSONObject("hourly").toString())
        assertEquals(original.getJSONObject("daily").toString(), corrected.getJSONObject("daily").toString())
    }

    @Test
    fun nearestSkyReportDoesNotBorrowFartherPresentWeather() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val clearStation = observation(50.001, 14.0, now.minusSeconds(300)).copy(
            precipitation = null, cloudCoverPercent = 0,
        )
        val drizzleStation = observation(50.01, 14.0, now.minusSeconds(300)).copy(
            precipitation = null, cloudCoverPercent = 100, weatherCode = 51,
        )
        for (reports in listOf(listOf(clearStation, drizzleStation), listOf(drizzleStation, clearStation))) {
            val fused = fuseCurrentConditions(
                current(weatherCode = 0, precipitation = 0.0, cloudCover = 0),
                CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
                reports,
                now,
            )
            assertEquals(0, fused.weatherCode)
            assertEquals(0, fused.cloudCover)
            assertEquals(0.0, fused.precipitation, 0.0)
        }
    }

    @Test
    fun explicitWeatherWithoutCloudCoverDoesNotBorrowAnotherStationsSky() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 38).copy(
            cloudCoverLow = 10, cloudCoverMid = 20, cloudCoverHigh = 0,
        )
        val fused = fuseCurrentConditions(
            model,
            CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
            listOf(
                observation(50.001, 14.0, now.minusSeconds(300)).copy(
                    precipitation = null, weatherCode = 51,
                ),
                observation(50.01, 14.0, now.minusSeconds(300)).copy(
                    precipitation = null, cloudCoverPercent = 0,
                ),
            ),
            now,
        )
        assertEquals(51, fused.weatherCode)
        assertEquals(38, fused.cloudCover)
        assertEquals(model.cloudCoverLow, fused.cloudCoverLow)
        assertEquals(model.cloudCoverMid, fused.cloudCoverMid)
        assertEquals(model.cloudCoverHigh, fused.cloudCoverHigh)
        assertEquals(model.precipitation, fused.precipitation, 0.0)
    }

    @Test
    fun stationTemperatureHumidityAndWindDoNotRecalculateModelApparentTemperature() {
        val now = Instant.parse("2026-08-29T09:10:00Z")
        val model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 0).copy(feelsLike = 19.0)
        for (temperature in listOf(model.temperature, 12.0)) {
            val station = observation(50.001, 14.0, now.minusSeconds(300)).copy(
                temperature = temperature, humidity = 90, windSpeed = 36.0, windDirection = 180.0,
            )
            val fused = fuseCurrentConditions(
                model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), listOf(station), now,
            )
            assertEquals(temperature, fused.temperature, 0.0)
            assertEquals(90, fused.humidity)
            assertEquals(36.0, fused.windSpeed, 1e-9)
            assertEquals(model.feelsLike, fused.feelsLike, 0.0)
            val parsed = WeatherParser.parseForecast(
                applyCurrentConditionsToForecastJson(FORECAST_WITH_CLOUD_LAYERS, fused), 0L,
            )
            assertEquals(model.feelsLike, parsed.current.feelsLike, 0.0)
        }
    }

    @Test
    fun correctedJsonPreservesTheWholeHourlyForecast() {
        val corrected = JSONObject(
            applyCurrentConditionsToForecastJson(
                """{"current":{"time":"2026-08-29T11:15"},"hourly":{"time":["2026-08-29T10:00","2026-08-29T11:00"],"temperature_2m":[18,19],"relative_humidity_2m":[70,65],"precipitation":[0.2,0.3],"rain":[0.2,0.3],"weather_code":[61,61],"wind_speed_10m":[5,6],"wind_direction_10m":[180,190]}}""",
                current(weatherCode = 0, precipitation = 0.0, cloudCover = 5),
            ),
        )

        assertEquals(0, corrected.getJSONObject("current").getInt("weather_code"))
        assertEquals(0.0, corrected.getJSONObject("current").getDouble("precipitation"), 0.0)
        val hourly = corrected.getJSONObject("hourly")
        assertEquals(61, hourly.getJSONArray("weather_code").getInt(0))
        assertEquals(61, hourly.getJSONArray("weather_code").getInt(1))
        assertEquals(19.0, hourly.getJSONArray("temperature_2m").getDouble(1), 0.0)
        assertEquals(0.3, hourly.getJSONArray("precipitation").getDouble(1), 0.0)
        assertEquals(0.3, hourly.getJSONArray("rain").getDouble(1), 0.0)
    }

    @Test
    fun nearbyTraceRainIsNotDilutedByDryStations() {
        val now = Instant.parse("2026-09-07T13:18:00Z")
        val model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 0)
        val fused = fuseCurrentConditions(
            model,
            CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
            listOf(
                observation(50.001, 14.0, now.minusSeconds(300), precipitation = 0.1),
                observation(50.01, 14.0, now.minusSeconds(300), precipitation = 0.0),
            ),
            now,
        )
        assertEquals(61, fused.weatherCode)
        assertEquals(model.precipitation, fused.precipitation, 0.0)
    }

    @Test
    fun explicitNearbyDrizzleReportOverridesClearWithoutInventingAmount() {
        val now = Instant.parse("2026-09-07T13:18:00Z")
        val model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 0)
        val report = observation(50.001, 14.0, now.minusSeconds(300)).copy(
            precipitation = null, weatherCode = 51,
        )
        val fused = fuseCurrentConditions(
            model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), listOf(report), now,
        )
        assertEquals(51, fused.weatherCode)
        assertEquals(0.0, fused.precipitation, 0.0)
        val remote = fuseCurrentConditions(
            model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), listOf(report.copy(latitude = 50.3)), now,
        )
        assertEquals(0, remote.weatherCode)
    }

    @Test
    fun gaugeWaterEquivalentBelowFreezingDoesNotInventLiquidRain() {
        val now = Instant.parse("2026-09-07T13:18:00Z")
        val fused = fuseCurrentConditions(
            current(weatherCode = 0, precipitation = 0.0, cloudCover = 0),
            CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
            listOf(observation(50.001, 14.0, now.minusSeconds(300), precipitation = 0.1).copy(temperature = -3.0)),
            now,
        )
        assertEquals(0, fused.weatherCode)
        assertEquals(-3.0, fused.temperature, 0.0)
    }

    @Test
    fun oldOrRemoteRainDoesNotOverrideCurrentCondition() {
        val now = Instant.parse("2026-09-07T13:18:00Z")
        val model = current(weatherCode = 0, precipitation = 0.0, cloudCover = 0)
        for (station in listOf(
            observation(50.001, 14.0, now.minusSeconds(3_600), precipitation = 0.3),
            observation(50.3, 14.0, now.minusSeconds(300), precipitation = 0.3),
        )) {
            val fused = fuseCurrentConditions(
                model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), listOf(station), now,
            )
            assertEquals(0, fused.weatherCode)
        }
    }

    @Test
    fun explicitFreshWeatherReportOverridesModeledPhenomenaWithoutInventingAmounts() {
        val now = Instant.parse("2026-09-07T13:18:00Z")
        val location = CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0)
        val report = observation(50.001, 14.0, now.minusSeconds(300)).copy(
            precipitation = null, cloudCoverPercent = 75,
        )
        for ((modelCode, reportedCode) in listOf(61 to 95, 45 to 73, 73 to 66, 95 to 96)) {
            val model = current(weatherCode = modelCode, precipitation = 0.4, cloudCover = 100)
            val fused = fuseCurrentConditions(model, location, listOf(report.copy(weatherCode = reportedCode)), now)

            assertEquals(reportedCode, fused.weatherCode)
            assertEquals(75, fused.cloudCover)
            assertEquals(model.precipitation, fused.precipitation, 0.0)
        }
    }

    @Test
    fun lowerAirportWeatherReportDoesNotDowngradeModeledStormOrHail() {
        val now = Instant.parse("2026-10-12T10:35:00Z")
        val location = CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0)
        for ((modelCode, reportedCode) in listOf(95 to 45, 96 to 61, 99 to 95)) {
            val model = current(modelCode, 0.4, 100)
            val report = observation(50.001, 14.0, now.minusSeconds(300)).copy(
                precipitation = null, weatherCode = reportedCode,
            )
            val fused = fuseCurrentConditions(model, location, listOf(report), now)
            assertEquals(modelCode, fused.weatherCode)
            assertEquals(model.precipitation, fused.precipitation, 0.0)
        }
    }

    @Test
    fun cloudOnlyStaleOrDistantReportsDoNotReplaceModeledPhenomena() {
        val now = Instant.parse("2026-09-07T13:18:00Z")
        val location = CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0)
        val report = observation(50.001, 14.0, now.minusSeconds(300)).copy(
            precipitation = null, cloudCoverPercent = 0, weatherCode = 95,
        )
        for (modelCode in listOf(45, 61, 73, 95)) {
            val model = current(weatherCode = modelCode, precipitation = 0.4, cloudCover = 100)
            for (unsupported in listOf(
                report.copy(weatherCode = null),
                report.copy(time = now.minusSeconds(1_801)),
                report.copy(latitude = 50.1),
            )) {
                val fused = fuseCurrentConditions(model, location, listOf(unsupported), now)
                assertEquals(modelCode, fused.weatherCode)
                assertEquals(model.precipitation, fused.precipitation, 0.0)
            }
        }
    }

    @Test
    fun partialManualCloudReportsDoNotCapModeledTotalOrRemoveUnchangedLayers() {
        val now = Instant.parse("2026-10-12T10:35:00Z")
        for (cover in listOf("FEW", "SCT", "BKN")) {
            val report = JSONObject(METAR_CLOUD_REPORT).put("cover", cover)
            val observation = parseMetarCurrentConditions("[$report]").single()
            for (cloudCover in listOf(85, 100)) {
                val model = current(3, 0.0, cloudCover).copy(
                    cloudCoverLow = 40, cloudCoverMid = 60, cloudCoverHigh = 85,
                )
                val fused = fuseCurrentConditions(
                    model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), listOf(observation), now,
                )
                assertEquals(cover, cloudCover, fused.cloudCover)
                assertEquals(cover, 3, fused.weatherCode)
                assertEquals(model.cloudCoverLow, fused.cloudCoverLow)
                assertEquals(model.cloudCoverMid, fused.cloudCoverMid)
                assertEquals(model.cloudCoverHigh, fused.cloudCoverHigh)
            }
        }
    }

    @Test
    fun manualBrokenCloudCategorySetsOnlyALowerBoundOnTotalCloudAmount() {
        val now = Instant.parse("2026-10-12T10:35:00Z")
        val report = JSONObject(METAR_CLOUD_REPORT).put("cover", "BKN")
        val observation = parseMetarCurrentConditions("[$report]").single()
        for ((modelCover, expected) in listOf(0 to (63 to 2), 100 to (100 to 3))) {
            val fused = fuseCurrentConditions(
                current(weatherCode = 0, precipitation = 0.0, cloudCover = modelCover),
                CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0),
                listOf(observation),
                now,
            )
            assertEquals(expected.first, fused.cloudCover)
            assertEquals(expected.second, fused.weatherCode)
        }
    }

    @Test
    fun automatedPartialCloudsRaiseCloudFloorWithoutErasingModeledOvercast() {
        val now = Instant.parse("2026-10-12T10:35:00Z")
        for ((cover, lowerBound) in listOf("FEW" to 13, "SCT" to 38, "BKN" to 63)) {
            val report = JSONObject(METAR_CLOUD_REPORT).put("cover", cover)
                .put("rawOb", "METAR LKPR 121030Z AUTO 29008KT 9999 $cover 12/05 Q1021")
            val observation = parseMetarCurrentConditions("[$report]").single()
            for (modelCover in listOf(0, 85)) {
                val model = current(if (modelCover == 0) 0 else 3, 0.0, modelCover).copy(cloudCoverHigh = modelCover)
                val fused = fuseCurrentConditions(
                    model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), listOf(observation), now,
                )
                assertEquals(maxOf(modelCover, lowerBound), fused.cloudCover)
                if (modelCover == 85) {
                    assertEquals(3, fused.weatherCode)
                    assertEquals(85, fused.cloudCoverHigh)
                } else {
                    assertEquals(null, fused.cloudCoverHigh)
                }
            }
        }
    }

    @Test
    fun ambiguousNearestMetarDoesNotBorrowFartherPresentWeatherOrClouds() {
        val now = Instant.parse("2026-10-12T10:35:00Z")
        val model = current(2, 0.0, 50).copy(cloudCoverHigh = 20)
        val farther = observation(50.01, 14.0, now.minusSeconds(300)).copy(
            precipitation = null, weatherCode = 95, cloudCoverPercent = 100,
        )
        for (encoded in listOf("", "VCTS", "-RASN", "UP")) {
            val report = JSONObject(METAR_CLOUD_REPORT).put("lat", 50.001)
                .put("cover", "CAVOK").put("wxString", encoded)
            val nearest = parseMetarCurrentConditions("[$report]").single()
            for (reports in listOf(listOf(nearest, farther), listOf(farther, nearest))) {
                val fused = fuseCurrentConditions(
                    model, CzechLocation("Point", REGION_PRAGUE, 50.0, 14.0), reports, now,
                )
                assertEquals(encoded, model.weatherCode, fused.weatherCode)
                assertEquals(encoded, model.cloudCover, fused.cloudCover)
                assertEquals(model.cloudCoverHigh, fused.cloudCoverHigh)
            }
        }
    }

    @Test
    fun cloudCoverRangesAreBoundedAndContainTheNominalMeasurement() {
        val report = observation(50.0, 14.0, Instant.EPOCH)
        for ((nominal, range) in listOf(
            75 to (88..63), 75 to (-1..75), 75 to (75..101), 75 to (63..74), null to (63..88),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                report.copy(cloudCoverPercent = nominal, cloudCoverRange = range)
            }
        }
    }

    private fun current(
        weatherCode: Int,
        precipitation: Double,
        cloudCover: Int,
    ) = CurrentWeather(
        time = "2026-08-29T11:10",
        temperature = 22.0,
        feelsLike = 22.0,
        humidity = 50,
        precipitation = precipitation,
        weatherCode = weatherCode,
        cloudCover = cloudCover,
        pressure = 1_015.0,
        windSpeed = 5.0,
        windDirection = 270,
        windGusts = 8.0,
        isDay = true,
    )

    private fun observation(
        latitude: Double,
        longitude: Double,
        time: Instant,
        precipitation: Double = 0.0,
        sunshineSeconds: Double? = null,
    ) = CurrentStationObservation(
        stationId = "$latitude,$longitude",
        latitude = latitude,
        longitude = longitude,
        time = time,
        temperature = 20.0,
        humidity = 60,
        precipitation = precipitation,
        windSpeed = null,
        windDirection = null,
        sunshineSeconds = sunshineSeconds,
    )

    companion object {
        private const val METAR_CLOUD_REPORT =
            """{"icaoId":"LKPR","obsTime":1791801000,"temp":12,"qcField":16,"lat":50.0,"lon":14.0}"""
        private val FORECAST_WITH_CLOUD_LAYERS = """
            {
              "timezone":"Europe/Prague","utc_offset_seconds":7200,
              "current":{
                "time":"2026-08-29T11:10","temperature_2m":22,"apparent_temperature":22,
                "relative_humidity_2m":50,"precipitation":0,"weather_code":3,"cloud_cover":100,
                "cloud_cover_low":80,"cloud_cover_mid":65,"cloud_cover_high":40,
                "pressure_msl":1015,"wind_speed_10m":5,"wind_direction_10m":270,"wind_gusts_10m":8,"is_day":1
              },
              "hourly":{
                "time":["2026-08-29T11:00"],"temperature_2m":[19],"relative_humidity_2m":[60],
                "precipitation_probability":[3],"precipitation":[0.3],"weather_code":[61],
                "cloud_cover_low":[80],"cloud_cover_mid":[65],"cloud_cover_high":[40],
                "pressure_msl":[1015],"wind_speed_10m":[5],"wind_direction_10m":[270],"is_day":[1]
              },
              "daily":{
                "time":["2026-08-29"],"weather_code":[61],"temperature_2m_max":[22],"temperature_2m_min":[10],
                "sunrise":["2026-08-29T06:00"],"sunset":["2026-08-29T20:00"],
                "precipitation_sum":[0.3],"precipitation_probability_max":[3],"wind_speed_10m_max":[8]
              }
            }
        """.trimIndent()
    }
}
