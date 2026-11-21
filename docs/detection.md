# Detekce regenerace na základě zachycené jízdy

## Přímé signály a časová osa

Záznam 2026-11-21 11:52:57–12:50:36 UTC:
- První 018B s bajty 53 03 FF 00: 12:24:01.618 UTC.
- Poslední aktivní 53 03 FF 00: 12:41:15.730 UTC.
- První pasivní 53 01 00 00: 12:41:17.316 UTC.
- Následně 53 00 00 00. Aktivní fáze cca 17 min 16 s.
- DID 220809 mění 0000 -> 0287 -> 0000.
- DID 22207D během aktivní regenerace roste a po ní se resetuje. Naměřená regrese: 1,56236 jednotky/s, tj. 0,64006 sekundy na jednotku.
- DID 22208D klesne po regeneraci z 187,3 km na 0,1 km.
- DID 222089 po ní klesne z 0BB5 na 0002. Přírůstek cca 0,14194 jednotky/s = 7,045 sekundy na jednotku.
- Vypočtená hmotnost sazí přibližně 24,0 g u spuštění, cca 5,7 g po dokončení.
- Maximální teplota A podle použitého přepočtu přibližně 689 °C.

## Dekodér

018B: čte se první ISO-TP rámec 10 09 41 8B 53 BB CC 00. BB & 1 = regenerace, BB & 2 = aktivní versus pasivní, CC*100/255 = normalizované kritérium. Rámec je NEÚPLNÝ, důležité stavové bajty však obsahuje.

22207D: uint16BE * 0,64 s (délka právě běžící regenerace).
222089: uint16BE * 7 s (doba od poslední regenerace, předběžné).
22208D: uint16BE / 10 km (vzdálenost od poslední regenerace).
22208B: uint16BE / 100 g (vypočtené saze).
22208C: int16BE / 100 g (naměřené saze; záporné výsledky vyžadují další ověření).
22449A/22449B: uint16BE / 10 - 273,15 °C (teplota A/B).
220809: surová hodnota 0287 během aktivní fáze, jinak 0000.

## Stavový automat

IDLE -> APPROACHING: kritérium >=85 %.
IDLE/APPROACHING -> ACTIVE: dva po sobě jdoucí rámce 018B signalizující ACTIVE; jednorázová hlasitá notifikace.
ACTIVE -> COOL_DOWN: dva po sobě jdoucí neaktivní/pasivní rámce. Neznamená dokončení!
COOL_DOWN -> COMPLETED: nezávislý reset vzdálenosti i času od poslední regenerace. Oba předchozí údaje musí být validní.
COOL_DOWN -> INTERRUPTED: po 90 sekundách s dostupnou ECU bez resetu čítačů. Pouze pravděpodobné přerušení.
ACTIVE nebo COOL_DOWN -> UNRESOLVED při odpojení. Při další jízdě upozornit na neověřený výsledek, ověřit stejný VIN a čítače.

## Predikce

Do ukončení: zbývající doba vůči mediánu předchozích dokončených cyklů; počáteční nízkokonfidenční referenční délka 1036 s z jediné jízdy.
Do spuštění: při dosažení >=80 % a kladném trendu nejméně 8 minut (100-current)/rychlost změny, jinak neznámé. Nejde o přesnou predikci ECU.

Potřebná další validace: úspěšná a přerušená regenerace při vypnutí motoru, stabilita času 222089, a přesné chování 018B po opětovném startu.
