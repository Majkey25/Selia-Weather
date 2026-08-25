package cz.majkey.pocasicesko.locale

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class SupportedLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    CZECH("cs"),
    GERMAN("de"),
    SPANISH("es"),
    FRENCH("fr"),
}

internal fun normalizeLanguageTag(tag: String?): String {
    val language = tag.orEmpty().substringBefore('-').lowercase(Locale.ROOT)
    return SupportedLanguage.entries.firstOrNull { it.tag == language }?.tag.orEmpty()
}

internal fun effectiveLanguageTag(selectedTag: String?, systemTag: String?): String =
    normalizeLanguageTag(selectedTag).ifBlank {
        normalizeLanguageTag(systemTag).ifBlank { SupportedLanguage.ENGLISH.tag }
    }

object AppLocale {
    private const val PREFERENCES = "app_locale"
    private const val LANGUAGE_TAG = "language_tag"

    fun wrap(context: Context): Context = localized(context)

    fun set(activity: Activity, tag: String) {
        val languageTag = normalizeLanguageTag(tag)
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_TAG, languageTag)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(languageTag)
        } else {
            activity.recreate()
        }
    }

    fun localized(context: Context): Context {
        val languageTag = normalizeLanguageTag(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(LANGUAGE_TAG, null),
        )
        if (languageTag.isEmpty()) return context

        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return context.createConfigurationContext(configuration)
    }

    fun languageTag(context: Context): String = effectiveLanguageTag(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE_TAG, null),
        context.resources.configuration.locales[0].toLanguageTag(),
    )

    fun locale(context: Context): Locale = Locale.forLanguageTag(languageTag(context))
}
