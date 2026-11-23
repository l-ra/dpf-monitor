# DPF Monitor – Android

Nativní monitor DPF pro Škoda Superb EA288 Evo přes přiřazený BLE ELM327 adaptér. Kotlin, Companion Device Manager, foreground service, lokální SQLite, notifikace a grafy.

## Instalace a nastavení

APK stáhni přímo ze stránky [GitHub Releases](https://github.com/l-ra/dpf-monitor/releases). Při prvním otevření aplikace automaticky požádá o Bluetooth oprávnění, případně oznámení, zapnutí Bluetooth a výběr jediného adaptéru OBDII. Po načtení a ověření úplného VIN z ECU naváže adaptér k automobilu. Další jízdy má sledovat automaticky i při zamčeném telefonu, pokud to Android a režim baterie umožní.

Hlavní obrazovka zobrazuje stav DPF, kritérium regenerace, km a čas od poslední regenerace, saze, teploty, délku aktivní fáze a orientační ETA. Obrazovka **Grafy** zobrazuje pět časových řad za 24 hodin, 7 nebo 30 dní. Pokročilé funkce jsou v nabídce ☰.

## GitHub Actions a přímé stažení APK

Verzi určuje soubor [release/version.txt](release/version.txt) (MAJOR.MINOR.PATCH). Jeho změna na main spustí workflow [Versioned Android release](.github/workflows/release.yml). To provede testy, sestaví APK a vytvoří GitHub Release s **přímo stažitelným APK v názvu dpf-monitor-vX.Y.Z-release.apk**; nejde o ZIP. Pokud nejsou nakonfigurována čtyři signing secrets, vydá se testovací prerelease s APK pojmenovaným dpf-monitor-vX.Y.Z-debug.apk.

Běžná CI workflow Android APK vytváří debug APK, ale jejich Actions Artifacts se stahují ve formátu ZIP (vlastnost GitHub Actions). Pro přímé APK použij GitHub Releases.

## Data a export

Veškerá měření, chyby, komunikace ELM327 i události se ukládají do soukromé SQLite databáze. Retence je 30 dní. ☰ → **Export diagnostiky · 30 dnů** uloží jeden ZIP do místa vybraného systémovým dialogem Androidu. Obsah: telemetry.csv, communication.txt, events.csv, README.txt. Archiv může obsahovat VIN a přesné časové údaje. Žádný cloud ani backend.

## Dokumentace

- [Technické zadání](docs/requirements.md)
- [Architektura](docs/architecture.md)
- [Detekce regenerace](docs/detection.md)
- [Uživatelský návod a podpis APK](docs/user-guide.md)
- [Podkladová jízda 2026-11-21](data/README.md)
- [Release notes](docs/release-notes.md)

## Omezení

Aplikace je experimentální a používá pouze čtecí diagnostiku. Některé ELM327 adaptéry nedokončí vícerámcové ISO-TP odpovědi; bez ověřeného VIN se monitoring z bezpečnostních důvodů neaktivuje. Android ani Companion Device Manager negarantují absolutní spolehlivost na pozadí, zejména při omezeních výrobce nebo vypnutí Bluetooth. Odhady regenerace nejsou doporučením k řízení vozidla.


## Automatické obnovení BLE (1.0.3)

Po incidentu 22.–23.11.2026 se aplikace nespoléhá jen na změnu přítomnosti Companion Device Manager: při výpadku nechává foreground monitoring běžet, aktivně vyhledává BLE reklamy, před připojením kontroluje uloženou MAC a u jediného kandidáta se změněnou adresou vyžaduje úplnou shodu dříve uloženého VIN. Dále používá persistentní PendingIntent BLE scan pro probuzení procesu a poslouchá restart/aktivaci Bluetooth. V menu Znovu připojit nyní skutečně ukončí stávající GATT pokus a naváže nové spojení.

Podrobnosti, skutečné chyby a akceptační scénáře: [Rozbor výpadku BLE z 23.11.2026](docs/incident-2026-11-23-ble-reconnect.md). Původní ZIP nebyl vložen do veřejného repozitáře, protože obsahuje VIN.

Technicky není možné zaručit 100% automatické probuzení po Android Force stop, odnětí Bluetooth oprávnění nebo vypnutí napájení donglu. Android doporučuje Companion Device Manager a PendingIntent BLE scan pro background detekci, ale i zde platí omezení na start foreground service.
