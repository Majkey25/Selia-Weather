# Soukromí

Počasí Česko nevyžaduje účet a neobsahuje analytiku ani reklamní SDK.

## Data v zařízení

Aplikace ukládá poslední vybrané město, poslední úspěšnou předpověď a nastavení widgetu do interního `SharedPreferences`. Android backup a přenos do jiného zařízení jsou pro tato data zakázané. Odinstalace aplikace data odstraní.

## Síťová komunikace

- `api.open-meteo.com` poskytuje předpověď.
- `geocoding-api.open-meteo.com` hledá české lokality.
- `produkty.chmi.cz` poskytuje radar ČHMÚ.

Aplikace neposílá jméno, e-mail, reklamní identifikátor ani přesnou polohu zařízení. Dotaz na počasí obsahuje pouze souřadnice města vybraného uživatelem.
