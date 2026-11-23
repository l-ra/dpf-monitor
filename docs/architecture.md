# Architektura aplikace

## Přehled

Systém tvoří MainActivity (UI), AdapterPresenceService (Companion Device Manager), MonitorService (foreground služba typu connectedDevice), BleElm (GATT transport), ObdProtocol (ISO-TP parser), RegenEngine (čistý stavový automat) a LocalStore (perzistence).

- Companion Device Manager při prvním použití asociuje jediný BLE adaptér; následně má probouzet službu podle přítomnosti. BootReceiver obnovuje pozorování přítomnosti, ale nespouští přímo foreground service při bootu.
- MonitorService je oddělen od obrazovky a může fungovat při zamčeném telefonu. Služba vykonává sériové dotazy a obnovuje spojení s exponential backoff.
- BLE adaptér: služba FFF0, zápis FFF2, notifikace FFF1. ELM327 v2.1, CAN 11bit 500 kbit/s, adresa ECU 7E0, standardní OBD funkční adresa 7DF.
- VIN musí být přečteno z ECU (0902 nebo 22F190). Teprve potom se povolí monitoring. Při změně VIN je přístup odmítnut.
- LocalStore ukládá MAC adaptéru, VIN, stavový automat, události a poslední diagnostická data lokálně. Android backup je zakázán.
- NotificationChannels: trvalá stavová, vysoce prioritní události a hlasitá aktivní regenerace s alarm audio usage.

## Sekvence

1. Asociace zařízení prostřednictvím systémového výběru.
2. Výskyt BLE -> systém zavolá CompanionDeviceService -> spuštění MonitorService.
3. GATT spojení, ELM inicializace, čtení ECU VIN, porovnání s lokální vazbou.
4. Každý cyklus: PID 018B přibližně 1,5 s; teploty cca 8 s; ostatní DIDs cca 20 s, vždy serializovaně.
5. ObdProtocol -> RegenEngine -> uložit fázi a okamžitě odeslat notifikaci při přechodu.
6. Při výpadku BLE během regenerace označit UNKNOWN/UNRESOLVED; při dalším spojení opět ověřit VIN a čítače.

## Známé limity

- Android a výrobce telefonu mohou na pozadí omezovat BLE, foreground service a asociace. Nutný provozní test na konkrétním telefonu a nastavení baterie.
- Některé levné ELM adaptéry vracejí pouze první rámec ISO-TP; pro VIN to nestačí. Aplikace proto může oprávněně odmítnout aktivaci, dokud VIN nelze bezpečně přečíst.
- Pokud je adaptér stále pod proudem i po vypnutí motoru, samotná přítomnost BLE nemusí vymezovat jednotlivé jízdy. Vyhodnocení jízdy se musí dále doplnit o RPM a ověření napájecího režimu.
- Data jsou experimentální, pouze read-only. Nejsou náhradou za originální diagnostiku ani pokyny k bezpečné jízdě.


## Změny ve verzi 1.0.0

**UI a životní cyklus:** MainActivity vykresluje hlavní dashboard a detailní obrazovku s grafy pomocí Android Views a vlastního TelemetryChart (Canvas). Žádná obrazovka nevlastní BLE spojení. První spuštění vede uživatele systémovým udělením oprávnění, zapnutím Bluetooth a Companion Device Manager výběrem adaptéru; teprve poté MonitorService načte a potvrdí VIN.

**Retence:** TelemetryDatabase je singleton SQLiteOpenHelper s WAL a indexy podle času / (command, time). Zapisuje všechny pokusy o čtení ECU do tabulky samples včetně error/partial, komunikační text do communications a notifikované události do events. Automatické mazání záznamů starších 30 dnů probíhá při otevření databáze, při spuštění aplikace a nejméně jednou za hodinu při aktivním zápisu. Stav motoru, vazba VIN/MAC a aktuální dashboard zůstávají v SharedPreferences nezávisle na retenci analytické historie.

**Grafy:** TelemetryChart je jednoduchý Canvas vykreslovač s barevně odlišenými řadami a dělením os; databázový dotaz seskupuje hodnoty do časových intervalů a zprůměruje je pro požadovaný rozsah 1/7/30 dní. Dotazy probíhají mimo UI thread.

**Export:** MainActivity spouští Android ACTION_CREATE_DOCUMENT a předává přidělený URI do background threadu. TelemetryDatabase exportZip() postupně zapisuje ZIP přímo do uživatelem vybraného OutputStreamu – telemetry.csv, communication.txt, events.csv, README.txt. Soubory se nemusí kopírovat na veřejnou cestu a aplikace nepotřebuje storage permissions. Export obsahuje případné VIN; jde o data lokálního vozidla.

**Release:** build.gradle.kts přebírá verzi z release/version.txt, versionCode je monotonicky odvozený. Nový Versioned Android release workflow po změně verze sestavuje a testuje, vytváří GitHub Release se samostatným, verzovaným APK a SHA-256. Release APK musí být podepsané trvalým keystore v GitHub Secrets; bez něj se zveřejní výhradně označený testovací prerelease.


## Recovery-first BLE architektura (1.0.3)

- AdapterPresenceService: CDM appeared je budicí impuls, ale disappeared **nevolá stopService**. Je-li služba již aktivní, nový impuls urychlí reconnect.
- PersistentAdapterScan: oslovuje BluetoothLeScanner se systémovým PendingIntent; explicitní neveřejný AdapterScanReceiver může obnovit proces i bez otevřené Activity a požádat o spuštění connectedDevice FGS. Při omezení startu Androidem loguje chybu. Scan se při vypnutí automatiky odregistruje.
- BootReceiver: při BOOT_COMPLETED, MY_PACKAGE_REPLACED a přechodu Bluetooth do STATE_ON obnoví CDM pozorování, persistentní BLE scan a monitoring. Start může odmítnout OEM/Android, proto se výsledek zapisuje do diagnostiky.
- AdapterDiscovery: před každým novým GATT spojením proběhne krátký aktivní scan známé MAC a jediného alternativního zařízení se shodným BLE jménem. Při více alternativách se nic nepřepáruje. Pokud reklama chybí, provede se pouze každý třetí pokus přímé GATT navázání původní MAC.
- BleRecoveryPolicy: změna BLE adresy se neuloží bez dříve ověřeného a zcela shodného VIN. Toto ověřuje identitu vozidla, nikoli kryptograficky konkrétní dongle.
- MonitorService: stav Hledám BLE / BLE připojeno, ECU spí / Monitoruji DPF je oddělen a perzistován, notifikace neoznačuje stará měření jako aktuální. Reconnect má vlastní akci ACTION_RECONNECT; vyvolá close předchozího GATT a probudí běžící worker.
- BleElm: protokol a dekodér beze změny; callbacky opožděných GATT objektů se ignorují. Všechny GATT stavy a chyby se logují.
- Backoff: při chybě exponeciálně do 30 sekund, s omezeným 7s aktivním BLE scanem; po znovunalezení zařízení žádné lidské párování. Sleep ECU neznamená ztrátu BLE.
- Praktický požadavek: pro dlouhodobý automatický provoz je nutné na konkrétním Android telefonu povolit neomezené běžení na pozadí; systémové Force stop nemůže aplikace sama obejít.

Podkladová analýza: [Incident 23.11.2026](incident-2026-11-23-ble-reconnect.md).
