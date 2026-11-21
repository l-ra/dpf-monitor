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
