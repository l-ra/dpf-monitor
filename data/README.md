# Data z regenerace DPF 2026-11-21

Uživatel poskytl dva záznamy z Web DPF PoC v0.2: původní raw BLE log a CSV s dekódovanými hodnotami. Cílem jejich archivace je reprodukovat pravidla v docs/detection.md a testech. Repozitář je veřejný. Před budoucím přidáváním záznamů je nutné odstranit osobní údaje a zejména VIN (tyto dva záznamy neobsahují dotaz na VIN).

Původní soubory:
- dpf-values-2026-11-21T12-50-36-600Z.csv: SHA-256 da5e5e8e923de76b42e8b1aac81f2a8eb8338cfabe89c430cdb0d1b2d98c34c6; 4090 vzorků.
- dpf-ble-2026-11-21T12-50-29-768Z.txt: SHA-256 7c84ae13e841c732382cc1370777f1ab16cbc7f14165cecc62f7e2dced6522c7; 28999 řádků.

Při rekonstrukci textové podoby CSV z nástroje pro čtení příloh mohou být uvnitř quoted reply polí originální CR znaky normalizovány na LF (semantický obsah CSV polí je zachován, bytewise SHA se pak liší). SHA hodnoty výše patří originálům.

Statistika: 57 min 39 s, 2 151 dotazů 018B, 647 active, 29 passive, 26 error. Aktivní fáze 12:24:01.618–12:41:17.316 UTC. Reset 22208D z 187.3 km na 0.1 km, reset 222089, čítač 22207D roste s krokem 0.64 s, 220809 přepíná z 0000 na 0287 a zpět.
