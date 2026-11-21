package cz.majkey.pocasicesko

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.google.android.gms.tasks.Task
import com.google.android.play.agesignals.AgeSignalsAccessRequest
import com.google.android.play.agesignals.AgeSignalsException
import com.google.android.play.agesignals.AgeSignalsManager
import com.google.android.play.agesignals.AgeSignalsManagerFactory
import com.google.android.play.agesignals.AgeSignalsRequest
import com.google.android.play.agesignals.ApplicableAgeFeaturesRequest
import com.google.android.play.agesignals.model.AgeSignalsStatus
import com.google.android.play.agesignals.model.ApplicableAgeFeature
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

private const val NOTICE_INTERVAL_MS = 24 * 60 * 60 * 1000L
private val launchCheck = Mutex()

internal fun ComponentActivity.checkPlayAgeSignalsOnLaunch() {
    lifecycleScope.launch {
        lifecycle.withResumed { }
        launchCheck.withLock {
            if (!canRequestAgeSignal()) return@withLock
            val preferences = getSharedPreferences("play_age_signals", Context.MODE_PRIVATE)
            @Suppress("DEPRECATION")
            val installedAt = packageManager.getPackageInfo(packageName, 0).firstInstallTime
            if (preferences.getLong("checked_install", -1) == installedAt) return@withLock

            val manager = AgeSignalsManagerFactory.create(applicationContext)
            when (requestPlayAgeSignal(this@checkPlayAgeSignalsOnLaunch, manager)) {
                AgeSignalCheck.Received -> {
                    // Remember completion for this install. Never retain the bracket or Play install ID.
                    preferences.edit().putLong("checked_install", installedAt).apply()
                }
                AgeSignalCheck.VerificationRequired -> {
                    val now = System.currentTimeMillis()
                    val lastNotice = preferences.getLong("notice_shown_at", 0)
                    if (canRequestAgeSignal() && now - lastNotice !in 0 until NOTICE_INTERVAL_MS) {
                        showPlayAgeVerification()
                        preferences.edit().putLong("notice_shown_at", now).apply()
                    }
                }
                AgeSignalCheck.Unavailable -> Unit
            }
        }
    }
}

internal enum class AgeSignalCheck { Received, VerificationRequired, Unavailable }

internal suspend fun requestPlayAgeSignal(
    activity: ComponentActivity,
    manager: AgeSignalsManager,
): AgeSignalCheck {
    val features = readPlay { manager.getApplicableAgeFeatures(ApplicableAgeFeaturesRequest.builder().build()) }
    if (features == null ||
        ApplicableAgeFeature.MANDATORY_SHARING !in features.features() ||
        !activity.canRequestAgeSignal()
    ) return AgeSignalCheck.Unavailable

    val request = AgeSignalsAccessRequest.builder().setActivity(activity).build()
    val access = readPlay {
        if (activity.canRequestAgeSignal()) manager.requestAgeSignalsAccess(request) else null
    }
    return when (access?.ageSignalsStatus()) {
        AgeSignalsStatus.SHARED -> {
            val signal = readPlay { manager.checkAgeSignals(AgeSignalsRequest.builder().build()) }
            val lower = signal?.ageLower()
            val upper = signal?.ageUpper()
            if (lower != null && lower >= 0 && (upper == null || upper >= lower)) {
                AgeSignalCheck.Received
            } else {
                AgeSignalCheck.Unavailable
            }
        }
        AgeSignalsStatus.VERIFICATION_REQUIRED -> AgeSignalCheck.VerificationRequired
        else -> AgeSignalCheck.Unavailable
    }
}

private fun ComponentActivity.canRequestAgeSignal(): Boolean =
    !isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

private suspend fun <T> readPlay(request: () -> Task<T>?): T? {
    for (attempt in 0..2) {
        val task = request() ?: return null
        val completed = suspendCancellableCoroutine<Task<T>> { continuation ->
            task.addOnCompleteListener { continuation.resume(it) }
        }
        if (completed.isSuccessful) return completed.result
        val error = completed.exception as? AgeSignalsException ?: return null
        if (attempt == 2 || error.errorCode !in -8..-1) return null
        delay(350L * (attempt + 1))
    }
    return null
}

private fun ComponentActivity.showPlayAgeVerification() {
    AlertDialog.Builder(this)
        .setMessage(R.string.play_age_verify)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.play_age_open_store) { _, _ ->
            val store = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
                .setPackage("com.android.vending")
            try {
                startActivity(store)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.play_age_store_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        .show()
}
