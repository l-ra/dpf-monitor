# DPF Monitor 1.0.2 — oprava pádu po spuštění

## Důvod opravné verze

Po instalaci 1.0.1 byl na skutečném telefonu hlášen okamžitý pád aplikace při spuštění. Bez stack trace z telefonu není přesná výjimka dosud prokázána. Rozdíl proti 1.0.0 byl omezen zejména na nové adaptivní ikony a vlastní zpracování okrajů obrazovky Androidu 15. V této verzi je nová ručně řízená obsluha WindowInsets odstraněna.

## Změny

- Systémové okraje a oblast gest nyní zpracovává přímo platformní `fitsSystemWindows` na kořenovém View místo vlastního `setOnApplyWindowInsetsListener`. Nepřepisujeme padding uvnitř insets callbacku během prvního vykreslení.
- Zůstává nová adaptivní ikona a monochromatická notifikační ikona.
- GitHub Actions navíc instaluje debug APK v emulátoru Androidu 15 a kontroluje úspěšné otevření hlavní obrazovky, přepnutí na grafy a opětovné otevření aplikace. Je-li na runneru dostupné KVM, musí tento smoke test projít. Bez KVM se test přeskočí; CI i tak povinně spouští Kotlin/JUnit testy a sestavení, ale nespouští UI na Androidu.
- Beze změny zůstávají VIN a BLE vazba, databáze, 30denní retence, detekce regenerace a podepisovací klíč.

## Ověření v telefonu

Po instalaci `dpf-monitor-v1.0.2-release.apk` přes verzi 1.0.1 zkontrolovat start, přepínání Přehled/Grafy, zobrazení stavové lišty a spodního indikátoru gest. Pokud pád přetrvá, potřebujeme přesný AndroidRuntime stack trace z `adb logcat`; emulátor nedokáže simulovat každou variantu Android ROM výrobce telefonu.

Oprava je preventivní podle rozdílů 1.0.0 a 1.0.1, nikoli potvrzení konkrétní příčiny na fyzickém zařízení.
