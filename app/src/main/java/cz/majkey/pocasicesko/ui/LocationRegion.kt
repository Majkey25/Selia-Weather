package cz.majkey.pocasicesko.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cz.majkey.pocasicesko.R
import cz.majkey.pocasicesko.data.CzechLocation
import cz.majkey.pocasicesko.data.REGION_CZECHIA
import cz.majkey.pocasicesko.data.REGION_CENTRAL_BOHEMIA
import cz.majkey.pocasicesko.data.REGION_HRADEC_KRALOVE
import cz.majkey.pocasicesko.data.REGION_KARLOVY_VARY
import cz.majkey.pocasicesko.data.REGION_LIBEREC
import cz.majkey.pocasicesko.data.REGION_MORAVIAN_SILESIAN
import cz.majkey.pocasicesko.data.REGION_OLOMOUC
import cz.majkey.pocasicesko.data.REGION_PARDUBICE
import cz.majkey.pocasicesko.data.REGION_PLZEN
import cz.majkey.pocasicesko.data.REGION_PRAGUE
import cz.majkey.pocasicesko.data.REGION_SOUTH_BOHEMIAN
import cz.majkey.pocasicesko.data.REGION_SOUTH_MORAVIAN
import cz.majkey.pocasicesko.data.REGION_USTI_NAD_LABEM
import cz.majkey.pocasicesko.data.REGION_VYSOCINA
import cz.majkey.pocasicesko.data.REGION_ZLIN

@Composable
internal fun CzechLocation.localizedRegion(): String = stringResource(regionLabelResource(region))

internal fun regionLabelResource(region: String): Int = when (region) {
    REGION_PRAGUE -> R.string.location_region_prague
    REGION_CENTRAL_BOHEMIA -> R.string.location_region_central_bohemia
    REGION_SOUTH_BOHEMIAN -> R.string.location_region_south_bohemian
    REGION_PLZEN -> R.string.location_region_plzen
    REGION_KARLOVY_VARY -> R.string.location_region_karlovy_vary
    REGION_USTI_NAD_LABEM -> R.string.location_region_usti_nad_labem
    REGION_LIBEREC -> R.string.location_region_liberec
    REGION_HRADEC_KRALOVE -> R.string.location_region_hradec_kralove
    REGION_PARDUBICE -> R.string.location_region_pardubice
    REGION_VYSOCINA -> R.string.location_region_vysocina
    REGION_SOUTH_MORAVIAN -> R.string.location_region_south_moravian
    REGION_OLOMOUC -> R.string.location_region_olomouc
    REGION_ZLIN -> R.string.location_region_zlin
    REGION_MORAVIAN_SILESIAN -> R.string.location_region_moravian_silesian
    else -> R.string.location_region_czechia
}
