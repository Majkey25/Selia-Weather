# Zásady ochrany soukromí

Platnost od: 24. srpna 2026

Počasí Česko nevyžaduje účet a neobsahuje analytiku, reklamy ani reklamní SDK. Vývojář neprodává osobní údaje a nepoužívá je k profilování.

## Data v zařízení

Aplikace ukládá poslední vybrané město, oblíbené lokality, poslední úspěšnou předpověď a nastavení widgetu do interního úložiště. Záloha Androidu a přenos do jiného zařízení jsou pro tato data zakázané. Vymazání dat nebo odinstalace aplikace je odstraní.

## Poloha

Oprávnění k přibližné nebo přesné poloze je volitelné. Po použití tlačítka „Použít moji polohu“ aplikace zjistí souřadnice, ověří, že leží v Česku, a použije je k pojmenování místa a načtení předpovědi. Aplikace nesleduje polohu na pozadí.

## Síťová komunikace

- Open-Meteo dostává hledaný název místa nebo souřadnice vybrané lokality a poskytuje geokódování a předpověď.
- Systémová služba Android Geocoder může dostat souřadnice aktuální polohy za účelem pojmenování místa.
- ČHMÚ poskytuje radarové, nowcastové, bleskové a družicové snímky. Tyto servery při běžném HTTPS spojení zpracují technické údaje, například IP adresu a údaje požadavku.

Veškerá síťová komunikace aplikace používá HTTPS. Aplikace neposílá jméno, e-mail, reklamní identifikátor, kontakty ani obsah zařízení.

## Uchování a volby uživatele

Vývojář neprovozuje vlastní server pro ukládání údajů aplikace. Data v zařízení zůstávají do vymazání dat nebo odinstalace. Oprávnění k poloze lze kdykoli odebrat v nastavení Androidu. Zpracování na serverech Open-Meteo a ČHMÚ se řídí podmínkami těchto poskytovatelů.

## Kontakt

Dotazy k soukromí lze poslat přes [veřejný support formulář projektu](https://github.com/Majkey25/Pocasi-Cesko/issues/new).
