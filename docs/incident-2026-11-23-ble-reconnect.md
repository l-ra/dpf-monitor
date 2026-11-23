# Incident 2026-11-22/23: Automatické obnovení BLE

## Zdroj a soukromí

Anonymizovaný rozbor uživatelem přiloženého archivu DPF Monitor v1.0.2 z 23. 11. 2026. Originál obsahuje VIN a časy jízd; **není vložen do veřejného repozitáře**.

- 6 394 diagnostických vzorků; 11 989 řádků ELM komunikace.
- 211 LINK ERROR: 163 připojovacích timeoutů, 45 timeoutů GATT 147 (0x93), dvě nedostupné ECU a jeden chybějící ELM prompt.
- 22.11. 13:00:52 UTC: první úspěšné BLE spojení a ověřené VIN.
- 22.11. 21:14:52 UTC: poslední řada ECU NO DATA, pravděpodobně uspané ECU.
- 22.11. 21:35:20 UTC: začínají opakované neúspěšné pokusy o BLE reconnect.
- 23.11. 06:28:34 UTC: GATT se nakrátko spojí, ECU stále nedostupná.
- 23.11. 06:37–06:40 UTC: znovu GATT timeouty.
- 23.11. 06:41:01 a 06:41:19 UTC: No paired adapter v době ručního nového přiřazování.
- 23.11. 06:42:41 UTC: GATT funguje a ověření VIN a telemetrie jsou úspěšné.

Časy UTC; pro český CET přičíst 1 hodinu.

## Prokázané softwarové chyby ve v1.0.2

1. Callback AdapterPresenceService.onDeviceDisappeared bezpodmínečně volal stopService. Krátkodobé zmizení BLE tak mohlo zastavit monitoring bez zaručeného následného callbacku appeared.
2. Menu Znovu připojit volalo pouze MonitorService.start. Při běžícím workeru onStartCommand žádnou skutečnou obnovu neprovedl.
3. GATT připojení opakovalo uloženou adresu naslepo, neověřovalo BLE reklamu ani jinou případně změněnou adresu.
4. BootReceiver obnovoval pouze Companion Device Manager pozorování. Při chybějící CDM události aplikace neměla další mechanismus probuzení.
5. Notifikace mohla nadále zobrazovat neaktuální hodnoty, protože mezi ECU a BLE offline nebyl plný rozdíl.

## Co z logů nelze potvrdit

Neznáme původní a novou BLE MAC adresu. Status 147 znamená timeout Bluetooth spojení, nikoli důkaz rotace adresy. Neznáme ani všechny Android CDM události před opravou, protože nebyly logovány.

## Oprava v1.0.3

- CDM disappeared neukončuje worker; spojení se obnovuje autonomně.
- BLE scan před reconnectem hledá původní MAC, případně jediný adaptér s přesně shodným názvem.
- Změna adresy je povolena až po ověření shody VIN s dříve přiřazeným vozidlem. Názvem samotným se žádný adaptér neautentizuje; dvě zařízení stejného jména znamenají odmítnutí automatického přepnutí.
- Android BluetoothLeScanner PendingIntent scan doplňuje CDM presence jako druhý mechanismus probuzení po ukončení procesu.
- Boot, aktualizace aplikace i zapnutí Bluetooth obnovují scan a monitoring, pokud je Android dovolí.
- Položka Znovu připojit restartuje probíhající GATT session.
- Vyhledávání BLE je zkusmo omezené backoffem do 30 s; při nepřítomnosti reklamy probíhají pouze občasné přímé pokusy.
- Log obsahuje spojovací události, scan, GATT status, start a konec služby a případné ověřené změny BLE adresy.
- Stará data v notifikaci jsou označená jako neaktuální.

## Akceptační testy

1. Po jednorázové asociaci zavřít UI, ukončit jízdu a další den nastartovat bez otevření aplikace. Do 1 minuty po dostupné BLE reklamě očekáváme telemetrii. Opakovat nejméně třikrát.
2. Během běžícího reconnect workeru použít Znovu připojit a ověřit log nové GATT session.
3. Krátký callback disappeared nesmí zastavit monitorovací službu.
4. Simulovat novou MAC a dvě zařízení OBDII: bez shodného VIN či při více kandidátech nesmí být žádná změna identity.
5. Ověřit boot, vypnutí/zapnutí Bluetooth a 10minutový zamčený displej.
6. Automatické unit testy a Android 15 emulator smoke test netestují skutečný hardware a neprokazují 100% dostupnost na konkrétním telefonu.

## Omezení

Android po Force stop může blokovat další automatické starty do ručního otevření aplikace. Zařízení bez napájení či bez BLE reklam není možné najít. Shoda VIN ověřuje automobil, ale bez kryptografické vazby nelze stoprocentně prokázat totožnost fyzického BLE donglu.

Reference: Android developer docs /connectivity/bluetooth/ble/background; BluetoothGatt GATT_CONNECTION_TIMEOUT; CompanionDeviceManager.
