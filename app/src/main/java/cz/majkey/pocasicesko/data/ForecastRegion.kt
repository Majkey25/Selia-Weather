package cz.majkey.pocasicesko.data

enum class ForecastRegion {
    CZECHIA,
    EUROPE,
    NORTH_AMERICA,
    SOUTH_AMERICA,
    AFRICA,
    SOUTH_CENTRAL_ASIA,
    EAST_ASIA,
    NORTHERN_ASIA,
    OCEANIA,
    GLOBAL,
}

internal fun forecastRegionFor(location: CzechLocation): ForecastRegion = when {
    location.isInCzechia() -> ForecastRegion.CZECHIA
    location.countryCode == "RU" -> ForecastRegion.NORTHERN_ASIA
    location.countryCode in SOUTH_CENTRAL_ASIA_COUNTRIES -> ForecastRegion.SOUTH_CENTRAL_ASIA
    location.countryCode in AFRICA_COUNTRIES -> ForecastRegion.AFRICA
    location.countryCode in SOUTH_AMERICA_COUNTRIES -> ForecastRegion.SOUTH_AMERICA
    location.latitude in EUROPE_LATITUDE && location.longitude in EUROPE_LONGITUDE -> {
        ForecastRegion.EUROPE
    }
    location.latitude in SOUTH_AMERICA_LATITUDE && location.longitude in SOUTH_AMERICA_LONGITUDE -> {
        ForecastRegion.SOUTH_AMERICA
    }
    location.latitude in NORTH_AMERICA_LATITUDE && location.longitude in NORTH_AMERICA_LONGITUDE -> {
        ForecastRegion.NORTH_AMERICA
    }
    location.latitude in AFRICA_LATITUDE && location.longitude in AFRICA_LONGITUDE -> {
        ForecastRegion.AFRICA
    }
    location.latitude in SOUTH_CENTRAL_ASIA_LATITUDE &&
        location.longitude in SOUTH_CENTRAL_ASIA_LONGITUDE -> {
        ForecastRegion.SOUTH_CENTRAL_ASIA
    }
    location.latitude in EAST_ASIA_LATITUDE && location.longitude in EAST_ASIA_LONGITUDE -> {
        ForecastRegion.EAST_ASIA
    }
    location.latitude in NORTHERN_ASIA_LATITUDE && location.longitude in NORTHERN_ASIA_LONGITUDE -> {
        ForecastRegion.NORTHERN_ASIA
    }
    location.latitude in OCEANIA_LATITUDE && location.longitude in OCEANIA_LONGITUDE -> {
        ForecastRegion.OCEANIA
    }
    else -> ForecastRegion.GLOBAL
}

internal fun forecastSourcesFor(region: ForecastRegion): Set<String> = when (region) {
    ForecastRegion.CZECHIA -> EUROPE_SOURCES + "chmi-aladin-cz-1km"
    ForecastRegion.EUROPE -> EUROPE_SOURCES
    ForecastRegion.NORTH_AMERICA, ForecastRegion.SOUTH_AMERICA, ForecastRegion.AFRICA,
    ForecastRegion.SOUTH_CENTRAL_ASIA, ForecastRegion.EAST_ASIA,
    ForecastRegion.NORTHERN_ASIA, ForecastRegion.OCEANIA,
    ForecastRegion.GLOBAL -> GLOBAL_SOURCES
}

internal fun forecastApiModelsFor(location: CzechLocation): List<String> = buildList {
    if (location.isInCzechia()) add("chmi_aladin_seamless")
    addAll(GLOBAL_API_MODELS)
}

private val GLOBAL_SOURCES = setOf(
    "ecmwf-aifs-open",
    "ecmwf-ifs-open",
    "noaa-gefs",
    "noaa-gfs",
)
private val EUROPE_SOURCES = GLOBAL_SOURCES + "dwd-icon-eu"

private val GLOBAL_API_MODELS = listOf(
    "icon_seamless",
    "ecmwf_ifs025",
    "ecmwf_aifs025",
    "gfs_seamless",
    "gem_seamless",
    "meteofrance_seamless",
    "ukmo_seamless",
    "cma_grapes_global",
    "jma_seamless",
    "bom_access_global",
)

private val EUROPE_LATITUDE = 34.0..72.0
private val EUROPE_LONGITUDE = -25.0..45.0
private val NORTH_AMERICA_LATITUDE = 7.0..84.0
private val NORTH_AMERICA_LONGITUDE = -170.0..-50.0
private val SOUTH_AMERICA_LATITUDE = -60.0..15.0
private val SOUTH_AMERICA_LONGITUDE = -90.0..-30.0
private val AFRICA_LATITUDE = -36.0..38.0
private val AFRICA_LONGITUDE = -19.0..53.0
private val SOUTH_CENTRAL_ASIA_LATITUDE = 5.0..40.0
private val SOUTH_CENTRAL_ASIA_LONGITUDE = 45.0..100.0
private val EAST_ASIA_LATITUDE = 5.0..60.0
private val EAST_ASIA_LONGITUDE = 100.0..150.0
private val NORTHERN_ASIA_LATITUDE = 40.0..82.0
private val NORTHERN_ASIA_LONGITUDE = 45.0..180.0
private val OCEANIA_LATITUDE = -50.0..5.0
private val OCEANIA_LONGITUDE = 110.0..180.0

private val SOUTH_CENTRAL_ASIA_COUNTRIES = setOf(
    "AF", "BD", "BT", "IN", "KG", "KZ", "LK", "MV", "NP", "PK", "TJ", "TM", "UZ",
)
private val AFRICA_COUNTRIES = setOf(
    "AO", "BF", "BI", "BJ", "BW", "CD", "CF", "CG", "CI", "CM", "CV", "DJ", "DZ",
    "EG", "ER", "ET", "GA", "GH", "GM", "GN", "GQ", "GW", "KE", "KM", "LR", "LS",
    "LY", "MA", "MG", "ML", "MR", "MU", "MW", "MZ", "NA", "NE", "NG", "RW", "SC",
    "SD", "SL", "SN", "SO", "SS", "ST", "SZ", "TD", "TG", "TN", "TZ", "UG", "ZA",
    "ZM", "ZW",
)
private val SOUTH_AMERICA_COUNTRIES = setOf(
    "AR", "BO", "BR", "CL", "CO", "EC", "GY", "PE", "PY", "SR", "UY", "VE",
)
