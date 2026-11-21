# DPF Monitor (Android)

Nativní aplikace pro Škoda Superb / VAG EA288 Evo. Kotlin, BLE ELM327, CompanionDeviceManager, `connectedDevice` foreground service, notifikace, perzistentní historie a predikce. Projekt je experimentální; identifikaci VIN a provoz na konkrétním telefonu je nutné ověřit.

* [Technické zadání a akceptační testy](docs/requirements.md)
* [Architektura](docs/architecture.md)
* [Detekce regenerace a evidence](docs/detection.md)
* [Instalace, oprávnění, GitHub Actions](docs/user-guide.md)
* [Datové podklady](data/README.md)

## Sestavení

Workflow [Android APK](.github/workflows/android.yml) při každém push do `main` sestaví debug APK a provede jednotkové testy. Stažení: **Actions → Android APK → poslední úspěšný běh → Artifacts → dpf-monitor-debug**. Debug sestavení je podepsáno dočasným debug klíčem runneru (nová instalace může vyžadovat odinstalování předchozího APK). Pro dlouhodobé aktualizace nastavte stabilní release signing secrets podle [návodu](docs/user-guide.md).

## Omezení

Aplikace smí pouze číst. Nesmí se používat jako náhrada přístrojového panelu. Data z reálné regenerace 2026-11-21 podporují konkrétní DIDs; adapter ELM327 v2.1 stále vrací neúplný ISO-TP rámec 018B. Detekce využívá přijatý první rámec se sníženou jistotou a nezávislé potvrzení resetu čítačů. Android může proces zastavit, OEM omezení baterie mohou blokovat automatické spuštění. Bez úspěšného načtení VIN aplikace vozidlo nepřiřadí.
