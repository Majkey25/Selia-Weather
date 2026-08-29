package cz.majkey.pocasicesko.data

internal enum class ForecastRegion {
    CZECHIA,
    EUROPE,
    NORTH_AMERICA,
    GLOBAL,
}

internal fun forecastRegionFor(location: CzechLocation): ForecastRegion = when {
    location.isInCzechia() -> ForecastRegion.CZECHIA
    location.latitude in EUROPE_LATITUDE && location.longitude in EUROPE_LONGITUDE -> {
        ForecastRegion.EUROPE
    }
    location.latitude in NORTH_AMERICA_LATITUDE && location.longitude in NORTH_AMERICA_LONGITUDE -> {
        ForecastRegion.NORTH_AMERICA
    }
    else -> ForecastRegion.GLOBAL
}

internal fun forecastSourcesFor(region: ForecastRegion): Set<String> = when (region) {
    ForecastRegion.CZECHIA -> EUROPE_SOURCES + "chmi-aladin-cz-1km"
    ForecastRegion.EUROPE -> EUROPE_SOURCES
    ForecastRegion.NORTH_AMERICA, ForecastRegion.GLOBAL -> GLOBAL_SOURCES
}

private val GLOBAL_SOURCES = setOf(
    "ecmwf-aifs-open",
    "ecmwf-ifs-open",
    "noaa-gefs",
    "noaa-gfs",
)
private val EUROPE_SOURCES = GLOBAL_SOURCES + "dwd-icon-eu"

private val EUROPE_LATITUDE = 34.0..72.0
private val EUROPE_LONGITUDE = -25.0..45.0
private val NORTH_AMERICA_LATITUDE = 7.0..84.0
private val NORTH_AMERICA_LONGITUDE = -170.0..-50.0
