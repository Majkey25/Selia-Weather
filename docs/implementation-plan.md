# Počasí Česko implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vytvořit a vydat jednoduchou českou weather app pro Android 13+ s ALADIN předpovědí, radarem ČHMÚ a resizovatelným widgetem.

**Architecture:** Jeden Android app modul. `WeatherRepository` vlastní HTTP a JSON mapování. Compose vykresluje počasí a radar. Nativní `AppWidgetProvider` používá stejnou uloženou předpověď. `SharedPreferences` drží poslední lokaci, cache a nastavení widgetu.

**Tech stack:** Kotlin 2.3, Jetpack Compose Material 3, Android SDK 36, `HttpURLConnection`, `org.json`, native App Widgets, JUnit 4.

---

### Task 1: Project base

**Files:** Gradle wrapper, root Gradle files, `app/build.gradle.kts`, manifest, resources.

- [ ] Vytvořit minimální Android app s `minSdk = 33`, `targetSdk = 36` a namespace `cz.majkey.pocasicesko`.
- [ ] Přidat jen Compose, Material Icons a testovací závislosti.
- [ ] Přidat síťové oprávnění, hlavní aktivitu a widget provider.
- [ ] Spustit `gradlew.bat :app:assembleDebug --console=plain`.

### Task 2: Forecast data

**Files:** `app/src/main/java/cz/majkey/pocasicesko/data/WeatherModels.kt`, `WeatherRepository.kt`, test modelu.

- [ ] Definovat přesné immutable modely pro current, hourly, daily a českou lokaci.
- [ ] Volat `api.open-meteo.com/v1/forecast` s `models=chmi_aladin_seamless`, `forecast_days=14` a `timezone=Europe/Prague`.
- [ ] Volat geocoding s `countryCode=CZ` a odmítnout prázdný dotaz.
- [ ] Parsovat JSON jednou, kontrolovat délky souvisejících polí a propagovat konkrétní chyby.
- [ ] Uložit poslední úspěšnou odpověď a lokaci do `SharedPreferences`.
- [ ] Ověřit happy path, poškozený JSON a neznámý WMO kód v JUnit.

### Task 3: Weather UI

**Files:** `MainActivity.kt`, `ui/WeatherApp.kt`, `ui/WeatherTheme.kt`, `ui/WeatherIcons.kt`.

- [ ] Vykreslit edge-to-edge gradient, dominantní aktuální teplotu a jasný výběr města.
- [ ] Přidat 24hodinovou lištu, 14denní seznam a metriky srážky, vlhkost, vítr, tlak a východ/západ slunce.
- [ ] Přidat search sheet pouze pro české výsledky.
- [ ] Přidat loading, cached a retry stav bez překryvných dialogů.
- [ ] Přidat sémantické popisky a minimální dotykové plochy.

### Task 4: CHMI radar

**Files:** `ui/RadarScreen.kt`.

- [ ] Zobrazit oficiální interaktivní radar ČHMÚ v izolovaném WebView.
- [ ] Povolit jen HTTPS, zakázat file/content access a otevřít cizí domény v systémovém prohlížeči.
- [ ] Přidat lokální loading/error stav, obnovení a viditelnou atribuci.

### Task 5: Resizable widget

**Files:** `widget/WeatherWidgetProvider.kt`, `WidgetConfigActivity.kt`, layouty a widget XML.

- [ ] Vykreslit kompaktní a široký widget podle `OPTION_APPWIDGET_MIN_WIDTH`.
- [ ] Použít `TextClock`, uložené počasí a pending intent do aplikace.
- [ ] Přidat čtyři vzhledy a přepínače čas, ikona a detail.
- [ ] Aktualizovat všechny widgety po úspěšném načtení dat a při změně velikosti.

### Task 6: Documentation and release

**Files:** `README.md`, `CHANGELOG.md`, `LICENSE`, `.github/workflows/android.yml`, release assets.

- [ ] Popsat funkce, datové zdroje, přesnost, soukromí, build a omezení 14denního výhledu.
- [ ] Přidat screenshoty pouze z reálného emulator běhu.
- [ ] Spustit test, lint a debug/release build.
- [ ] Provést emulator scénáře: online Praha, české hledání, síťová chyba, starý workflow po reloadu, radar a widget config.
- [ ] Vytvořit veřejné GitHub repo, pushnout ověřený stav a vydat `v0.1.0-beta.1` s APK a SHA-256.
