package cz.majkey.pocasicesko.data

import java.util.Locale

const val REGION_CZECHIA = "REGION_CZECHIA"
const val REGION_PRAGUE = "REGION_PRAGUE"
const val REGION_CENTRAL_BOHEMIA = "REGION_CENTRAL_BOHEMIA"
const val REGION_SOUTH_BOHEMIAN = "REGION_SOUTH_BOHEMIAN"
const val REGION_PLZEN = "REGION_PLZEN"
const val REGION_KARLOVY_VARY = "REGION_KARLOVY_VARY"
const val REGION_USTI_NAD_LABEM = "REGION_USTI_NAD_LABEM"
const val REGION_LIBEREC = "REGION_LIBEREC"
const val REGION_HRADEC_KRALOVE = "REGION_HRADEC_KRALOVE"
const val REGION_PARDUBICE = "REGION_PARDUBICE"
const val REGION_VYSOCINA = "REGION_VYSOCINA"
const val REGION_SOUTH_MORAVIAN = "REGION_SOUTH_MORAVIAN"
const val REGION_OLOMOUC = "REGION_OLOMOUC"
const val REGION_ZLIN = "REGION_ZLIN"
const val REGION_MORAVIAN_SILESIAN = "REGION_MORAVIAN_SILESIAN"

internal fun regionKeyForAdmin1Id(admin1Id: Int): String = when (admin1Id) {
    3_067_695 -> REGION_PRAGUE
    3_339_576 -> REGION_CENTRAL_BOHEMIA
    3_339_537 -> REGION_SOUTH_BOHEMIAN
    3_339_575 -> REGION_PLZEN
    3_339_539 -> REGION_KARLOVY_VARY
    3_339_577 -> REGION_USTI_NAD_LABEM
    3_339_541 -> REGION_LIBEREC
    3_339_540 -> REGION_HRADEC_KRALOVE
    3_339_574 -> REGION_PARDUBICE
    3_339_538 -> REGION_VYSOCINA
    3_339_536 -> REGION_SOUTH_MORAVIAN
    3_339_542 -> REGION_OLOMOUC
    3_339_578 -> REGION_ZLIN
    3_339_573 -> REGION_MORAVIAN_SILESIAN
    else -> REGION_CZECHIA
}

internal fun normalizeRegionKey(region: String): String = REGION_ALIASES[region.trim().lowercase(Locale.ROOT)]
    ?: REGION_CZECHIA

internal fun normalizeLocationRegion(location: CzechLocation): CzechLocation =
    location.copy(region = normalizeRegionKey(location.region))

private val REGION_ALIASES = buildMap {
    fun aliases(key: String, vararg labels: String) {
        put(key.lowercase(Locale.ROOT), key)
        labels.forEach { put(it.lowercase(Locale.ROOT), key) }
    }

    aliases(REGION_CZECHIA, "Czechia", "Česko")
    aliases(REGION_PRAGUE, "Prague", "Capital City of Prague", "Hlavní město Praha")
    aliases(REGION_CENTRAL_BOHEMIA, "Central Bohemia", "Central Bohemian Region", "Středočeský kraj")
    aliases(REGION_SOUTH_BOHEMIAN, "South Bohemian Region", "Jihočeský kraj")
    aliases(REGION_PLZEN, "Plzeň Region", "Pilsen Region", "Plzeňský kraj")
    aliases(REGION_KARLOVY_VARY, "Carlsbad Region", "Karlovy Vary Region", "Karlovarský kraj")
    aliases(REGION_USTI_NAD_LABEM, "Ústí nad Labem Region", "Ústecký kraj")
    aliases(REGION_LIBEREC, "Liberec Region", "Liberecký kraj")
    aliases(REGION_HRADEC_KRALOVE, "Hradec Králové Region", "Královéhradecký kraj")
    aliases(REGION_PARDUBICE, "Pardubice Region", "Pardubický kraj")
    aliases(REGION_VYSOCINA, "Vysocina", "Vysočina Region", "Kraj Vysočina")
    aliases(REGION_SOUTH_MORAVIAN, "South Moravian", "South Moravian Region", "Jihomoravský", "Jihomoravský kraj")
    aliases(REGION_OLOMOUC, "Olomouc Region", "Olomoucký kraj")
    aliases(REGION_ZLIN, "Zlín", "Zlín Region", "Zlínský kraj")
    aliases(REGION_MORAVIAN_SILESIAN, "Moravian-Silesian Region", "Moravskoslezský kraj")
}
