package cz.majkey.pocasicesko.ui

import androidx.annotation.StringRes
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherCondition
import cz.majkey.pocasicesko.data.WeatherConditionKey

@StringRes
fun WeatherCondition.labelResource(): Int = when (weatherCode) {
    51 -> R.string.condition_light_drizzle
    53 -> R.string.condition_moderate_drizzle
    55 -> R.string.condition_dense_drizzle
    56 -> R.string.condition_light_freezing_drizzle
    57 -> R.string.condition_dense_freezing_drizzle
    61 -> R.string.condition_light_rain
    63 -> R.string.condition_moderate_rain
    65 -> R.string.condition_heavy_rain
    66 -> R.string.condition_light_freezing_rain
    67 -> R.string.condition_heavy_freezing_rain
    80 -> R.string.condition_light_showers
    81 -> R.string.condition_moderate_showers
    82 -> R.string.condition_violent_showers
    else -> when (key) {
        WeatherConditionKey.CLEAR_DAY -> R.string.condition_clear_day
        WeatherConditionKey.CLEAR_NIGHT -> R.string.condition_clear_night
        WeatherConditionKey.MAINLY_CLEAR_DAY -> R.string.condition_mainly_clear_day
        WeatherConditionKey.MAINLY_CLEAR_NIGHT -> R.string.condition_mainly_clear_night
        WeatherConditionKey.PARTLY_CLOUDY -> R.string.condition_partly_cloudy
        WeatherConditionKey.CLOUDY -> R.string.condition_cloudy
        WeatherConditionKey.FOG -> R.string.condition_fog
        WeatherConditionKey.DRIZZLE -> R.string.condition_drizzle
        WeatherConditionKey.RAIN -> R.string.condition_rain
        WeatherConditionKey.SNOW -> R.string.condition_snow
        WeatherConditionKey.SHOWERS -> R.string.condition_showers
        WeatherConditionKey.SNOW_SHOWERS -> R.string.condition_snow_showers
        WeatherConditionKey.STORM -> R.string.condition_storm
        WeatherConditionKey.UNKNOWN -> R.string.condition_unknown
    }
}
