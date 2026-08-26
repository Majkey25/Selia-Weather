package cz.majkey.pocasicesko.monetization

const val LIFETIME_PRODUCT_ID = "remove_ads_lifetime"
const val MONTHLY_PRODUCT_ID = "premium_monthly"

enum class EntitlementState {
    CHECKING,
    FREE,
    PREMIUM,
}

internal enum class ProductKind {
    IN_APP,
    SUBSCRIPTION,
}

internal data class ProductSpec(val id: String, val kind: ProductKind)

internal fun premiumProductGroups(): List<List<ProductSpec>> = listOf(
    listOf(ProductSpec(LIFETIME_PRODUCT_ID, ProductKind.IN_APP)),
    listOf(ProductSpec(MONTHLY_PRODUCT_ID, ProductKind.SUBSCRIPTION)),
)

internal fun premiumOfferButtons(
    offers: List<PremiumOffer>,
): List<Pair<PremiumOfferType, String?>> = PremiumOfferType.entries.map { type ->
    type to offers.firstOrNull { offer -> offer.type == type }?.price
}

internal fun shouldReportUnavailableCatalog(entitlement: EntitlementState, offerCount: Int): Boolean =
    entitlement != EntitlementState.PREMIUM && offerCount == 0

internal fun shouldShowAds(
    entitlement: EntitlementState,
    consentReady: Boolean,
    configured: Boolean,
): Boolean = entitlement == EntitlementState.FREE && consentReady && configured

internal fun hasPremiumEntitlement(productIds: Set<String>): Boolean =
    LIFETIME_PRODUCT_ID in productIds || MONTHLY_PRODUCT_ID in productIds

internal fun shouldShowInterstitial(completedMapVisits: Int): Boolean =
    completedMapVisits > 0 && completedMapVisits % 4 == 0
