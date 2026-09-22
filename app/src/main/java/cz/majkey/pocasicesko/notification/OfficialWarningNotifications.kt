package cz.majkey.pocasicesko.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cz.majkey.pocasicesko.MainActivity
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.WeatherWarningsResult
import cz.majkey.pocasicesko.data.WeatherWarningsStatus
import cz.majkey.pocasicesko.data.WeatherRepository
import cz.majkey.pocasicesko.locale.AppLocale
import java.security.MessageDigest
import java.time.Instant

internal object OfficialWarningNotifications {
    const val EXTRA_SHOW_WARNINGS = "show_official_warnings"

    @Synchronized
    fun publish(context: Context, location: CzechLocation, result: WeatherWarningsResult) {
        val selected = WeatherRepository(context).lastLocation()
        if (location.latitude != selected.latitude || location.longitude != selected.longitude) return
        WeatherAlerts.ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        val notificationId = WeatherAlerts.NOTIFICATION_ID_OFFICIAL
        if (!WeatherAlertSettings.load(context).officialWarningsEnabled ||
            !WeatherAlerts.canPost(context, WeatherAlerts.CHANNEL_OFFICIAL)) {
            manager.cancel(notificationId)
            return
        }
        val preferences = context.getSharedPreferences("official_warning_delivery", Context.MODE_PRIVATE)
        val locationKey = "${location.latitude},${location.longitude}"
        val previousLocation = preferences.getString("location", null)
        val now = Instant.now()
        if (!acceptWarningCheck(previousLocation, preferences.getLong("checked_at", Long.MIN_VALUE),
                locationKey, result.checkedAt.toEpochMilli(), now.toEpochMilli())) return
        if (previousLocation != locationKey) {
            manager.cancel(notificationId)
            preferences.edit().remove("fingerprint").apply()
        }
        preferences.edit().putString("location", locationKey).putLong("checked_at", result.checkedAt.toEpochMilli()).apply()
        // On provider failure, keep a previously delivered warning only until its existing timeout.
        if (result.status != WeatherWarningsStatus.AVAILABLE) return
        val active = result.warnings.filter { it.expires?.isAfter(now) == true }
        if (active.isEmpty()) {
            manager.cancel(notificationId)
            preferences.edit().remove("fingerprint").apply()
            return
        }
        val fingerprint = officialWarningFingerprint(location, result)
        if (preferences.getString("fingerprint", null) == fingerprint) return
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
            .setTimeoutAfter((expires.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1))
            .build()
        try {
            manager.notify(notificationId, notification)
            preferences.edit().putString("fingerprint", fingerprint).apply()
        } catch (_: SecurityException) {
            manager.cancel(notificationId)
        }
    }
}

internal fun acceptWarningCheck(previousLocation: String?, previousCheck: Long, location: String, check: Long, now: Long): Boolean =
    check <= now && (previousLocation != location || previousCheck > now || check > previousCheck)

internal fun officialWarningFingerprint(location: CzechLocation, result: WeatherWarningsResult): String {
    val content = "${location.latitude},${location.longitude}|" + result.warnings.map {
        "${it.source}|${it.headline}|${it.severity}|${it.onset}|${it.expires}|${it.description}|${it.instruction}"
    }.sorted().joinToString("\n")
    // A national CAP bulletin can get a new ID without changing the warning at this location.
    return MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
