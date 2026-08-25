package cz.majkey.pocasicesko.monetization

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import cz.majkey.pocasicesko.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AdsController(private val activity: Activity) {
    private val consentInformation = UserMessagingPlatform.getConsentInformation(activity)
    private val mutableAdsInitialized = MutableStateFlow(false)
    val adsInitialized: StateFlow<Boolean> = mutableAdsInitialized.asStateFlow()

    private val mutablePrivacyOptionsRequired = MutableStateFlow(false)
    val privacyOptionsRequired: StateFlow<Boolean> = mutablePrivacyOptionsRequired.asStateFlow()

    private var interstitialAd: InterstitialAd? = null
    private var initializing = false

    fun start() {
        if (!BuildConfig.MONETIZATION_CONFIGURED) return
        consentInformation.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                updatePrivacyRequirement()
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    updatePrivacyRequirement()
                    initializeAdsIfAllowed()
                }
            },
            {
                updatePrivacyRequirement()
                initializeAdsIfAllowed()
            },
        )
    }

    fun showPrivacyOptions() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            updatePrivacyRequirement()
            initializeAdsIfAllowed()
        }
    }

    fun maybeShowInterstitial(entitlement: EntitlementState, onContinue: () -> Unit) {
        val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val visit = preferences.getInt(KEY_MAP_VISITS, 0) % INTERSTITIAL_FREQUENCY + 1
        preferences.edit().putInt(KEY_MAP_VISITS, visit).apply()
        val ad = interstitialAd
        if (!shouldShowAds(entitlement, mutableAdsInitialized.value, BuildConfig.MONETIZATION_CONFIGURED) ||
            !shouldShowInterstitial(visit) || ad == null
        ) {
            onContinue()
            return
        }
        interstitialAd = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadInterstitial()
                onContinue()
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                loadInterstitial()
                onContinue()
            }
        }
        ad.show(activity)
    }

    private fun updatePrivacyRequirement() {
        mutablePrivacyOptionsRequired.value =
            consentInformation.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun initializeAdsIfAllowed() {
        if (!BuildConfig.MONETIZATION_CONFIGURED || !consentInformation.canRequestAds() ||
            mutableAdsInitialized.value || initializing
        ) return
        initializing = true
        MobileAds.initialize(activity) {
            initializing = false
            mutableAdsInitialized.value = true
            loadInterstitial()
        }
    }

    private fun loadInterstitial() {
        if (interstitialAd != null || !mutableAdsInitialized.value) return
        InterstitialAd.load(
            activity,
            BuildConfig.INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }

                override fun onAdFailedToLoad(error: com.google.android.gms.ads.LoadAdError) {
                    interstitialAd = null
                }
            },
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "monetization"
        const val KEY_MAP_VISITS = "completed_map_visits"
        const val INTERSTITIAL_FREQUENCY = 4
    }
}
