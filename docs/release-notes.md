# DPF Monitor 1.0.3 — automatické obnovení Bluetooth

Hlavní oprava: po ukončení jízdy, usnutí adaptéru nebo přerušení BLE může aplikace další jízdu automaticky obnovit bez nového systémového párování. Oprava vychází z diagnostiky v1.0.2 z 22.–23. 11. 2026.

## Změny

- Při CDM disappeared události se foreground monitor již nezastavuje.
- Aktivní BLE scan před reconnectem kontroluje původní MAC. Pokud adaptér vysílá pod novou adresou, aplikace může jediného kandidáta vyzkoušet, ale adresu uloží pouze po ověření **shodného, dříve uloženého VIN**. Při více kandidátech nedojde k přepárování.
- PendingIntent BLE scan dovolí systému probudit aplikaci při další BLE reklamě, vedle stávajícího Companion Device Manager.
- Po BOOT_COMPLETED, aktualizaci aplikace a zapnutí Bluetooth se registrují obě pozorování a pokud je to dovoleno, restartuje monitorovací služba.
- Opakování spojení má omezený backoff do 30 s; při chybějící reklamě se jen občas pokusí o přímé GATT spojení.
- Příkaz v menu Znovu připojit nyní skutečně ukončí starou GATT session a spustí nové hledání adaptéru.
- Samostatný stav BLE offline / ECU spí / živý monitoring; staré hodnoty nejsou vydávány za aktuální.
- Rozšířená diagnostika obsahuje spouštěcí události služby, GATT status, BLE scanning, boot a případné ověřené změny BLE adresy.
- Nové jednotkové testy bezpečného výběru BLE kandidátů a validace VIN. Dosavadní grafy, alarmy a 30denní databáze jsou zachované.

## Výchozí incident

V přiložených logách bylo 211 chyb linky: 163 timeoutů a 45 GATT status 147. Dalších 2 případů se týkala nedostupná ECU při existujícím BLE spojení a 1 timeout příkazu ELM. Po opětovné manuální asociaci komunikace fungovala.

Podrobnosti: [docs/incident-2026-11-23-ble-reconnect.md](incident-2026-11-23-ble-reconnect.md). Původní log není v veřejném repozitáři kvůli obsaženému VIN.

## Důležitá omezení a ověření

Tato verze zlepšuje zotavení, ale nepředstavuje garanci 100% provozu na pozadí. Android může po Force stop zakázat automatické spuštění a fyzický adaptér bez napájení ani reklamy není možné vyhledat. Změna adresy s ověřeným VIN potvrzuje pouze identitu automobilu, nikoli kryptografickou identitu donglu.

Po instalaci přes v1.0.2 **neodinstalovávat aplikaci**; stabilní release podpis má zachovat VIN, CDM asociaci i uloženou historii. Na telefonu povolit Bluetooth, notifikace, neomezený provoz na pozadí. Provést alespoň 3 následující jízdy bez otevření UI a z diagnostiky ověřit automatické připojení, každou cestu a obnovu po vypnutí zapalování.

CI testuje Kotlin unit tests a Android 15 emulator startup; konkrétní fyzický dongle se ověří až další reálnou jízdou.
