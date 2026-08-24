# Počasí Česko: produktový návrh

## Produkt

Počasí Česko je česká Android aplikace pro rychlou odpověď na jednu otázku: co bude dnes a v dalších dnech v mém městě. Cílí na lidi v Česku, kteří chtějí přesný lokální výhled bez reklam, účtu a složitého nastavování.

Hlavní akce je změna české lokality. Předpověď se po otevření načte pro poslední vybrané město. Radar a widget jsou dostupné bez procházení menu.

## Data

- Open-Meteo CHMI Forecast API, model `chmi_aladin_seamless`.
- ALADIN CZ 1 km pro první 3 dny. Open-Meteo navazuje ECMWF IFS HRES 9 km do 14 dnů.
- Open-Meteo geocoding omezený na `countryCode=CZ`.
- Interaktivní radar ČHMÚ v aplikaci. Zdroj i čas dat zůstávají viditelné.
- Bez API klíče. Poslední úspěšná předpověď se uloží lokálně pro offline stav a widget.

## Informační architektura

Spodní navigace má dvě položky: Počasí a Radar. Počasí obsahuje současný stav, dalších 24 hodin, 14denní seznam a stručné metriky. Klepnutí na název města otevře hledání českých obcí. Radar používá jednu účelovou obrazovku s obnovením a odkazem na zdroj.

Widget je jedna resizovatelná komponenta. Kompaktní velikost ukáže čas a teplotu. Široká velikost přidá město, stav a denní maximum/minimum. Konfigurace nabídne automatické barvy podle dne a počasí, světlou, tmavou a průhlednou variantu plus přepínače času, ikony a detailu počasí.

## Vizuální systém

- Material 3, edge-to-edge, Android 13+.
- Jedna souvislá plocha místo mřížky stejných karet.
- Pozadí používá tlumený vertikální gradient podle dne, noci a typu počasí. Gradient nese stav, není dekorativní mlha.
- Velká teplota je vstupní bod. Lokalita a stav jsou druhé. Hodinová předpověď je horizontální, denní výhled vertikální.
- 4dp základní krok, 20dp boční okraj, 32dp mezery mezi hlavními sekcemi.
- Výchozí systémové písmo. Čísla používají tabulární proporce, kde je to dostupné.
- Rohy 18dp pouze pro ovládací plochy. Žádné zbytečné stíny, sklo ani řada nesouvisejících karet.
- Minimální dotyková plocha 48dp, kontrastní text, čitelné popisky ikon a podpora zvětšeného písma.

## Stavy a chyby

Načítání zachová strukturu obrazovky a zobrazí jeden indikátor. Při síťové chybě zůstane poslední uložená předpověď s časem aktualizace. Bez cache aplikace ukáže konkrétní chybu a tlačítko Zkusit znovu. Prázdné hledání nic neodesílá. Výsledek mimo Česko se nezobrazí.

## Ověření

Build musí projít přes repo Gradle wrapper. Jednotkový test ověří mapování WMO kódů a parsování reálného tvaru API. Emulator QA ověří načtení Prahy, změnu města, radar, offline stav a konfigurační aktivitu widgetu. Vizuální kontrola proběhne na telefonu i úzkém widgetu pouze v emulátoru.
