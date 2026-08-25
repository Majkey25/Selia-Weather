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
    val language = tag.orEmpty().substringBefore(',').substringBefore('-').lowercase(Locale.ROOT)
    return SupportedLanguage.entries.firstOrNull { it.tag == language }?.tag.orEmpty()
}

internal fun effectiveLanguageTag(selectedTag: String?, systemTag: String?): String =
    normalizeLanguageTag(selectedTag).ifBlank {
        normalizeLanguageTag(systemTag).ifBlank { SupportedLanguage.ENGLISH.tag }
    }

internal fun selectedLanguageTag(
    sdkInt: Int,
    preferenceTag: String?,
    frameworkTags: String?,
): String = normalizeLanguageTag(
    if (sdkInt >= Build.VERSION_CODES.TIRAMISU) frameworkTags else preferenceTag,
)

object AppLocale {
    private const val PREFERENCES = "app_locale"
    private const val LANGUAGE_TAG = "language_tag"

    fun wrap(context: Context): Context = localized(context)

    fun set(activity: Activity, tag: String) {
        val languageTag = normalizeLanguageTag(tag)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(LANGUAGE_TAG)
                .apply()
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(languageTag)
        } else {
            activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(LANGUAGE_TAG, languageTag)
                .apply()
            activity.recreate()
        }
    }

    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context

        val languageTag = selectedTag(context)
        if (languageTag.isEmpty()) return context

        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        return context.createConfigurationContext(configuration)
    }

    fun selectedTag(context: Context): String = selectedLanguageTag(
        Build.VERSION.SDK_INT,
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LANGUAGE_TAG, null),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else {
            null
        },
    )

    fun languageTag(context: Context): String = effectiveLanguageTag(
        selectedTag(context),
        context.resources.configuration.locales[0].toLanguageTag(),
    )

    fun locale(context: Context): Locale = Locale.forLanguageTag(languageTag(context))
}
