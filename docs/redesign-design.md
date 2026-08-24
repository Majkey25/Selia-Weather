# Redesign Počasí Česko

## Směr

Rozhraní používá principy Gradient Weather, ne jeho přesnou kompozici. Počasí určuje celou barevnou atmosféru. Hlavní hodnota, teplota, zůstane uprostřed bez vedlejší grafiky. Detailní data se přesunou do několika velkých ploch s jasným účelem.

## Hierarchie

1. Kompaktní chip lokality a obnovení.
2. Centrovaný stav, ikona, teplota, pocitová teplota a denní rozsah.
3. Jeden hodinový graf s teplotní křivkou a srážkami.
4. Horizontální řada rychlých metrik.
5. Čtrnáctidenní seznam s relativním teplotním rozsahem.
6. Plovoucí navigace Počasí / Mapy.

## Vizuální systém

- Tmavé weather-reactive palety s hloubkou tvořenou dvěma jemnými světelnými plochami.
- Jasný den: tmavá oceánská modř, bez světlého modrého pozadí přes celou obrazovku.
- Déšť a mlha: šedomodrá, nízká saturace.
- Noc: inkoustová modř s jemnými body, bez dekorativního vesmíru.
- Typografie: velká teplota 104sp, názvy sekcí 20sp, sekundární text nejméně 13sp.
- Povrchy: tmavé průsvitné plochy 24–30dp, tenký světlý okraj, žádné stíny.
- Navigace: jedna kompaktní pilulka, dvě položky. Mapy mají vlastní přepínač Radar / Družice.

## Hodinový graf

Graf ukáže šest hodin. Teploty spojí barevná křivka od tyrkysové po teplou žlutou. Nad body budou časy, pod nimi ikony a pravděpodobnost srážek. Graf musí zůstat čitelný bez animace a při zvětšeném písmu.

## Mapy

Radar zůstane oficiální interaktivní ČHMÚ WebView. Družice bude nativní obrazovka. Načte HTML index otevřených dat ČHMÚ, mechanicky vybere nejnovější `geo_vis-ir_cz.jpg`, ověří JPEG a zobrazí čas UTC. Chyba nikdy nebude vydávaná za prázdnou mapu.

ALADIN je numerický model, ne radar ani družice. Aplikace ho označí u předpovědi jako `ALADIN CZ 1 km`. Mapy budou správně označené jako radarové měření a družicový snímek.

## Lokality

Location sheet spojuje tři cesty: aktuální poloha zařízení, nejvýše 12 oblíbených českých míst a stávající vyhledávání. Oprávnění se žádá až po klepnutí na `Použít moji polohu`. Souřadnice mimo český bounding box aplikace odmítne. Oblíbené se ukládají jako validovaný typovaný JSON v interních preferences.

## Widget

Kompaktní 2×1 widget: město a velká teplota vlevo, čas a stav vpravo. Široký widget: horní řádek lokalita/čas, hlavní teplota a ikona, dole tři následující hodiny. Automatická paleta používá stejnou den/noc/déšť logiku jako aplikace. Světlý, tmavý a průhledný režim zůstanou.

Konfigurace zobrazí živý náhled před ovládacími prvky. Uživatel mění pouze vzhled, čas, ikonu a hodinový detail. Žádné další přepínače.

## Kompatibilita

Minimální verze je Android 10, API 29. Novější widget metadata pro Android 12+ jsou pouze progresivní rozšíření; základní `RemoteViews`, resize, konfigurace, síť a Compose obrazovky fungují i na API 29. Backup pravidla mají variantu pro API 29–30 i moderní `dataExtractionRules`.

## Ověření

- Parser ALADIN: 14 denních a 336 hodinových hodnot, první tři dny bez null hodnot.
- Radar: hlavní stránka, měřený snímek a nowcast soubor odpoví HTTP 200; WebView vykreslí mapu a ovládání.
- Družice: index a poslední český JPEG odpoví HTTP 200; aplikace ukáže snímek a jeho čas.
- Widget: launcher objeví provider, 2×1 i široký layout obsahují aktuální data a resize změní layout.
- Emulator: online, offline cache, hledání, mapové přepínání a návrat na předpověď.
