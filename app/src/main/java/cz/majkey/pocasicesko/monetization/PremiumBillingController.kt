package cz.majkey.pocasicesko.monetization

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PremiumOfferType {
    LIFETIME,
    MONTHLY,
}

data class PremiumOffer(
    val type: PremiumOfferType,
    val price: String,
)

enum class BillingMessage {
    NONE,
    COMPLETE,
    PENDING,
    UNAVAILABLE,
    ERROR,
}

class PremiumBillingController(private val activity: Activity) : PurchasesUpdatedListener {
    private val mutableEntitlement = MutableStateFlow(EntitlementState.CHECKING)
    val entitlement: StateFlow<EntitlementState> = mutableEntitlement.asStateFlow()

    private val mutableOffers = MutableStateFlow(emptyList<PremiumOffer>())
    val offers: StateFlow<List<PremiumOffer>> = mutableOffers.asStateFlow()

    private val mutableMessage = MutableStateFlow(BillingMessage.NONE)
    val message: StateFlow<BillingMessage> = mutableMessage.asStateFlow()

    private val productDetails = mutableMapOf<PremiumOfferType, ProductDetails>()
    private var connecting = false
    private val billingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (billingClient.isReady) {
            refresh()
            queryOffers()
            return
        }
        if (connecting) return
        connecting = true
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refresh()
                    queryOffers()
                } else {
                    mutableMessage.value = BillingMessage.UNAVAILABLE
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                mutableEntitlement.value = EntitlementState.CHECKING
            }
        })
    }

    fun refresh() {
        if (!billingClient.isReady) {
            start()
            return
        }
        queryOwned(BillingClient.ProductType.INAPP) { inApp, inAppOk ->
            if (!inAppOk) return@queryOwned billingUnavailable()
            queryOwned(BillingClient.ProductType.SUBS) { subscriptions, subscriptionsOk ->
                if (!subscriptionsOk) return@queryOwned billingUnavailable()
                processPurchases(inApp + subscriptions)
            }
        }
    }

    fun launch(type: PremiumOfferType) {
        val details = productDetails[type] ?: return billingUnavailable()
        val offerToken = when (type) {
            PremiumOfferType.LIFETIME ->
                details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
                    ?: details.oneTimePurchaseOfferDetails?.offerToken
            PremiumOfferType.MONTHLY -> details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        } ?: return billingUnavailable()
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val result = billingClient.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(productParams)).build(),
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            mutableMessage.value = BillingMessage.ERROR
        }
    }

    fun clearMessage() {
        mutableMessage.value = BillingMessage.NONE
    }

    fun close() {
        billingClient.endConnection()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> processPurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> mutableMessage.value = BillingMessage.NONE
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> mutableMessage.value = BillingMessage.ERROR
        }
    }

    private fun queryOwned(productType: String, complete: (List<Purchase>, Boolean) -> Unit) {
        val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            complete(purchases, result.responseCode == BillingClient.BillingResponseCode.OK)
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        val purchased = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val premium = hasPremiumEntitlement(purchased.flatMapTo(mutableSetOf()) { it.products })
        mutableEntitlement.value = if (premium) EntitlementState.PREMIUM else EntitlementState.FREE

        if (purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
            mutableMessage.value = BillingMessage.PENDING
        } else if (premium) {
            mutableMessage.value = BillingMessage.COMPLETE
        }
        purchased
            .filter { !it.isAcknowledged && hasPremiumEntitlement(it.products.toSet()) }
            .forEach(::acknowledge)
    }

    private fun acknowledge(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                mutableMessage.value = BillingMessage.ERROR
            }
        }
    }

    private fun queryOffers() {
        productDetails.clear()
        queryOfferGroup(premiumProductGroups(), index = 0)
    }

    private fun queryOfferGroup(groups: List<List<ProductSpec>>, index: Int) {
        if (index == groups.size) {
            mutableOffers.value = productDetails.mapNotNull { (type, details) ->
                details.formattedPrice(type)?.let { PremiumOffer(type, it) }
            }.sortedBy { it.type.ordinal }
            if (shouldReportUnavailableCatalog(mutableEntitlement.value, mutableOffers.value.size)) {
                mutableMessage.value = BillingMessage.UNAVAILABLE
            }
            return
        }
        val products = groups[index].map(::queryProduct)
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(products).build(),
        ) { result, detailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                detailsResult.productDetailsList.forEach { details ->
                    val type = when (details.productId) {
                        LIFETIME_PRODUCT_ID -> PremiumOfferType.LIFETIME
                        MONTHLY_PRODUCT_ID -> PremiumOfferType.MONTHLY
                        else -> return@forEach
                    }
                    productDetails[type] = details
                }
            }
            queryOfferGroup(groups, index + 1)
        }
    }

    private fun billingUnavailable() {
        mutableEntitlement.value = EntitlementState.CHECKING
        mutableMessage.value = BillingMessage.UNAVAILABLE
    }
}

private fun queryProduct(spec: ProductSpec): QueryProductDetailsParams.Product =
    QueryProductDetailsParams.Product.newBuilder()
        .setProductId(spec.id)
        .setProductType(
            when (spec.kind) {
                ProductKind.IN_APP -> BillingClient.ProductType.INAPP
                ProductKind.SUBSCRIPTION -> BillingClient.ProductType.SUBS
            },
        )
        .build()

private fun ProductDetails.formattedPrice(type: PremiumOfferType): String? = when (type) {
    PremiumOfferType.LIFETIME -> oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
        ?: oneTimePurchaseOfferDetails?.formattedPrice
    PremiumOfferType.MONTHLY -> subscriptionOfferDetails?.firstOrNull()
        ?.pricingPhases
        ?.pricingPhaseList
        ?.lastOrNull()
        ?.formattedPrice
}
