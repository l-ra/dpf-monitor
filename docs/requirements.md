# Technické zadání – DPF Monitor

## Účel

Lokální nativní Android aplikace pro *jeden předem schválený* BLE ELM327 adaptér a *jedno vozidlo ověřené VIN z ECU*. Při každé jízdě se má automaticky připojit, odečítat DPF stav a poskytovat srozumitelná upozornění. Sběr není závislý na otevřené Activity.

## Funkční požadavky a akceptace

| ID | Funkce | Akceptační kritérium |
|---|---|---|
| F-01 | První asociace BLE | Systémový Companion Device Manager, lokální MAC; pouze FFF0/FFF1/FFF2 adaptér v implementované verzi |
| F-02 | Ověření automobilu | Přečíst úplné VIN 0902 nebo 22F190, navázat VIN k MAC. Neúplné VIN se odmítne; jiné VIN nesmí vyvolat monitoring |
| F-03 | Automatický start | Po objevení asociovaného BLE zařízení proběhne callback služby a pokus o připojení bez otevření UI |
| F-04 | Pozadí | Foreground connectedDevice service, průběžná notifikace, reconnect s exponenciálním backoff a logování důvodů výpadku |
| F-05 | Telemetrie | 018B preferovaně <3 s; teploty ~8 s; ostatní DIDs ~20 s. Jednotlivé chyby nesmějí zničit poslední validní hodnotu |
| F-06 | Blížící se regenerace | Jednorázová notifikace po dosažení 85 % normalizovaného kritéria (experimentální indikátor, ne jistá událost) |
| F-07 | Spuštění | Dva po sobě jdoucí aktivní rámce 018B; vysokoprioritní notifikace a zvuk typu ALARM |
| F-08 | Potvrzení dokončení | Zaniklá aktivní indikace + reset DID 22208D a 222089, potvrzeno na 2026-11-21 jízdě |
| F-09 | Přerušení | Bez resetu čítačů při dostupné ECU >=90s -> pravděpodobně přerušeno; ztráta BLE -> pouze neověřený konec |
| F-10 | Příští jízda | Perzistentní nezakrytý neověřený/přerušený výsledek po novém ověření VIN připomenout |
| F-11 | UI | Km a čas od poslední regen, stav a čas aktivní fáze, odhad dokončení, odhad času do dalšího startu, kvalita/stáří dat |
| F-12 | Export | Diagnostický textový log umožnit sdílet bez backendu |
| F-13 | CI | GitHub Actions vytváří instalovatelné debug APK a volitelně podepsané release APK, provádí jednotkové testy |
| F-14 | Dokumentace | Architecture, detection, user-guide, requirements + obě zdrojová zachycení a reprodukční testy v repo |

## Nefunkční požadavky

- Bez zápisových diagnostických služeb; žádné 04, 2E, 31, 34 nebo nucená regenerace.
- Zpracovávat fragmentované BLE notifikace a ISO-TP rámce. Zpožděná odpověď nesmí být přiřazena jinému PID. Po timeoutu ELM promptu zahodit spojení.
- Nedetekované či zastaralé hodnoty nezobrazovat jako živé; výsledky, které nejsou podložené, označovat jako experimentální.
- Data na telefonu v privátním úložišti, bez automatického síťového přenosu a bez zálohování VIN.
- Android 12+ (minSdk 31), cílově Android 15; povolení Bluetooth scan/connect, notifications, a případně vypnutí OEM bateriových restrikcí.
- Nekomunikovat s BLE během konfigurační aktivity jiné aplikace (jednotlivý adaptér obvykle zvládne jedno připojení).

## Testovací plán pro akceptaci na telefonu

A. Bez vozidla: unit tests proti reálným rámcům 018B aktivní, pasivní, ECU timeout, NRC 78 a reset čítačů.
B. V zaparkovaném voze: spárování, zisk VIN, zavřít UI, zhasnout displej na 10 minut, ověřit dostupnost FGS a zachování logu.
C. Odstranit adaptér během ACTIVE: nesmí hlásit jednoznačně přerušeno; příští jízdu upozornit na neověřený konec.
D. Vypnout motor u probíhající regen: zapsat pending, při dalším běhu porovnat čítače.
E. Dokončená regen: porovnat začátek a konec s Car Scannerem; musí být jedna hlasitá notifikace START a potvrzení COMPLETED po obou resetech.
F. Restart telefonu a vypnutí/zapnutí Bluetooth: ověřit CDM presence callback a start bez otevření UI.
G. Zaměnit ECU / nechat přečíst jiné VIN: služba odmítne sběr.
H. Aktualizovat APK podepsané stejným release klíčem bez ztráty lokálního stavu.
I. OEM bateriové restrikce: kvalifikovat spolehlivost na konkrétním modelu; nelze slíbit stoprocentní běh bez tohoto testu.

## Stav projektu

První implementace a unit tests jsou v repozitáři. **Bod F-02, F-03, F-04, F-07 a F-09 vyžadují skutečný Android/OBD provozní test.** Zachycené webové logy neobsahují úplnou odpověď VIN a prokazují problematický vícerámcový ISO-TP transport levného ELM327, proto nelze funkční identifikaci ECU zatím považovat za ověřenou.


## Dodatky 1.0.0

- **F-15 Dashboard:** na první obrazovce jednoznačný stav regenerace, normalizované kritérium, km/čas od poslední regenerace, saze, teploty, uplynulá doba a ETA obou změn; neaktuální měření musí být viditelně označená. Technické volby dostupné přes ☰.
- **F-16 Grafy:** nezávislá obrazovka s grafy za 24 hodin / 7 dní / 30 dní a posledními událostmi. Kreslení prostřednictvím lokální databáze, bez serveru.
- **F-17 Retence:** lokální trvalá SQLite evidence všech diagnostických hodnot, chyb a kompletní komunikace ELM327, s časovým limitem 30 dní. Export ZIP pomocí Storage Access Framework, který zahrnuje telemetry.csv, communication.txt a events.csv. Export může obsahovat VIN.
- **F-18 Release:** MAJOR.MINOR.PATCH v release/version.txt; CI sestaví a otestuje APK a nahraje jej přímo mezi GitHub Release Assets pod verzovaným názvem. Actions Artifacts samotné GitHub poskytuje jako ZIP; release assets nikoli. Stabilní aktualizace vyžaduje release signing secrets.
- **F-19 Automatika:** po prvním systémovém přiřazení adaptéru a ověření VIN aplikace registruje Companion Device Presence, obnoví registraci po restartu a aktualizaci a foreground service pracuje bez otevřené Activity. Spolehlivost vyžaduje ověření podle výrobce telefonu a napájení adaptéru.

Provozní akceptační testy A–I stále zůstávají nutné před označením aplikace za terénně ověřenou. Verze 1.0.0 vyjadřuje sestavený funkční základ, nikoli certifikovanou diagnostiku.
