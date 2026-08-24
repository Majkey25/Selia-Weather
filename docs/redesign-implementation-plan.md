# Počasí Česko Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Nahradit generické UI weather-reactive rozhraním inspirovaným Gradient Weather a dodat funkční radar, družici a nové widgety.

**Architecture:** Datová vrstva předpovědi zůstane. UI se rozdělí na forecast komponenty a mapovou obrazovku. Mapová obrazovka používá malý lokální viewer nad oficiálními snímky ČHMÚ. Widget dál používá `RemoteViews`, ale cache přidá tři hodinové body.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Canvas, HttpURLConnection, org.json, Android RemoteViews, ČHMÚ Open Data.

---

### Task 1: Forecast visual system

**Files:** `ui/WeatherApp.kt`, `ui/WeatherTheme.kt`, `ui/WeatherIcons.kt`

- [ ] Přidat tmavou weather-reactive paletu a vrstvené pozadí.
- [ ] Nahradit levou hero kompozici centrovanou teplotou a lokalitou v chipu.
- [ ] Nahradit hodinový seznam šestihodinovým Canvas grafem.
- [ ] Přidat metriky v horizontální řadě a denní teplotní range bary.
- [ ] Nahradit širokou spodní navigaci plovoucí pilulkou Počasí / Mapy.

### Task 2: Map hub and satellite

**Files:** `ui/MapHubScreen.kt`, `ui/RadarScreen.kt`, `assets/radar.html`

- [ ] Přidat segment Radar / Družice v jedné obrazovce.
- [ ] Parsovat pouze HTTPS odkazy končící `_geo_vis-ir_cz.jpg` a vzít poslední z indexu.
- [ ] Omezit stažený JPEG na 5 MB, ověřit MIME a dekódování bitmapy.
- [ ] Zobrazit loading, konkrétní chybu, čas UTC a obnovení.
- [ ] Přidat parser test pro validní index, prázdný index a neplatnou příponu.

### Task 3: Widget redesign

**Files:** `data/WeatherRepository.kt`, `widget/WeatherWidgetProvider.kt`, `widget/WidgetConfigActivity.kt`, widget layouty a drawables.

- [ ] Uložit tři následující hodinové časy, teploty a WMO kódy do omezené cache.
- [ ] Přestavět compact layout na město/teplotu + čas/stav.
- [ ] Přestavět wide layout na hero + tři hodinové body.
- [ ] Přidat živý Compose náhled do konfigurace.
- [ ] Ověřit světlý, tmavý, automatický a průhledný režim.

### Task 4: Verification

**Files:** `README.md`, emulator screenshots.

- [ ] Spustit `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain`.
- [ ] Ověřit ALADIN endpoint pro Prahu a Brno, 14 dní a bez null hodnot v prvních 72 hodinách.
- [ ] Ověřit HTTP 200 pro radar, nowcast a poslední družicový JPEG.
- [ ] Nainstalovat debug build jen na `emulator-5564` a otestovat předpověď, Mapy, družici, radar a offline cache.
- [ ] Ověřit skutečný widget přes launcher, resize a konfiguraci.
- [ ] Zachytit nové emulator screenshoty a provést finální diff review.

### Task 5: Android 10 compatibility

**Files:** `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml`, `README.md`

- [ ] Nastavit `minSdk = 29` a přidat legacy backup exclusions.
- [ ] Spustit lint s kontrolou `NewApi` a `InlinedApi` bez suppressů.
- [ ] Pokud je dostupný API 29 system image, nainstalovat a otevřít forecast, Mapy a widget config na Android 10 emulatoru.

### Task 6: Locations and favorites

**Files:** `data/DeviceLocationRepository.kt`, `data/LocationFavoritesCodec.kt`, `data/WeatherRepository.kt`, `ui/WeatherApp.kt`, manifest, codec test.

- [ ] Vyžádat coarse/fine location permission pouze po akci uživatele.
- [ ] Použít Android `LocationManager`, 15sekundový timeout a maximálně 30 minut starou last-known polohu.
- [ ] Odmítnout souřadnice mimo Česko a pojmenovat polohu systémovým Geocoderem mimo hlavní vlákno.
- [ ] Uložit maximálně 12 oblíbených lokalit přes validovaný JSON codec.
- [ ] Ověřit povolení, odmítnutí, emulator GPS fix a přetrvání po restartu.
