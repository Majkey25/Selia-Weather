package cz.majkey.pocasicesko.units

import android.content.Context
import androidx.core.content.edit
import cz.majkey.pocasicesko.data.WeatherRepository
import java.util.Locale
import kotlin.math.roundToInt

enum class MeasurementSystem {
    METRIC,
    IMPERIAL,
}

internal fun measurementSystem(value: String?): MeasurementSystem = when (value) {
    MeasurementSystem.IMPERIAL.name, "US" -> MeasurementSystem.IMPERIAL
    else -> MeasurementSystem.METRIC
}

object MeasurementUnits {
    fun current(context: Context): MeasurementSystem = measurementSystem(
        context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MEASUREMENT_SYSTEM, null),
    )

    fun save(context: Context, system: MeasurementSystem) {
        context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_MEASUREMENT_SYSTEM, system.name) }
    }

    private const val KEY_MEASUREMENT_SYSTEM = "measurement_system"
}

class WeatherUnitFormatter(
    private val system: MeasurementSystem,
    private val locale: Locale,
) {
    fun temperature(celsius: Double): String = "${temperatureValue(celsius).roundToInt()}°"

    fun windSpeed(kilometresPerHour: Double): String = when (system) {
        MeasurementSystem.METRIC -> "${kilometresPerHour.roundToInt()} km/h"
        MeasurementSystem.IMPERIAL -> "${(kilometresPerHour * KILOMETRES_TO_MILES).roundToInt()} mph"
    }

    fun precipitation(millimetres: Double): String = when (system) {
        MeasurementSystem.METRIC -> String.format(locale, "%.1f mm", millimetres)
        MeasurementSystem.IMPERIAL -> String.format(locale, "%.2f in", millimetres / MILLIMETRES_PER_INCH)
    }

    fun snowfall(centimetres: Double): String = when (system) {
        MeasurementSystem.METRIC -> String.format(locale, "%.1f cm", centimetres)
        MeasurementSystem.IMPERIAL -> String.format(locale, "%.2f in", centimetres / CENTIMETRES_PER_INCH)
    }

    fun pressure(hectopascals: Double): String = when (system) {
        MeasurementSystem.METRIC -> "${hectopascals.roundToInt()} hPa"
        MeasurementSystem.IMPERIAL -> String.format(locale, "%.2f inHg", hectopascals * HECTOPASCALS_TO_INHG)
    }

    fun distance(kilometres: Double): String = when (system) {
        MeasurementSystem.METRIC -> String.format(locale, "%.1f km", kilometres)
        MeasurementSystem.IMPERIAL -> String.format(locale, "%.1f mi", kilometres * KILOMETRES_TO_MILES)
    }

    fun visibility(metres: Double): String = distance(metres / 1_000.0)

    private fun temperatureValue(celsius: Double): Double = when (system) {
        MeasurementSystem.METRIC -> celsius
        MeasurementSystem.IMPERIAL -> celsius * 9.0 / 5.0 + 32.0
    }

    private companion object {
        const val KILOMETRES_TO_MILES = 0.621371192237334
        const val CENTIMETRES_PER_INCH = 2.54
        const val MILLIMETRES_PER_INCH = 25.4
        const val HECTOPASCALS_TO_INHG = 0.029529983071445
    }
}
