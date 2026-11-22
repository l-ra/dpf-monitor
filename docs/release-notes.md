# DPF Monitor 1.0.0

První nativní Android verze pro průběžné sledování regenerace DPF v kontextu konkrétního BLE ELM327 adaptéru a vozidla.

## Hlavní změny

- Automatické první nastavení včetně systémových Bluetooth oprávnění, volby jediného adaptéru a ověření kompletního VIN z ECU.
- Automatické sledování při příštích jízdách pomocí Companion Device Manager, foreground service typu connectedDevice a obnovení sledování po restartu telefonu či aktualizaci aplikace. Dosažitelná spolehlivost závisí na Androidu, BLE adaptéru a nastavení telefonu.
- Přehledná hlavní obrazovka: stav DPF, normalizované kritérium regenerace, vzdálenost/čas od poslední regenerace, saze, teploty, délka aktivní regenerace a orientační čas do dokončení či dalšího spuštění.
- Obrazovka Grafy: vývoj teplot, sazí, kritéria, vzdálenosti a délky regenerace, interval 24 h / 7 dní / 30 dní.
- Automatická notifikace blížící se regenerace, hlasitý alarm začátku, potvrzení dokončení z resetu čítačů a upozornění na neověřenou či pravděpodobně přerušenou regeneraci.
- Trvalá lokální SQLite historie měření a celé komunikace ELM327, retence 30 dnů, export jediného ZIP pomocí Android Storage Access Framework (telemetry.csv, events.csv, communication.txt).
- Dokumentovaná interpretace PID/DID a testy nad zaznamenanou jízdou s dokončenou regenerací.

## Důležitá omezení

Aplikace je **experimentální diagnostický nástroj**, ne certifikovaná součást vozidla. Úspěšné přečtení VIN a provoz při zamčeném telefonu po restartu je nutné ověřit na konkrétním ELM adaptéru a telefonu. Levné ELM327 adaptéry mohou vracet neúplné ISO-TP odpovědi a pak bezpečné ověření VIN selže. Bez ověřeného VIN se monitor nespustí. Po prvním použití doporučujeme ověřit, že telefon povoluje aplikaci neomezenou aktivitu na pozadí a upozornění.

Pokud GitHub Actions nemá nahraný stabilní release keystore, vydá se **testovací debug APK**, označený jako prerelease, s přímým .apk souborem. Aktualizace takového APK může vyžadovat přeinstalování kvůli podpisu. Pro stabilní aktualizace nastavte čtyři Android signing secrets.

Data diagnostiky mohou obsahovat VIN a přesné časy jízd. Exportované ZIP soubory sdílejte uvážlivě.
