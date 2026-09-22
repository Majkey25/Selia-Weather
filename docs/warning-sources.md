# Official weather warnings

Verified on 2026-09-22. Coverage is currently Czechia and the United States. An unavailable or unsupported provider is never reported as an all-clear. Issued warnings for future events are included until their expiry.

## Czechia

- Current ČHMÚ CAP 1.2 snapshot: <https://vystrahy-cr.chmi.cz/data/XOCZ50_OKPR.xml>.
- Official warning page: <https://vystrahy-cr.chmi.cz/>.
- CAP documentation: <https://opendata.chmi.cz/meteorology/weather/alerts/metadata/Dokumentace_CAP.pdf>.
- Public coordinate lookup: <https://ags.cuzk.gov.cz/arcgis/rest/services/RUIAN/MapServer/14/query>. Send WGS84 longitude,latitude as `geometry`, `geometryType=esriGeometryPoint`, `inSR=4326`, `spatialRel=esriSpatialRelIntersects`, `outFields=kod`, `returnGeometry=false`, `f=json`.

The live CAP snapshot uses `CISORP` codes and has no polygons. The ČÚZK point lookup returns a different RÚIAN code. `app/src/main/assets/csu-ruian-orp.csv` converts those codes using the official ČÚZK conversion table. The asset retains the numeric columns, removes names, and uses UTF-8. Source archive: <https://www.cuzk.gov.cz/Uvod/Produkty-a-sluzby/RUIAN/2-Poskytovani-udaju-RUIAN-ISUI-VDP/Informace-o-uzemni-identifikaci/ruian_prevodniky.aspx>. Metadata: <https://www.cuzk.gov.cz/ruian/Poskytovani-udaju-ISUI-RUIAN-VDP/Informace-o-uzemni-identifikaci-(2).aspx>. Downloaded 2026-09-22, 206 mappings. Verified Brno `1317 -> 6203` and Praha `19 -> 1100` against live point lookups.

Attribution: ČHMÚ; ČÚZK, 2026; ČÚZK – on-line. ČHMÚ open data are available under [CC BY 4.0](https://www.chmi.cz/-/jak-mohu-pou%C5%BE%C3%ADvat-otev%C5%99en%C3%A1-data-%C4%8Dhm%C3%BA-). ČÚZK publishes [data terms](https://www.cuzk.gov.cz/Predpisy/Podminky-poskytovani-prostor-dat-a-sitovych-sluzeb/Podminky-poskytovani-prostorovych-dat-CUZK.aspx) and [network-service terms](https://www.cuzk.gov.cz/Predpisy/Podminky-poskytovani-prostor-dat-a-sitovych-sluzeb/Podminky-poskytovani-sitovych-sluzeb-CUZK.aspx). The application must keep this attribution and links accessible to users.

The current national CAP message replaces the prior snapshot. Parse the requested language, exact ORP membership, effective time and expiry. ČHMÚ explicit green blocks use `responseType=None` or `certainty=Unlikely`; severity `Minor` alone is not an all-clear. CAP permits warnings without an expiry until cancelled, but the app treats an active local warning without an explicit expiry as unavailable. Altitude-qualified warnings retain the issuer's text; the app has no verified altitude measurement.

## United States

Use `https://api.weather.gov/alerts/active?point=LAT,LON`, with an identifying User-Agent. [NWS documentation](https://www.weather.gov/documentation/services-web-api) allows free use; the rate limit is intentionally unpublished. [NWS geolocation guidance](https://www.weather.gov/media/documentation/docs/NWS_Geolocation.pdf) recommends point queries to include both county and forecast-zone warnings. A forecast-zone-only query can omit tornado and thunderstorm warnings.

Manual/map pins and GPS without reverse geocoding can lack a country code. Coordinates within the Czech candidate bounds use the exact ČÚZK lookup even if their region is unknown; the bounds alone never establish warning coverage. Other unknown-country coordinates first call the documented `https://api.weather.gov/points/LAT,LON` endpoint. Only a matching Point feature with an official `api.weather.gov` point identifier and forecast-zone URL enables NWS routing. Coverage lookup uses the API's four-decimal coordinate precision; the subsequent alert query keeps the full original coordinates. One exact-coordinate positive lookup is cached for 24 hours. HTTP failures, missing coverage, mismatched coordinates, and invalid metadata report unavailable without querying alerts. Live NYC returned a matching feature and `NYZ072`; London returned HTTP 404 `InvalidPoint`.

The response is a GeoJSON FeatureCollection. Parse CAP-derived `properties`, ignore cancelled/non-actual messages, remove messages superseded by references, and enforce effective/expiry/end times. The source's point query performs geographic selection; missing GeoJSON geometry is valid for county/zone alerts. NYC's live point response returned HTTP 200 and an empty FeatureCollection with a current `updated` timestamp.

## UK and wider coverage

UK currently reports unavailable and links to [Met Office Weather Warnings](https://weather.metoffice.gov.uk/warnings-and-advice/uk-warnings). The direct [Met Office NSWWS API](https://datahub.metoffice.gov.uk/docs/g/category/warnings/overview) is free within its quota but requires registration and an API key. Its Atom index links GeoJSON MultiPolygons. The [official FAQ](https://datahub.metoffice.gov.uk/support/faqs) documents 20,000 requests/day, a 200/second burst limit, and a recommended one-minute poll.

[Meteoalarm Atom feeds](https://feeds.meteoalarm.org/) remain public. Legacy RSS stopped updating on 2026-01-14. Live UK Atom was empty; a German Atom warning and its CAP document contained geocodes but no geometry. Country or region names cannot establish exact location membership. The newer EDR API [requires a token](https://api.meteoalarm.org/edr/v1/authentication), and its [GeoJSON geometry is only a bounding box](https://api.meteoalarm.org/edr/v1/faq). None of these are enabled as an exact-location fallback.

Other countries report unsupported and link to [WMO's official source directory](https://severeweather.wmo.int/sources.html). WMO's Alert Hub is labelled a demo; no verified global, no-key point-query service was found. Global coverage must not be claimed.

## Freshness and failure handling

The repository keeps one exact-coordinate ORP lookup for at most 24 hours and one source response for 60 seconds. It never reuses cached success after a failed refresh. Expiry is rechecked on every access. Payloads older than 48 hours, or more than five minutes in the future, are treated as unavailable. This is a conservative application limit, not a provider SLA. HTTP requests use bounded reads, timeouts, no redirects, and no persisted coordinate history. CAP doctypes and entity declarations are rejected before parsing; external entities are blocked.
