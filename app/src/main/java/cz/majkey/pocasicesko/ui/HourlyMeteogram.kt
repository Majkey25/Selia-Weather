package cz.majkey.pocasicesko.ui

import cz.majkey.pocasicesko.data.HourlyWeather

internal data class MeteogramHourGeometry(
    val centerX: Float,
    val temperatureY: Float,
    val precipitationHeight: Float,
    val precipitationAlpha: Float,
    val isDay: Boolean,
)

internal data class HourlyMeteogramGeometry(
    val hours: List<MeteogramHourGeometry>,
)

internal fun calculateHourlyMeteogram(
    hours: List<HourlyWeather>,
    width: Float,
    height: Float,
    columnWidth: Float,
): HourlyMeteogramGeometry {
    require(width.isFinite() && width > 0f)
    require(height.isFinite() && height > 0f)
    require(columnWidth.isFinite() && columnWidth > 0f)
    if (hours.isEmpty()) return HourlyMeteogramGeometry(emptyList())
    require(hours.all {
        it.temperature.isFinite() && it.precipitation.isFinite() && it.precipitation >= 0.0 &&
            it.precipitationProbability in 0..100
    })

    val minimumTemperature = hours.minOf(HourlyWeather::temperature)
    val temperatureRange = (hours.maxOf(HourlyWeather::temperature) - minimumTemperature).coerceAtLeast(1.0)
    val maximumPrecipitation = hours.maxOf(HourlyWeather::precipitation).coerceAtLeast(0.1)
    return HourlyMeteogramGeometry(
        hours.mapIndexed { index, hour ->
            MeteogramHourGeometry(
                centerX = columnWidth * index + columnWidth / 2f,
                temperatureY = height * (
                    0.54f -
                        ((hour.temperature - minimumTemperature) / temperatureRange).toFloat() * 0.42f
                    ),
                precipitationHeight = height * 0.30f *
                    (hour.precipitation / maximumPrecipitation).toFloat().coerceIn(0f, 1f),
                precipitationAlpha = (
                    0.30f + hour.precipitationProbability / 100f * 0.70f
                    ).coerceIn(0.30f, 1f),
                isDay = hour.isDay,
            )
        },
    )
}
