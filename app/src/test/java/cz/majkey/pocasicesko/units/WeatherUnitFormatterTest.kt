package cz.majkey.pocasicesko.units

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherUnitFormatterTest {
    @Test
    fun metricPresetKeepsCanonicalValues() {
        val units = WeatherUnitFormatter(MeasurementSystem.METRIC, Locale.US)

        assertEquals("0°", units.temperature(0.0))
        assertEquals("16 km/h", units.windSpeed(16.09344))
        assertEquals("25.4 mm", units.precipitation(25.4))
        assertEquals("2.5 cm", units.snowfall(2.54))
        assertEquals("1013 hPa", units.pressure(1013.25))
        assertEquals("1.6 km", units.distance(1.609344))
    }

    @Test
    fun imperialPresetConvertsEverySupportedDisplayQuantity() {
        val units = WeatherUnitFormatter(MeasurementSystem.IMPERIAL, Locale.US)

        assertEquals("32°", units.temperature(0.0))
        assertEquals("10 mph", units.windSpeed(16.09344))
        assertEquals("1.00 in", units.precipitation(25.4))
        assertEquals("1.00 in", units.snowfall(2.54))
        assertEquals("29.92 inHg", units.pressure(1013.25))
        assertEquals("1.0 mi", units.distance(1.609344))
    }

    @Test
    fun unknownStoredPresetFallsBackToMetric() {
        assertEquals(MeasurementSystem.METRIC, measurementSystem("broken"))
        assertEquals(MeasurementSystem.IMPERIAL, measurementSystem("IMPERIAL"))
        assertEquals(MeasurementSystem.METRIC, measurementSystem(null))
        assertEquals(MeasurementSystem.METRIC, measurementSystem("EUROPEAN"))
        assertEquals(MeasurementSystem.IMPERIAL, measurementSystem("US"))
    }
}
