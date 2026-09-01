package cz.majkey.pocasicesko.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.locale.SupportedLanguage
import cz.majkey.pocasicesko.monetization.BillingMessage
import cz.majkey.pocasicesko.monetization.EntitlementState
import cz.majkey.pocasicesko.monetization.PremiumOffer
import cz.majkey.pocasicesko.monetization.PremiumOfferType
import cz.majkey.pocasicesko.monetization.premiumOfferButtons
import cz.majkey.pocasicesko.units.MeasurementSystem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    selectedTag: String,
    selectedMeasurementSystem: MeasurementSystem,
    dailyBriefingEnabled: Boolean,
    entitlement: EntitlementState,
    premiumOffers: List<PremiumOffer>,
    billingMessage: BillingMessage,
    paymentsEnabled: Boolean,
    privacyOptionsRequired: Boolean,
    onLanguage: (String) -> Unit,
    onMeasurementSystem: (MeasurementSystem) -> Unit,
    onDailyBriefingChange: (Boolean) -> Unit,
    onAddWidget: () -> Unit,
    onWeatherDataAttribution: () -> Unit,
    onSupport: () -> Unit,
    supportError: String?,
    onPurchase: (PremiumOfferType) -> Unit,
    onRestorePurchases: () -> Unit,
    onPrivacyOptions: () -> Unit,
    onClearBillingMessage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF101820),
        contentColor = Color.White,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                text = stringResource(R.string.units),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            MeasurementSystem.entries.forEach { system ->
                ListItem(
                    headlineContent = { Text(stringResource(system.labelResource())) },
                    supportingContent = { Text(stringResource(system.summaryResource())) },
                    trailingContent = {
                        if (system == selectedMeasurementSystem) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onMeasurementSystem(system) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Text(
                text = stringResource(R.string.language),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SupportedLanguage.entries.forEach { language ->
                ListItem(
                    headlineContent = { Text(stringResource(language.labelResource())) },
                    trailingContent = {
                        if (language.tag == selectedTag) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLanguage(language.tag) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.daily_briefing)) },
                supportingContent = { Text(stringResource(R.string.daily_briefing_summary)) },
                trailingContent = {
                    Switch(
                        checked = dailyBriefingEnabled,
                        onCheckedChange = onDailyBriefingChange,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDailyBriefingChange(!dailyBriefingEnabled) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Text(
                text = stringResource(R.string.widget_title),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            OutlinedButton(
                onClick = onAddWidget,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.widget_add))
            }
            if (paymentsEnabled) {
                Text(
                    text = stringResource(R.string.premium),
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                if (entitlement == EntitlementState.PREMIUM) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.premium_active)) },
                        supportingContent = { Text(stringResource(R.string.premium_active_summary)) },
                        trailingContent = { Icon(Icons.Rounded.Check, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.premium_summary),
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    premiumOfferButtons(premiumOffers).forEach { (type, price) ->
                        val label = stringResource(type.labelResource())
                        OutlinedButton(
                            onClick = {
                                onClearBillingMessage()
                                onPurchase(type)
                            },
                            enabled = price != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp)
                                .heightIn(min = 52.dp),
                        ) {
                            Text(
                                price?.let { "$label · $it" } ?: label,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        onClearBillingMessage()
                        onRestorePurchases()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.restore_purchases))
                }
                if (billingMessage != BillingMessage.NONE) {
                    Text(
                        text = stringResource(billingMessage.labelResource()),
                        color = if (billingMessage == BillingMessage.COMPLETE) {
                            Color(0xFF83D6E8)
                        } else {
                            Color.White.copy(alpha = 0.68f)
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else if (entitlement == EntitlementState.CHECKING) {
                    Text(
                        text = stringResource(R.string.billing_checking),
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
            }
            if (privacyOptionsRequired) {
                OutlinedButton(
                    onClick = onPrivacyOptions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.privacy_choices))
                }
            }
            Text(
                text = stringResource(R.string.about),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.weather_data_attribution)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onWeatherDataAttribution),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Button(
                onClick = onSupport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 56.dp),
                border = BorderStroke(1.dp, Color(0xFF111111)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFDD00),
                    contentColor = Color(0xFF111111),
                ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_coffee),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.support_this_app))
            }
            Text(
                text = stringResource(R.string.support_note),
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            supportError?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun MeasurementSystem.labelResource(): Int = when (this) {
    MeasurementSystem.METRIC -> R.string.units_metric
    MeasurementSystem.IMPERIAL -> R.string.units_imperial
}

private fun MeasurementSystem.summaryResource(): Int = when (this) {
    MeasurementSystem.METRIC -> R.string.units_metric_summary
    MeasurementSystem.IMPERIAL -> R.string.units_imperial_summary
}

private fun SupportedLanguage.labelResource(): Int = when (this) {
    SupportedLanguage.SYSTEM -> R.string.language_system
    SupportedLanguage.ENGLISH -> R.string.language_english
    SupportedLanguage.CZECH -> R.string.language_czech
    SupportedLanguage.GERMAN -> R.string.language_german
    SupportedLanguage.SPANISH -> R.string.language_spanish
    SupportedLanguage.FRENCH -> R.string.language_french
}

private fun PremiumOfferType.labelResource(): Int = when (this) {
    PremiumOfferType.LIFETIME -> R.string.premium_lifetime
    PremiumOfferType.MONTHLY -> R.string.premium_monthly
}

private fun BillingMessage.labelResource(): Int = when (this) {
    BillingMessage.NONE -> R.string.billing_checking
    BillingMessage.COMPLETE -> R.string.billing_complete
    BillingMessage.PENDING -> R.string.billing_pending
    BillingMessage.UNAVAILABLE -> R.string.billing_unavailable
    BillingMessage.ERROR -> R.string.billing_error
}
