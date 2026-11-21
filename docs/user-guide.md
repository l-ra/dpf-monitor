# Uživatelský návod, build a instalace

## GitHub Actions / instalace

V repozitáři povolit Settings → Actions → General → Allow actions. Workflow Android APK se spouští při push do main a ručně. Provede testDebugUnitTest a assembleDebug. V Actions → Android APK → úspěšný běh → Artifacts stáhnout dpf-monitor-debug, rozbalit ZIP, na telefonu nainstalovat APK (povolit instalaci neznámých aplikací), případně použít adb install.

Debug podpis na runneru se může mezi běhy měnit; pro běžné aktualizace doporučujeme stabilně podepsaný release APK. Potřebné čtyři repository secrets v Settings → Secrets and variables → Actions:
- ANDROID_KEYSTORE_BASE64 (base64 privátního keystore)
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

Keystore vytvořit lokálně například pomocí keytool -genkeypair -keystore dpf-monitor.jks -alias dpf-monitor -keyalg RSA -keysize 3072 -validity 10000. Base64 získat přes base64 -w0 dpf-monitor.jks. Keystore uchovávat bezpečně mimo GitHub, nikdy nevkládat do repozitáře. Po nastavení secrets Actions vytvoří artifact dpf-monitor-release-signed. Přechod z debug na release podpis obvykle vyžaduje jednorázovou odinstalaci debug APK, další releasy lze aktualizovat bez ztráty dat.

## První aktivace

1. Android 12+, povolit Bluetooth a oprávnění BLUETOOTH_SCAN, BLUETOOTH_CONNECT; na Androidu 13+ také POST_NOTIFICATIONS.
2. Bezpečně zaparkovat, zapnout zapalování, připojit BLE ELM327, ukončit ostatní diagnostické aplikace.
3. V aplikaci zvolit Přiřadit jediný adaptér OBDII a dokončit systémový výběr.
4. Po připojení musí být z ECU získáno úplné VIN přes 0902 nebo 22F190. Bez úspěchu automatický monitoring nelze aktivovat; levný ELM adaptér může mít problém s vícerámcovou ISO-TP komunikací.
5. V dalších jízdách systémová asociace umožní automatické sledování při objevení stejného BLE adaptéru.

## Android nastavení pro běh na pozadí

Povolit notifikace a kanál Hlasitá regenerace DPF, zkontrolovat hlasitost alarmů a DND. Podle výrobce zapnout režim baterie Bez omezení a nepoužívat Force stop. Pokud OBD port zůstává napájen i při vypnutém motoru, BLE presence sama o sobě neznamená začátek/konec jízdy. Přesnou spolehlivost nelze garantovat bez reálného testování.

## Uživatelské stavy

ACTIVE: regenerace běží, první dvě kladná měření vyvolají hlasitý alarm.
COOL_DOWN: indikace aktivní regenerace zanikla, čeká se na potvrzení resetu čítačů.
COMPLETED: reset potvrzen, úspěšné dokončení.
INTERRUPTED: pravděpodobně přerušeno bez resetu čítačů.
UNRESOLVED: odpojení nebo zastavení během regenerace; při příští jízdě upozornění, není to definitivní důkaz přerušení.

Počítané časy jsou odhady, nikoli pokyn pokračovat v jízdě za nebezpečných podmínek.

## Ladění

Tlačítko Sdílet diagnostický log poskytne lokální technický záznam. U výpadků VIN ověřit ISO-TP Flow Control nebo vyměnit ELM za lépe zdokumentovaný BLE adaptér.
