package cz.majkey.pocasicesko.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.REGION_CZECHIA
import cz.majkey.pocasicesko.data.REGION_PRAGUE

@Composable
internal fun CzechLocation.localizedRegion(): String = when (region) {
    REGION_CZECHIA -> stringResource(R.string.location_region_czechia)
    REGION_PRAGUE, "Hlavní město Praha" -> stringResource(R.string.location_region_prague)
    else -> region
}
