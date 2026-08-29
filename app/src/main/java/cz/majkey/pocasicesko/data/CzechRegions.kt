package cz.majkey.pocasicesko.data

import java.util.Locale

const val REGION_CZECHIA = "REGION_CZECHIA"
const val REGION_WORLD = "REGION_WORLD"
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

internal fun normalizeLocationRegion(location: CzechLocation): CzechLocation {
    val czechRegion = REGION_ALIASES[location.region.trim().lowercase(Locale.ROOT)]
    val region = when {
        !location.countryCode.isNullOrBlank() &&
            !location.countryCode.equals("CZ", ignoreCase = true) -> {
            location.region.trim().ifEmpty { REGION_WORLD }
        }
        location.countryCode.equals("CZ", ignoreCase = true) -> czechRegion ?: REGION_CZECHIA
        location.countryCode == null && location.region != REGION_WORLD &&
            location.latitude in CZECH_LATITUDE && location.longitude in CZECH_LONGITUDE -> {
            czechRegion ?: REGION_CZECHIA
        }
        czechRegion != null -> czechRegion
        else -> location.region.trim().ifEmpty { REGION_WORLD }
    }
    return location.copy(region = region)
}

internal fun CzechLocation.isInCzechia(): Boolean {
    val code = countryCode?.trim()
    if (!code.isNullOrEmpty()) return code.equals("CZ", ignoreCase = true)
    return normalizeLocationRegion(this).region in CZECH_REGION_KEYS
}

private val REGION_ALIASES = buildMap {
    fun aliases(key: String, vararg labels: String) {
        put(key.lowercase(Locale.ROOT), key)
        labels.forEach { label ->
            val normalized = label.lowercase(Locale.ROOT)
            val previous = put(normalized, key)
            check(previous == null || previous == key) { "Region alias collision: $label" }
        }
    }

    aliases(REGION_CZECHIA, "Czechia", "Česko")
    aliases(REGION_PRAGUE, "Prague", "Capital City of Prague", "Hlavní město Praha", "Hauptstadt Prag", "Ciudad Capital de Praga", "Capitale Prague")
    aliases(REGION_CENTRAL_BOHEMIA, "Central Bohemia", "Central Bohemian Region", "Středočeský kraj", "Mittelböhmische Region", "Región de Bohemia Central", "Région de Bohême-Centrale")
    aliases(REGION_SOUTH_BOHEMIAN, "South Bohemian Region", "Jihočeský kraj", "Südböhmische Region", "Región de Bohemia Meridional", "Région de Bohême-du-Sud")
    aliases(REGION_PLZEN, "Plzeň Region", "Pilsen Region", "Plzeňský kraj", "Region Pilsen", "Región de Pilsen", "Région de Plzeň")
    aliases(REGION_KARLOVY_VARY, "Carlsbad Region", "Karlovy Vary Region", "Karlovarský kraj", "Region Karlsbad", "Región de Karlovy Vary", "Région de Karlovy Vary")
    aliases(REGION_USTI_NAD_LABEM, "Ústí nad Labem Region", "Ústecký kraj", "Region Ústí nad Labem", "Región de Ústí nad Labem", "Région d'Ústí nad Labem")
    aliases(REGION_LIBEREC, "Liberec Region", "Liberecký kraj", "Region Liberec", "Región de Liberec", "Région de Liberec")
    aliases(REGION_HRADEC_KRALOVE, "Hradec Králové Region", "Královéhradecký kraj", "Region Hradec Králové", "Región de Hradec Králové", "Région de Hradec Králové")
    aliases(REGION_PARDUBICE, "Pardubice Region", "Pardubický kraj", "Region Pardubice", "Región de Pardubice", "Région de Pardubice")
    aliases(REGION_VYSOCINA, "Vysocina", "Vysočina Region", "Kraj Vysočina", "Region Vysočina", "Región de Vysočina", "Région de Vysočina")
    aliases(REGION_SOUTH_MORAVIAN, "South Moravian", "South Moravian Region", "Jihomoravský", "Jihomoravský kraj", "Südmährische Region", "Región de Moravia Meridional", "Région de Moravie-du-Sud")
    aliases(REGION_OLOMOUC, "Olomouc Region", "Olomoucký kraj", "Region Olomouc", "Región de Olomouc", "Région d'Olomouc")
    aliases(REGION_ZLIN, "Zlín", "Zlín Region", "Zlínský kraj", "Region Zlín", "Región de Zlín", "Région de Zlín")
    aliases(REGION_MORAVIAN_SILESIAN, "Moravian-Silesian Region", "Moravskoslezský kraj", "Mährisch-Schlesische Region", "Región de Moravia-Silesia", "Région de Moravie-Silésie")
}

private val CZECH_REGION_KEYS = REGION_ALIASES.values.toSet()
private val CZECH_LATITUDE = 48.45..51.2
private val CZECH_LONGITUDE = 11.9..19.0
