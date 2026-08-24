package cz.majkey.pocasicesko.ui

import androidx.annotation.StringRes
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.WeatherCondition
import cz.majkey.pocasicesko.data.WeatherConditionKey

@StringRes
fun WeatherCondition.labelResource(): Int = when (key) {
    WeatherConditionKey.CLEAR_DAY -> R.string.condition_clear_day
    WeatherConditionKey.CLEAR_NIGHT -> R.string.condition_clear_night
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
