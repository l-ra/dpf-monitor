# DPF Monitor 1.0.1 — patch release

## Opravy

- Vlastní adaptivní ikona aplikace: stylizovaný filtr DPF a šipka regenerace, kompatibilní s kruhovým i jinak maskovaným launcherem. Android 13+ může použít monochromatickou ikonku v barevném motivu.
- Vlastní jednobarevná ikona notifikací namísto výchozí výstražné ikonky Androidu.
- Android 15 / SDK 35: dashboard a spodní navigační tlačítka respektují insets systémové stavové lišty, oblasti výřezu fotoaparátu a spodního systémového navigačního panelu (gesta i tlačítka).
- Správný kontrast systémových ikon na tmavém pozadí v režimu edge-to-edge.
- Číslo verze na obrazovce a v názvu exportu se odvozuje z verze skutečně nainstalovaného APK.

## Kompatibilita a data

- Pouze UI a grafické prostředky; diagnostické PID/DID, stavový automat a BLE protokol se nemění.
- Zůstává zachován balíček cz.dpf.monitor, podepisovací klíč, SQLite historie (30 dnů), vazba adaptéru a VIN.
- Aktualizace 1.0.0 → 1.0.1 má zachovat data bez nové konfigurace.

## Ověření

- GitHub Actions build Android APK a JUnit testy.
- Doporučený terénní test na Androidu 15: kontrola horní stavové lišty a spodního indikátoru gest při přepínání Přehled / Grafy, otočení telefonu a otevření nabídky nastavení.
- Skutečné rozmístění na konkrétním telefonu po instalaci zatím nelze ověřit vzdáleně.
