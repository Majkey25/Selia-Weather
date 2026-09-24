package cz.majkey.pocasicesko.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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

private enum class SettingsPage(val title: Int) {
    ROOT(R.string.settings),
    UNITS(R.string.units),
    LANGUAGE(R.string.language),
    WIDGETS(R.string.widget_title),
    ABOUT(R.string.settings_about_support),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    selectedTag: String,
    selectedMeasurementSystem: MeasurementSystem,
    entitlement: EntitlementState,
    premiumOffers: List<PremiumOffer>,
    billingMessage: BillingMessage,
    paymentsEnabled: Boolean,
    privacyOptionsRequired: Boolean,
    widgetIds: List<Int>,
    onLanguage: (String) -> Unit,
    onMeasurementSystem: (MeasurementSystem) -> Unit,
    onNotifications: () -> Unit,
    onAddWidget: () -> Unit,
    onEditWidget: (Int) -> Unit,
    onWeatherDataAttribution: () -> Unit,
    onLegalPage: (LegalPage) -> Unit,
    onSupport: () -> Unit,
    supportError: String?,
    onPurchase: (PremiumOfferType) -> Unit,
    onRestorePurchases: () -> Unit,
    onPrivacyOptions: () -> Unit,
    onClearBillingMessage: () -> Unit,
    onDismiss: () -> Unit,
    onAppearance: () -> Unit = {},
    onWarningSettings: () -> Unit = onNotifications,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }
    val rootListState = rememberLazyListState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = page == SettingsPage.ROOT),
    ) {
        BackHandler(enabled = page != SettingsPage.ROOT) { page = SettingsPage.ROOT }
        SheetHeader(stringResource(page.title), onBack = {
            if (page == SettingsPage.ROOT) onDismiss() else page = SettingsPage.ROOT
        })
        key(page) {
            LazyColumn(
                state = if (page == SettingsPage.ROOT) rootListState else rememberLazyListState(),
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .navigationBarsPadding().padding(bottom = 24.dp),
            ) {
                when (page) {
                    SettingsPage.ROOT -> {
                        item {
                            SettingsCategoryRow(R.string.notifications, Icons.Rounded.Notifications,
                                stringResource(R.string.settings_notifications_summary), onNotifications)
                        }
                        item {
                            SettingsCategoryRow(R.string.notification_official, Icons.Rounded.WarningAmber,
                                stringResource(R.string.settings_warnings_summary), onWarningSettings)
                        }
                        item { SettingsCategoryRow(R.string.appearance_title, Icons.Rounded.Palette, onClick = onAppearance) }
                        item {
                            SettingsCategoryRow(R.string.units, Icons.Rounded.Straighten,
                                stringResource(selectedMeasurementSystem.labelResource())) { page = SettingsPage.UNITS }
                        }
                        item {
                            val language = SupportedLanguage.entries.firstOrNull { it.tag == selectedTag } ?: SupportedLanguage.SYSTEM
                            SettingsCategoryRow(R.string.language, Icons.Rounded.Language,
                                stringResource(language.labelResource())) { page = SettingsPage.LANGUAGE }
                        }
                        item {
                            SettingsCategoryRow(R.string.widget_title, Icons.Rounded.Widgets,
                                stringResource(R.string.settings_widgets_summary)) { page = SettingsPage.WIDGETS }
                        }
                        item {
                            SettingsCategoryRow(R.string.settings_about_support, Icons.Rounded.Info) { page = SettingsPage.ABOUT }
                        }
                    }
                    SettingsPage.UNITS -> items(MeasurementSystem.entries, key = { it.name }) { system ->
                        ListItem(
                            headlineContent = { Text(stringResource(system.labelResource())) },
                            supportingContent = { Text(stringResource(system.summaryResource())) },
                            trailingContent = {
                                if (system == selectedMeasurementSystem) Icon(Icons.Rounded.Check, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = system == selectedMeasurementSystem,
                                role = Role.RadioButton,
                                onClick = { onMeasurementSystem(system) },
                            ),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    SettingsPage.LANGUAGE -> items(SupportedLanguage.entries, key = { it.tag }) { language ->
                        ListItem(
                            headlineContent = { Text(stringResource(language.labelResource())) },
                            trailingContent = {
                                if (language.tag == selectedTag) Icon(Icons.Rounded.Check, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = language.tag == selectedTag,
                                role = Role.RadioButton,
                                onClick = { onLanguage(language.tag) },
                            ),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    SettingsPage.WIDGETS -> {
                        item {
                            OutlinedButton(
                                onClick = onAddWidget,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                    .heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.widget_add)) }
                        }
                        itemsIndexed(widgetIds, key = { _, widgetId -> widgetId }) { index, widgetId ->
                            OutlinedButton(
                                onClick = { onEditWidget(widgetId) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                    .heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.widget_edit, index + 1)) }
                        }
                        supportError?.let { error -> item { SettingsError(error) } }
                    }
                    SettingsPage.ABOUT -> {
                        if (paymentsEnabled) {
                            item {
                                Text(stringResource(R.string.premium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                            }
                            if (entitlement == EntitlementState.PREMIUM) {
                                item {
                                    ListItem(
                                        headlineContent = { Text(stringResource(R.string.premium_active)) },
                                        supportingContent = { Text(stringResource(R.string.premium_active_summary)) },
                                        trailingContent = { Icon(Icons.Rounded.Check, contentDescription = null) },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    )
                                }
                            } else {
                                item {
                                    Text(stringResource(R.string.premium_summary),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                                }
                                items(premiumOfferButtons(premiumOffers), key = { it.first.name }) { (type, price) ->
                                    val label = stringResource(type.labelResource())
                                    OutlinedButton(
                                        onClick = { onClearBillingMessage(); onPurchase(type) },
                                        enabled = price != null,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                            .heightIn(min = 52.dp),
                                    ) {
                                        Text(price?.let { "$label · $it" } ?: label, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            item {
                                OutlinedButton(
                                    onClick = { onClearBillingMessage(); onRestorePurchases() },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                        .heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.restore_purchases)) }
                            }
                            if (billingMessage != BillingMessage.NONE) {
                                item {
                                    Text(stringResource(billingMessage.labelResource()),
                                        color = if (billingMessage == BillingMessage.COMPLETE) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                                }
                            } else if (entitlement == EntitlementState.CHECKING) {
                                item {
                                    Text(stringResource(R.string.billing_checking),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                                }
                            }
                        }
                        if (privacyOptionsRequired) {
                            item {
                                OutlinedButton(
                                    onClick = onPrivacyOptions,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
                                        .heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.privacy_choices)) }
                            }
                        }
                        item {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.weather_data_attribution)) },
                                modifier = Modifier.fillMaxWidth().clickable(onClick = onWeatherDataAttribution),
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                        item {
                            Text(stringResource(R.string.legal_title),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        }
                        items(LegalPage.entries, key = { it.name }) { page ->
                            ListItem(
                                headlineContent = { Text(stringResource(page.labelResource)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onLegalPage(page) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                        item {
                            Button(
                                onClick = onSupport,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 56.dp),
                                border = BorderStroke(1.dp, Color(0xFF111111)),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFDD00), contentColor = Color(0xFF111111),
                                ),
                            ) {
                                Icon(painterResource(R.drawable.ic_coffee), contentDescription = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.support_this_app))
                            }
                        }
                        item {
                            Text(stringResource(R.string.support_note),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        }
                        supportError?.let { error -> item { SettingsError(error) } }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsCategoryRow(
    title: Int,
    icon: ImageVector,
    summary: String? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun SettingsError(error: String) {
    Text(
        text = error,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
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
