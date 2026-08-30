package cz.majkey.pocasicesko.monetization

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetizationPolicyTest {
    @Test
    fun releaseBuildFailsClosedUntilCommercialForecastShips() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val gradle = File(root, "build.gradle.kts").readText()
        val debugBlock = gradle.substringAfter("debug {").substringBefore("release {")
        val mainActivity = File(root, "src/main/java/cz/majkey/pocasicesko/MainActivity.kt").readText()
        val weatherApp = File(root, "src/main/java/cz/majkey/pocasicesko/ui/WeatherApp.kt").readText()
        val settings = File(root, "src/main/java/cz/majkey/pocasicesko/ui/SettingsSheet.kt").readText()

        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"MONETIZATION_CONFIGURED\", \"false\")"))
        assertTrue(gradle.contains("buildConfigField(\"boolean\", \"PAYMENTS_ENABLED\", \"false\")"))
        assertTrue(debugBlock.contains("buildConfigField(\"boolean\", \"MONETIZATION_CONFIGURED\", \"true\")"))
        assertTrue(debugBlock.contains("buildConfigField(\"boolean\", \"PAYMENTS_ENABLED\", \"true\")"))
        assertTrue(mainActivity.contains("if (BuildConfig.PAYMENTS_ENABLED) premiumBillingController.start()"))
        assertTrue(mainActivity.contains("paymentsEnabled = BuildConfig.PAYMENTS_ENABLED"))
        assertTrue(weatherApp.contains("paymentsEnabled: Boolean"))
        assertTrue(weatherApp.contains("paymentsEnabled = paymentsEnabled"))
        assertTrue(settings.contains("paymentsEnabled: Boolean"))
        assertTrue(settings.contains("if (paymentsEnabled)"))
    }

    @Test
    fun adsRequireKnownFreeEntitlementConsentAndConfiguration() {
        assertTrue(shouldShowAds(EntitlementState.FREE, consentReady = true, configured = true))
        assertFalse(shouldShowAds(EntitlementState.CHECKING, consentReady = true, configured = true))
        assertFalse(shouldShowAds(EntitlementState.PREMIUM, consentReady = true, configured = true))
        assertFalse(shouldShowAds(EntitlementState.FREE, consentReady = false, configured = true))
        assertFalse(shouldShowAds(EntitlementState.FREE, consentReady = true, configured = false))
    }

    @Test
    fun onlyPublishedProductsGrantPremium() {
        assertTrue(hasPremiumEntitlement(setOf(LIFETIME_PRODUCT_ID)))
        assertTrue(hasPremiumEntitlement(setOf(MONTHLY_PRODUCT_ID)))
        assertFalse(hasPremiumEntitlement(setOf("forged_product")))
        assertFalse(hasPremiumEntitlement(emptySet()))
    }

    @Test
    fun interstitialIsDueOnlyAfterEveryFourthCompletedMapVisit() {
        assertFalse(shouldShowInterstitial(0))
        assertFalse(shouldShowInterstitial(3))
        assertTrue(shouldShowInterstitial(4))
        assertFalse(shouldShowInterstitial(5))
        assertTrue(shouldShowInterstitial(8))
    }

    @Test
    fun billingCatalogQueriesNeverMixProductTypes() {
        val groups = premiumProductGroups()

        assertEquals(2, groups.size)
        assertTrue(groups.all { group -> group.map { it.kind }.distinct().size == 1 })
    }

    @Test
    fun emptyCatalogIsReportedOnlyToNonPremiumUsers() {
        assertTrue(shouldReportUnavailableCatalog(EntitlementState.FREE, offerCount = 0))
        assertFalse(shouldReportUnavailableCatalog(EntitlementState.PREMIUM, offerCount = 0))
        assertFalse(shouldReportUnavailableCatalog(EntitlementState.FREE, offerCount = 1))
    }

    @Test
    fun bothPremiumButtonsExistBeforePlayCatalogLoads() {
        assertEquals(
            listOf(PremiumOfferType.LIFETIME to null, PremiumOfferType.MONTHLY to null),
            premiumOfferButtons(emptyList()),
        )
        assertEquals(
            listOf(PremiumOfferType.LIFETIME to "CZK 99.00", PremiumOfferType.MONTHLY to null),
            premiumOfferButtons(
                listOf(PremiumOffer(PremiumOfferType.LIFETIME, "CZK 99.00")),
            ),
        )
    }
}
