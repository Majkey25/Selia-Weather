package cz.majkey.pocasicesko.notification

import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import cz.majkey.pocasicesko.MainActivity
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.LocationMode
import cz.majkey.pocasicesko.data.WeatherWarning
import cz.majkey.pocasicesko.data.WeatherWarningsResult
import cz.majkey.pocasicesko.data.WeatherWarningsStatus
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.locale.AppLocale
import java.security.MessageDigest
import java.time.Instant

internal object OfficialWarningNotifications {
    const val EXTRA_SHOW_WARNINGS = "show_official_warnings"

    @Synchronized
    fun publish(
        context: Context,
        location: CzechLocation,
        result: WeatherWarningsResult,
        requireBackgroundPermission: Boolean = false,
    ) {
        val repository = WeatherRepository(context)
        repository.publishForCurrentLocation(location, requireBackgroundPermission) {
            publishCurrentLocation(context, location, result, repository.locationMode())
        }
    }

    private fun publishCurrentLocation(
        context: Context,
        location: CzechLocation,
        result: WeatherWarningsResult,
        locationMode: LocationMode,
    ) {
        WeatherAlerts.ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        val notificationId = WeatherAlerts.NOTIFICATION_ID_OFFICIAL
        val settings = WeatherAlertSettings.load(context)
        if (!settings.backgroundAlertsAllowed || !settings.officialWarningsEnabled ||
            !WeatherAlerts.canPost(context, WeatherAlerts.CHANNEL_OFFICIAL)) {
            manager.cancel(notificationId)
            return
        }
        val preferences = context.getSharedPreferences("official_warning_delivery", Context.MODE_PRIVATE)
        val locationKey = "${location.latitude},${location.longitude}"
        val previousLocation = preferences.getString("location", null)
        val now = Instant.now()
        val scope = officialWarningScope(location, locationMode)
        val previousScope = preferences.getString("scope", null)
        if (!acceptWarningCheck(previousLocation, preferences.getLong("checked_at", Long.MIN_VALUE),
                locationKey, result.checkedAt.toEpochMilli(), now.toEpochMilli())) return
        if ((previousScope != null && previousScope != scope) ||
            (previousScope == null && locationMode == LocationMode.FIXED && previousLocation != locationKey) ||
            result.status == WeatherWarningsStatus.UNSUPPORTED) {
            manager.cancel(notificationId)
            preferences.edit { remove("fingerprint"); remove("delivered"); remove("displayed") }
        }
        preferences.edit {
            putString("location", locationKey)
            putString("scope", scope)
            putLong("checked_at", result.checkedAt.toEpochMilli())
        }
        // On provider failure, keep a previously delivered warning only until its existing timeout.
        if (result.status != WeatherWarningsStatus.AVAILABLE) return
        val active = result.warnings.filter { it.expires?.isAfter(now) == true }
        if (active.isEmpty()) {
            manager.cancel(notificationId)
            preferences.edit { remove("displayed") }
            return
        }
        val keys = active.map(::officialWarningKey).toSet()
        val previousFingerprint = preferences.getString("fingerprint", null)
        if (previousFingerprint != null && (previousFingerprint == legacyWarningFingerprint(scope, result) ||
                (locationMode == LocationMode.FOLLOW_DEVICE && previousLocation != null &&
                    previousFingerprint == legacyWarningFingerprint(previousLocation, result)))) {
            preferences.edit {
                putStringSet("delivered", rememberedWarningKeys(keys))
                putStringSet("displayed", keys)
                remove("fingerprint")
            }
            return
        }
        val delivered = preferences.getStringSet("delivered", emptySet()).orEmpty()
            .filterTo(mutableSetOf()) { (it.substringBefore('|').toLongOrNull() ?: 0L) > now.toEpochMilli() }
        val displayed = preferences.getStringSet("displayed", emptySet()).orEmpty()
        val hasNewWarning = keys.any { it !in delivered && it !in displayed }
        if (!hasNewWarning && (keys == displayed ||
                context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == notificationId })) return
        val localized = AppLocale.localized(context)
        val text = active.take(3).joinToString("\n\n") {
            listOf(it.headline.take(240), it.instruction.take(1000), it.source).filter(String::isNotBlank).joinToString("\n")
        }
        val expires = active.mapNotNull { it.expires }.minOrNull()!!.coerceAtMost(now.plusSeconds(86_400))
        val notification = NotificationCompat.Builder(localized, WeatherAlerts.CHANNEL_OFFICIAL)
            .setSmallIcon(R.drawable.ic_weather_cloud)
            .setContentTitle(localized.getString(R.string.notification_location_title,
                localized.getString(R.string.notification_official), location.name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(context, notificationId,
                Intent(context, MainActivity::class.java).putExtra(EXTRA_SHOW_WARNINGS, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setOnlyAlertOnce(!hasNewWarning)
            .setSilent(!hasNewWarning)
            .setTimeoutAfter((expires.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1))
            .build()
        try {
            manager.notify(notificationId, notification)
            preferences.edit {
                putStringSet("delivered", rememberedWarningKeys(delivered + keys))
                putStringSet("displayed", keys)
                remove("fingerprint")
            }
        } catch (_: SecurityException) {
            manager.cancel(notificationId)
        }
    }
}

internal fun acceptWarningCheck(previousLocation: String?, previousCheck: Long, location: String, check: Long, now: Long): Boolean =
    check <= now && (previousLocation != location || previousCheck > now || check > previousCheck)

internal fun officialWarningScope(location: CzechLocation, mode: LocationMode): String =
    if (mode == LocationMode.FOLLOW_DEVICE) "follow" else "${location.latitude},${location.longitude}"

internal fun officialWarningKey(warning: WeatherWarning): String {
    val fields = listOf(warning.source, warning.headline, warning.severity.name,
        warning.onset.toString(), warning.instruction)
    val content = fields.joinToString("") { value ->
        val normalized = value.trim().replace(WARNING_WHITESPACE, " ")
        "${normalized.length}:$normalized"
    }
    return "${warning.expires?.toEpochMilli() ?: 0L}|${sha256(content)}"
}

private fun rememberedWarningKeys(keys: Collection<String>): Set<String> =
    keys.sortedByDescending { it.substringBefore('|').toLong() }.take(128).toSet()

private fun legacyWarningFingerprint(locationKey: String, result: WeatherWarningsResult): String {
    val content = "$locationKey|" + result.warnings.map {
        "${it.source}|${it.headline}|${it.severity}|${it.onset}|${it.expires}|${it.description}|${it.instruction}"
    }.sorted().joinToString("\n")
    return sha256(content)
}

private fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private val WARNING_WHITESPACE = Regex("\\s+")
