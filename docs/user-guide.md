# Uživatelský návod a release proces

## Instalace

Otevři [GitHub Releases](https://github.com/l-ra/dpf-monitor/releases) a stáhni přímo APK. Release APK je podepsaný stabilním keystore; pokud nebyl keystore nakonfigurován, vyjde testovací prerelease s debug APK. Debug podpis může být při každém CI běhu jiný, takže aktualizace může vyžadovat přeinstalaci aplikace.

## První spuštění

1. Při zapnutém zapalování a zaparkovaném vozidle připoj BLE OBDII adaptér (FFF0/FFF1/FFF2). Ukonči Car Scanner a jiné současně připojené aplikace.
2. DPF Monitor sám vyžádá Bluetooth a případně notifikační oprávnění, nabídne zapnutí Bluetooth a systémový výběr adaptéru.
3. Potom automaticky načte úplné VIN z ECU přes 0902 nebo 22F190. Bez validního VIN záměrně nepovolí sledování.
4. Po dokončení první konfigurace je automatické sledování zapnuto. Po další jízdě se aplikace může probudit při přítomnosti přiřazeného adaptéru; není nutné mít otevřenou hlavní obrazovku.

Nastav pro DPF Monitor v Androidu neomezenou aktivitu na pozadí, povol notifikační kanál „Hlasitá regenerace DPF“ a nastav hlasitost alarmů. Vypnutí Bluetooth, Force Stop či OEM úspory baterie mohou fungování blokovat.

## Rozhraní

**Přehled:** stav regenerace, kritérium, km/čas od poslední regenerace, vypočtené saze, teploty, uplynulá a odhadovaná zbývající doba aktivní fáze, ETA do dalšího spuštění.

**Grafy:** pět grafů z lokální databáze v rozsahu 24 h, 7 dní nebo 30 dní. Ve spodní části seznam událostí.

**☰ Nastavení:** export, přepínač automatického monitoringu, opětovné připojení, změna BLE adaptéru, oprávnění a informace o aplikaci.

## Upozornění

- Blížící se regenerace – odvozeno od normalizovaného kritéria nad 85 %, orientačně.
- Start aktivní regenerace – dva odpovídající rámce PID 018B a jednorázový hlasitý alarm.
- Potvrzení dokončení – vyžaduje reset čítačů vzdálenosti a času od regenerace.
- Pravděpodobné přerušení – ECU odpovídá, aktivní indikace zanikla bez resetu čítačů.
- Neověřený konec – ztráta spojení během regenerace; upozornění na další jízdě.

Nejde o certifikovanou součást vozidla. Nikdy nepokračuj v jízdě jen kvůli odhadu aplikace.

## Retence a export

Surová komunikace ELM327, odpovědi ECU, dekódované hodnoty i události se v SQLite uchovávají 30 dní. Starší záznamy se automaticky smažou při práci databáze a při spuštění aplikace.

V ☰ vyber Export diagnostiky · 30 dnů. Android zobrazí dialog pro vytvoření ZIP souboru v telefonu nebo na připojeném úložišti. Archiv obsahuje telemetry.csv, events.csv, communication.txt a README.txt. Obsahuje-li čtení VIN, může být VIN v exportu; archivy nesdílej veřejně.

## Automatický běh a restart telefonu

Companion Device Manager sleduje přítomnost přiřazeného adaptéru. AdapterPresenceService má při jeho výskytu spustit MonitorService (foreground service connectedDevice). BootReceiver obnovuje registraci přítomnosti při bootu a aktualizaci balíčku. Android nemusí automatiku povolit při Force Stop, vypnutém Bluetooth, ztracených oprávněních či omezeních výrobce. Spolehlivost je nutné ověřit na konkrétním telefonu a adaptéru.

Pokud ELM327 nedokáže dokončit ISO-TP při čtení VIN, bezpečné automatické navázání identity není možné a aplikace monitoring nespustí.

## GitHub Actions a verzování

Soubor release/version.txt určuje verzi ve formátu MAJOR.MINOR.PATCH. Push změny do main spustí workflow Versioned Android release, které kontroluje JUnit, sestaví APK, vypočte versionCode, vytvoří tag vX.Y.Z a GitHub Release s přímým .apk souborem.

Pro dlouhodobě aktualizovatelný release potřebuje GitHub Settings → Secrets and variables → Actions následující čtyři secrets:

- ANDROID_KEYSTORE_BASE64
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

Vytvoření soukromého keystore na Linuxu:

    keytool -genkeypair -keystore dpf-monitor.jks -alias dpf-monitor -keyalg RSA -keysize 3072 -validity 10000
    base64 -w0 dpf-monitor.jks

Nastav base64 a odpovídající hesla/alias. Keystore bezpečně zálohuj a nikdy jej necommituj. Pokud chybí, workflow vytvoří testovací prerelease s dočasně podepsaným debug APK, nikoli stabilní release APK. Běžné Actions Artifacts se stahují jako ZIP; GitHub Release Assets obsahují přímé APK.

## Diagnostika

Pokud po prvním nastavení nefunguje ověření VIN, příčinou může být neúplná ISO-TP odpověď levného adaptéru. V takovém případě exportuj ZIP a porovnej komunikaci s logy webové PoC aplikace. V případě změny auta neobcházej kontrolu VIN ručním přepsáním identity.
