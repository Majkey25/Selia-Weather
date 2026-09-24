package cz.majkey.pocasicesko.ui

import android.content.Context
import androidx.core.content.edit
import cz.majkey.pocasicesko.data.WeatherRepository

enum class AppAppearance {
    WEATHER,
    OCEAN,
    SUNSET,
    FOREST,
    MATERIAL,
    MINIMAL,
}

internal fun appAppearance(value: String?): AppAppearance =
    AppAppearance.entries.firstOrNull { it.name == value } ?: AppAppearance.WEATHER

object AppearanceSettings {
    fun load(context: Context): AppAppearance = appAppearance(
        context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_APPEARANCE, null),
    )

    fun save(context: Context, appearance: AppAppearance) {
        context.getSharedPreferences(WeatherRepository.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_APPEARANCE, appearance.name) }
    }

    private const val KEY_APPEARANCE = "app_appearance"
}
