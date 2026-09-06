package cz.majkey.pocasicesko.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelConsensusTest {
    @Test
    fun selectsLocationSpecificModelInputs() {
        val prague = WeatherRepository.modelForecastUrl(
            CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ"),
        )
        val newYork = WeatherRepository.modelForecastUrl(
            CzechLocation("New York", "New York", 40.7128, -74.006, "US"),
        )

        assertTrue(prague.contains("chmi_aladin_seamless"))
        assertTrue(prague.contains("icon_seamless"))
        assertTrue(prague.contains("ecmwf_ifs025"))
        assertTrue(prague.contains("gfs_seamless"))
        assertFalse(newYork.contains("chmi_aladin_seamless"))
        assertTrue(newYork.contains("gem_seamless"))
        assertTrue(newYork.contains("&models=${forecastApiModelsFor(
            CzechLocation("New York", "New York", 40.7128, -74.006, "US"),
        ).joinToString(",")}"))
        assertFalse(newYork.contains("kma_seamless"))
    }

    @Test
    fun blendsContinuousValuesAndDerivesConditionsLocally() {
        val result = blendModelForecast(BASE, MODELS)
        val root = JSONObject(result.json)
        val current = root.getJSONObject("current")
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")

        assertEquals(99.0, current.getDouble("temperature_2m"), 0.0)
        assertEquals(22.0, hourly.getJSONArray("temperature_2m").getDouble(0), 0.0)
        assertEquals(3, current.getInt("weather_code"))
        assertEquals(100, current.getInt("cloud_cover"))
        assertEquals(0, hourly.getJSONArray("weather_code").getInt(0))
        assertEquals(10, hourly.getJSONArray("cloud_cover").getInt(0))
        assertEquals(180, current.getInt("wind_direction_10m"))
        assertEquals(0, hourly.getJSONArray("wind_direction_10m").getInt(0))
        assertEquals(0, hourly.getJSONArray("precipitation_probability").getInt(1))
        assertEquals(61, hourly.getJSONArray("weather_code").getInt(1))
        assertEquals(22.0, daily.getJSONArray("temperature_2m_max").getDouble(0), 0.0)
        assertEquals(20.0, daily.getJSONArray("temperature_2m_min").getDouble(0), 0.0)
        assertEquals(0.2, daily.getJSONArray("precipitation_sum").getDouble(0), 0.0)
        assertEquals(180, daily.getJSONArray("wind_direction_10m_dominant").getInt(0))
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
        assertEquals(listOf("a", "b", "c"), result.contributorIds)
        assertEquals(null, result.fallbackReason)
    }

    @Test
    fun sparseOrTiedCodesDoNotInventHazardConsensus() {
        listOf(listOf(95), listOf(71, 0), listOf(45, 0), listOf(95, 95, 0, 0)).forEach { codes ->
            val models = JSONObject(MODELS).also { root ->
                val hourly = root.getJSONObject("hourly")
                listOf("a", "b", "c", "d").forEachIndexed { index, suffix ->
                    hourly.put("weather_code_$suffix", JSONArray(listOf(codes.getOrNull(index), 0)))
                }
            }

            val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

            assertEquals(0, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        }
    }

    @Test
    fun incompleteCodeCoverageKeepsProviderHazardsInsteadOfInventingRain() {
        listOf(71, 66, 56, 95, 45).forEach { providerCode ->
            val base = JSONObject(BASE).also { root ->
                root.getJSONObject("hourly").getJSONArray("weather_code").put(1, providerCode)
            }
            val models = JSONObject(MODELS).also { root ->
                root.getJSONObject("hourly").remove("weather_code_b")
                root.getJSONObject("hourly").remove("weather_code_c")
            }

            val result = JSONObject(blendModelForecast(base.toString(), models.toString()).json)

            assertEquals(providerCode, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(1))
        }
    }

    @Test
    fun majorityFreezingWeatherRetainsItsPhase() {
        listOf(66, 56).forEach { code ->
            val models = JSONObject(MODELS).also { root ->
                val hourly = root.getJSONObject("hourly")
                listOf("a", "b", "c").forEach { suffix ->
                    hourly.getJSONArray("weather_code_$suffix").put(1, code)
                }
            }

            val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

            assertEquals(code, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(1))
        }
    }

    @Test
    fun rainConditionUsesBlendedAmountRatherThanTieOfWetModelVotes() {
        listOf(0.1 to 3, 0.4 to 61).forEach { (wetAmount, expectedCode) ->
            val models = JSONObject(MODELS).also { root ->
                val hourly = root.getJSONObject("hourly")
                listOf("a", "b", "c", "d").forEachIndexed { index, suffix ->
                    hourly.put("precipitation_$suffix", JSONArray(listOf(0.0, if (index < 2) 0.0 else wetAmount)))
                    hourly.put("cloud_cover_$suffix", JSONArray(listOf(10, 100)))
                    hourly.put("weather_code_$suffix", JSONArray(listOf(0, 3)))
                }
            }

            val result = JSONObject(blendModelForecast(BASE, models.toString()).json)
            val hourly = result.getJSONObject("hourly")

            assertEquals(wetAmount / 2, hourly.getJSONArray("precipitation").getDouble(1), 0.0)
            assertEquals(expectedCode, hourly.getJSONArray("weather_code").getInt(1))
            assertEquals(0, hourly.getJSONArray("precipitation_probability").getInt(1))
        }
    }

    @Test
    fun preservesCurrentIntervalAggregatesWhileBlendingHourlyTotals() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("current")
                .put("interval", 900).put("precipitation", 0.1).put("rain", 0.05)
                .put("snowfall", 0.02).put("wind_gusts_10m", 8.0)
            root.getJSONObject("hourly")
                .put("rain", JSONArray(listOf(0.0, 0.0)))
                .put("snowfall", JSONArray(listOf(0.0, 0.0)))
        }
        val models = JSONObject(MODELS).also { root ->
            listOf("a", "b", "c").forEach { suffix ->
                val hourly = root.getJSONObject("hourly")
                listOf("precipitation", "rain", "snowfall").forEach { field ->
                    hourly.put("${field}_$suffix", JSONArray(listOf(1.2, 1.2)))
                }
            }
        }

        val result = JSONObject(blendModelForecast(base.toString(), models.toString()).json)
        val current = result.getJSONObject("current")

        assertEquals(900, current.getInt("interval"))
        assertEquals(0.1, current.getDouble("precipitation"), 0.0)
        assertEquals(0.05, current.getDouble("rain"), 0.0)
        assertEquals(0.02, current.getDouble("snowfall"), 0.0)
        assertEquals(8.0, current.getDouble("wind_gusts_10m"), 0.0)
        assertEquals(1.2, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(0), 0.0)
        assertEquals(16.0, result.getJSONObject("hourly").getJSONArray("wind_gusts_10m").getDouble(0), 0.0)
    }

    @Test
    fun preservesProviderDailyTotalWhenHourlyAmountsAreIncomplete() {
        listOf("precipitation", "rain", "snowfall").forEach { field ->
            val models = JSONObject(MODELS).also { root ->
                listOf("a", "b", "c").forEach { suffix ->
                    root.getJSONObject("hourly").remove("${field}_$suffix")
                }
            }.toString()
            listOf(
                "[null,null]" to 4.2,
                "[0.2,null]" to 4.2,
                "[0.2,0.3]" to 0.5,
            ).forEach { (amounts, expected) ->
                val base = JSONObject(BASE).also { root ->
                    root.getJSONObject("hourly").put(field, JSONArray(amounts))
                    root.getJSONObject("daily").put("${field}_sum", JSONArray(listOf(4.2)))
                }

                val result = JSONObject(blendModelForecast(base.toString(), models).json)

                assertEquals(
                    expected,
                    result.getJSONObject("daily").getJSONArray("${field}_sum").getDouble(0),
                    0.0001,
                )
            }
        }
    }

    @Test
    fun precedingHourRainDoesNotOverwriteCurrentClearSky() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("current")
                .put("interval", 900).put("weather_code", 0).put("precipitation", 0.0)
                .put("cloud_cover", 0).put("cloud_cover_low", 0)
                .put("cloud_cover_mid", 0).put("cloud_cover_high", 0)
                .put("temperature_2m", 18.2).put("relative_humidity_2m", 65)
        }
        val models = JSONObject(MODELS).also { root ->
            val hourly = root.getJSONObject("hourly")
            listOf("a", "b", "c").forEach { suffix ->
                hourly.getJSONArray("precipitation_$suffix").put(0, 1.2)
                hourly.getJSONArray("weather_code_$suffix").put(0, 61)
                listOf("cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high")
                    .forEach { field -> hourly.put("${field}_$suffix", JSONArray(listOf(100, 100))) }
            }
        }

        val result = JSONObject(blendModelForecast(base.toString(), models.toString()).json)
        val current = result.getJSONObject("current")

        assertEquals("2026-08-29T19:15", current.getString("time"))
        assertEquals(900, current.getInt("interval"))
        assertEquals(0, current.getInt("weather_code"))
        assertEquals(0.0, current.getDouble("precipitation"), 0.0)
        assertEquals(18.2, current.getDouble("temperature_2m"), 0.0)
        assertEquals(65, current.getInt("relative_humidity_2m"))
        assertEquals(5.0, current.getDouble("wind_speed_10m"), 0.0)
        assertEquals(180, current.getInt("wind_direction_10m"))
        listOf("cloud_cover", "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high")
            .forEach { field -> assertEquals(0, current.getInt(field)) }
        assertEquals(61, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        assertEquals(1.2, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(0), 0.0)
    }

    @Test
    fun exactHourlyTimestampStillUsesInstantaneousModelBlend() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("current").put("time", "2026-08-29T19:00")
        }

        val current = JSONObject(blendModelForecast(base.toString(), MODELS).json)
            .getJSONObject("current")

        assertEquals(22.0, current.getDouble("temperature_2m"), 0.0)
        assertEquals(0, current.getInt("wind_direction_10m"))
        assertEquals(1014.0, current.getDouble("pressure_msl"), 0.0)
        assertEquals(3, current.getInt("weather_code"))
        assertEquals(100, current.getInt("cloud_cover"))
    }

    @Test
    fun preservesProviderProbabilityRegardlessOfDeterministicModelAgreement() {
        val base = JSONObject(BASE).also { root ->
            root.getJSONObject("hourly").getJSONArray("precipitation_probability")
                .put(0, 35).put(1, 45)
        }.toString()
        listOf(0.0, 0.2, JSONObject.NULL).forEach { precipitation ->
            val models = JSONObject(MODELS).also { root ->
                listOf("a", "b", "c").forEach { suffix ->
                    root.getJSONObject("hourly").getJSONArray("precipitation_$suffix")
                        .put(0, precipitation).put(1, precipitation)
                }
            }

            val result = JSONObject(blendModelForecast(base, models.toString()).json)
            val probabilities = result.getJSONObject("hourly").getJSONArray("precipitation_probability")

            assertEquals(35, probabilities.getInt(0))
            assertEquals(45, probabilities.getInt(1))
            assertEquals(45, result.getJSONObject("daily").getJSONArray("precipitation_probability_max").getInt(0))
        }
    }

    @Test
    fun regionalBlendWithoutVerifiedCalibrationKeepsDiagnosticProvenance() {
        val result = blendModelForecast(BASE, MODELS, PRAGUE, calibration = null)

        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
        assertEquals(listOf("a", "b", "c"), result.contributorIds)
        assertTrue(result.appliedWeights.isEmpty())
        assertEquals(null, result.truthClass)
        assertEquals(null, result.artifactVersion)
        assertEquals(null, result.artifactGeneratedAtEpochSeconds)
    }

    @Test
    fun liveSeriesWithoutIssuedRunTimesCannotUseLearnedWeights() {
        val artifact = parseCalibrationArtifact(
            CALIBRATION,
            Instant.parse("2026-08-29T19:00:00Z").epochSecond,
        )

        val result = blendModelForecast(BASE, MODELS, PRAGUE, artifact)
        val root = JSONObject(result.json)

        assertEquals(22.0, root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0001)
        assertEquals(99.0, root.getJSONObject("current").getDouble("temperature_2m"), 0.0)
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
        assertTrue(result.appliedWeights.isEmpty())
    }

    @Test
    fun appliesIssuedValuesAtTheRequestedLocationUsingActualRunLead() {
        val result = issuedBlend(issuedValues())
        val hourly = JSONObject(result.json).getJSONObject("hourly")

        assertEquals(21.2, hourly.getJSONArray("temperature_2m").getDouble(0), 1e-9)
        assertEquals(20.0, hourly.getJSONArray("temperature_2m").getDouble(1), 1e-9)
        assertEquals(ForecastCalculationMode.CALIBRATED, result.mode)
        assertEquals(mapOf("a" to 0.4, "b" to 0.6), result.appliedWeights)
        assertEquals(CalibrationTruthClass.STATION, result.truthClass)
        assertEquals("2026-08-29T19:00", result.calibrationAppliedAt)
        assertEquals("temperature_2m", result.calibrationVariable)
        assertEquals(1, result.calibratedValueCount)
    }

    @Test
    fun reportsFutureOnlyCalibrationWithoutClaimingItChangedCurrentConditions() {
        val result = issuedBlend(issuedValues().map { it.copy(validTime = it.validTime.plusSeconds(3600)) })
        assertEquals(ForecastCalculationMode.CALIBRATED, result.mode)
        assertEquals("2026-08-29T20:00", result.calibrationAppliedAt)
        assertEquals(1, result.calibratedValueCount)
        val root = JSONObject(result.json)
        assertEquals(99.0, root.getJSONObject("current").getDouble("temperature_2m"), 0.0)
        assertEquals(22.0, root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0)
        assertEquals(21.2, root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(1), 1e-9)
    }

    @Test
    fun rejectsStaleFutureWrongLocationUnitAndOutOfLeadIssuedValues() {
        val values = issuedValues()
        val invalid = listOf(
            values.map { it.copy(runTime = Instant.parse("2026-08-28T00:00:00Z")) },
            values.map { it.copy(runTime = Instant.parse("2026-08-29T18:00:00Z")) },
            values.map { it.copy(latitude = 51.0) },
            values.map { it.copy(unit = "K") },
            values.take(1),
        )
        invalid.forEach { rows ->
            val result = issuedBlend(rows)
            assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
            assertEquals(22.0, JSONObject(result.json).getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0)
        }
        val artifact = JSONObject(CALIBRATION).also {
            it.getJSONArray("segments").getJSONObject(0).getJSONObject("selector")
                .put("minimum_lead_hours", 0).put("maximum_lead_hours", 1)
        }
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, issuedBlend(values, artifact.toString()).mode)
    }

    @Test
    fun doesNotApplyExpiredWeights() {
        val expired = JSONObject(CALIBRATION).put("expires_at", "2026-08-29T17:00:00Z")
        val parsedBeforeExpiry = parseCalibrationArtifact(expired.toString(), Instant.parse("2026-08-29T16:00:00Z").epochSecond)
        val result = blendModelForecast(BASE, MODELS, PRAGUE, parsedBeforeExpiry, issuedValues(), NOW)
        assertEquals(ForecastCalculationMode.DIAGNOSTIC_MEDIAN, result.mode)
    }

    @Test
    fun doesNotGuessUtcForDaylightSavingOverlapOrGap() {
        listOf("2026-03-29" to "01", "2026-10-25" to "00").forEach { (date, utcHour) ->
            val times = JSONArray(listOf("${date}T02:00", "${date}T03:00"))
            val base = JSONObject(BASE).also {
                it.getJSONObject("current").put("time", "${date}T02:15")
                it.getJSONObject("hourly").put("time", times)
            }
            val models = JSONObject(MODELS).also { it.getJSONObject("hourly").put("time", times) }
            val validTime = Instant.parse("${date}T${utcHour}:00:00Z")
            val now = validTime.plusSeconds(900)
            val artifact = JSONObject(CALIBRATION).also {
                it.put("generated_at", validTime.minusSeconds(3600).toString())
                it.put("expires_at", now.plusSeconds(86400).toString())
                it.getJSONArray("segments").getJSONObject(0).getJSONObject("selector")
                    .put("months", JSONArray(listOf(date.substring(5, 7).toInt())))
            }
            val values = issuedValues().map { it.copy(runTime = validTime.minusSeconds(3600), validTime = validTime) }
            val result = blendModelForecast(base.toString(), models.toString(), PRAGUE,
                parseCalibrationArtifact(artifact.toString(), now.epochSecond), values, now)
            assertEquals(22.0, JSONObject(result.json).getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0)
            // In spring, the next real 03:00 hour legitimately matches 01:00 UTC.
            assertEquals(if (date == "2026-03-29") "${date}T03:00" else null, result.calibrationAppliedAt)
        }
    }

    @Test
    fun usesResponseUtcOffsetInsteadOfPotentiallyOutdatedDeviceTimezoneRules() {
        val base = JSONObject(BASE).put("timezone", "UTC").put("utc_offset_seconds", 7200)
        val result = blendModelForecast(base.toString(), MODELS, PRAGUE,
            parseCalibrationArtifact(CALIBRATION, NOW.epochSecond), issuedValues(), NOW)
        assertEquals(21.2, JSONObject(result.json).getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 1e-9)
        assertEquals(1, result.calibratedValueCount)
    }

    @Test
    fun neverUsesAnIntervalTotalAsHourlyCalibratedRain() {
        val artifact = CALIBRATION.replace("\"variable\":\"temperature_2m\"", "\"variable\":\"precipitation\"")
        val result = issuedBlend(issuedValues().map { it.copy(variable = "precipitation", unit = "mm") }, artifact)
        assertEquals(0, result.calibratedValueCount)
        assertEquals(0.0, JSONObject(result.json).getJSONObject("hourly").getJSONArray("precipitation").getDouble(0), 0.0)
    }

    @Test
    fun doesNotRecalibrateHoursBeforeCurrentHour() {
        val base = JSONObject(BASE).also { it.getJSONObject("current").put("time", "2026-08-29T20:15") }
        val result = blendModelForecast(base.toString(), MODELS, PRAGUE,
            parseCalibrationArtifact(CALIBRATION, NOW.epochSecond), issuedValues(), NOW)
        assertEquals(0, result.calibratedValueCount)
        assertEquals(22.0, JSONObject(result.json).getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0), 0.0)
    }

    private fun issuedBlend(values: List<StaticModelValue>, artifact: String = CALIBRATION): ModelBlendResult =
        blendModelForecast(BASE, MODELS, PRAGUE, parseCalibrationArtifact(artifact, NOW.epochSecond), values, NOW)

    private fun issuedValues(): List<StaticModelValue> = listOf("a" to 20.0, "b" to 22.0).map { (model, value) ->
        StaticModelValue(model, model, Instant.parse("2026-08-29T12:00:00Z"), Instant.parse("2026-08-29T17:00:00Z"),
            PRAGUE.latitude, PRAGUE.longitude, 250.0, "temperature_2m", value, "°C")
    }

    @Test
    fun keepsBestMatchWhenFewerThanThreeModelsArePresent() {
        val result = blendModelForecast(BASE, ONE_MODEL)
        val root = JSONObject(result.json)

        assertEquals(99.0, root.getJSONObject("current").getDouble("temperature_2m"), 0.0)
        assertEquals(
            99.0,
            root.getJSONObject("hourly").getJSONArray("temperature_2m").getDouble(0),
            0.0,
        )
        assertEquals(ForecastCalculationMode.BEST_MATCH, result.mode)
        assertEquals(listOf("a"), result.contributorIds)
        assertEquals(ForecastFallbackReason.INSUFFICIENT_CONTRIBUTORS, result.fallbackReason)
    }

    @Test
    fun derivesClearSkyWhenModelCodesAreMissing() {
        val models = JSONObject(MODELS).also { root ->
            val hourly = root.getJSONObject("hourly")
            listOf("a", "b", "c").forEach { suffix -> hourly.remove("weather_code_$suffix") }
        }

        val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

        assertEquals(0, result.getJSONObject("hourly").getJSONArray("weather_code").getInt(0))
        assertEquals(3, result.getJSONObject("current").getInt("weather_code"))
    }

    @Test
    fun rejectsNegativeProviderPrecipitationInsteadOfBlendingIt() {
        val models = JSONObject(MODELS).also { root ->
            root.getJSONObject("hourly").getJSONArray("precipitation_a").put(1, -0.2)
        }

        val result = JSONObject(blendModelForecast(BASE, models.toString()).json)

        assertEquals(0.0, result.getJSONObject("hourly").getJSONArray("precipitation").getDouble(1), 0.0)
    }

    companion object {
        private val PRAGUE = CzechLocation("Praha", REGION_PRAGUE, 50.0755, 14.4378, "CZ")
        private val NOW = Instant.parse("2026-08-29T17:15:00Z")

        private val CALIBRATION = """
            {
              "schema_version":2,
              "dataset_manifest_hash":"${"a".repeat(64)}",
              "model_contract_hash":"${"b".repeat(64)}",
              "generated_at":"2026-08-29T12:00:00Z",
              "expires_at":"2026-09-29T18:00:00Z",
              "models":[
                {"model_id":"a","maximum_run_age_hours":12,"resolution_km":10.0},
                {"model_id":"b","maximum_run_age_hours":12,"resolution_km":10.0}
              ],
              "segments":[{
                "selector":{
                  "region":"CZECHIA",
                  "variable":"temperature_2m",
                  "minimum_lead_hours":0,
                  "maximum_lead_hours":24,
                  "months":[8]
                },
                "truth_class":"station",
                "mode":"blend",
                "weights":{"a":0.4,"b":0.6},
                "minimum_source_count":2,
                "fallback_model":"a",
                "holdout":{"accepted":true,"sample_count":30}
              }]
            }
        """.trimIndent()

        private val BASE = """
            {
              "timezone":"Europe/Prague",
              "current":{"time":"2026-08-29T19:15","temperature_2m":99,"weather_code":3,"cloud_cover":100,"relative_humidity_2m":50,"precipitation":0,"pressure_msl":1010,"wind_speed_10m":5,"wind_direction_10m":180,"wind_gusts_10m":8,"apparent_temperature":99},
              "hourly":{"time":["2026-08-29T19:00","2026-08-29T20:00"],"temperature_2m":[99,99],"relative_humidity_2m":[50,50],"precipitation_probability":[0,0],"precipitation":[0,0],"weather_code":[3,3],"cloud_cover":[100,100],"pressure_msl":[1010,1010],"wind_speed_10m":[5,5],"wind_direction_10m":[180,180],"wind_gusts_10m":[8,8],"apparent_temperature":[99,99],"is_day":[1,1]},
              "daily":{"time":["2026-08-29"],"weather_code":[3],"temperature_2m_max":[99],"temperature_2m_min":[99],"precipitation_sum":[0],"precipitation_probability_max":[0],"wind_speed_10m_max":[5],"apparent_temperature_max":[99],"apparent_temperature_min":[99],"wind_gusts_10m_max":[8],"wind_direction_10m_dominant":[90]}
            }
        """.trimIndent()

        private val MODELS = """
            {
              "hourly":{
                "time":["2026-08-29T19:00","2026-08-29T20:00"],
                "temperature_2m_a":[20,18],"temperature_2m_b":[22,20],"temperature_2m_c":[40,22],"temperature_2m_d":[null,null],
                "relative_humidity_2m_a":[40,60],"relative_humidity_2m_b":[50,70],"relative_humidity_2m_c":[60,80],
                "precipitation_a":[0,0],"precipitation_b":[0,0.2],"precipitation_c":[0,0.4],
                "weather_code_a":[0,0],"weather_code_b":[0,61],"weather_code_c":[1,61],
                "cloud_cover_a":[0,100],"cloud_cover_b":[10,100],"cloud_cover_c":[20,100],
                "pressure_msl_a":[1012,1011],"pressure_msl_b":[1014,1013],"pressure_msl_c":[1016,1015],
                "wind_speed_10m_a":[10,12],"wind_speed_10m_b":[10,12],"wind_speed_10m_c":[10,12],
                "wind_direction_10m_a":[350,180],"wind_direction_10m_b":[0,180],"wind_direction_10m_c":[10,180],
                "wind_gusts_10m_a":[15,18],"wind_gusts_10m_b":[16,19],"wind_gusts_10m_c":[17,20],
                "apparent_temperature_a":[20,18],"apparent_temperature_b":[22,20],"apparent_temperature_c":[40,22]
              }
            }
        """.trimIndent()

        private val ONE_MODEL = """
            {"hourly":{"time":["2026-08-29T19:00","2026-08-29T20:00"],"temperature_2m_a":[20,18]}}
        """.trimIndent()
    }
}
