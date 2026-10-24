# Lokální AI

Tento dokument popisuje hlavně **cílový stav** — stejně jako `docs/reputace.md` v částech za MVP.
Jako první je napsaná úloha **vytěžení účtenky** (schéma `ai`, fronta, worker
`tools/uctenky/`, oddíl „Účtenky" níž); zbytek zatím ne. Účel
dokumentu je mít jedno místo, kam patří rozhodnutí o roli AI v appce, než se první úloha vůbec
začne psát — ať se neroztroušou po kódu jako vlastní konstanty na víc místech (stejný důvod,
proč jsou vzorce reputace jen v `docs/reputace.md`).

## Proč lokálně

Appka neposílá uživatelský obsah třetím stranám — `docs/soukromi.md` to shrnuje větou „Žádná
analytika třetích stran, žádné externí fonty ani CDN". Hostované AI API (OpenAI, Anthropic
apod.) by tenhle slib porušilo tím nejcitlivějším způsobem, jaký appka má: fotkami provozoven
a zboží a texty uživatelů. Lokální model běžící u provozovatele proto není úspora navíc, kterou
lze později vyměnit za pohodlnější cloud — je to jediná varianta slučitelná se zbytkem tohohle
dokumentu. Vedlejším efektem je nulová provozní cena, což je u komunitního projektu bez rozpočtu
podstatné, ale není to hlavní důvod.

## AI nikdy nerozhoduje

Nejdůležitější rozhodnutí v tomhle dokumentu, zapsané dřív, než ho okolnosti (tlak na rychlejší
moderaci, dobré výsledky modelu v testu) přimějí ohnout:

- **Verdikt je poradní údaj vedle záznamu, ne stav záznamu.** Model nesahá na `hidden_at`,
  nemaže nic, nezakládá `core.price_observation`.
- **Verdikt není složka reputační váhy.** Nevstupuje do `S` ani do žádného z faktorů
  `f_conf`/`f_evid`/`f_recency`/`f_group` z `docs/reputace.md` — o vahách rozhoduje chování lidí,
  ne odhad modelu.
- **U fotek verdikt jen řadí frontu k přezkumu.** Rozhoduje pořád člověk, stejně jako
  u `core.record_flag` (`docs/reputace.md`, „Nahlášení záznamu — hlasuje se o faktu, ne o člověku").
- **Když worker neběží, appka funguje beze změny.** Degradace, ne výpadek — a zároveň test
  správnosti návrhu: pokud by vypnutý domácí PC něco rozbil, je AI zapojená špatně.

## Kde to běží

Topologie: appka běží na veřejném serveru, model na domácím PC jako **pull worker**. Worker si
úlohy vyzvedává přes HTTPS a výsledky posílá zpět; doma se kvůli tomu neotevírá žádný port ani
není potřeba veřejná IP. Vypnutý PC znamená jen delší frontu, ne chybu.

Fronta se drží vzoru, který v appce už funguje pro `agg.price_current`/`agg.price_daily` —
`agg.recompute_queue` + `PriceAggregationService.processQueue()` (`@Scheduled(fixedDelay =
5000)`, dávka přes `findTop200ByProcessedAtIsNullOrderByEnqueuedAtAsc()`, `processed_at` jako
značka hotova). Rozdíl je jen v tom, kdo úlohy z fronty bere — tam scheduler uvnitř backendu,
tady vzdálený worker přes HTTPS pull.

Verdikty patří do vlastního schématu **`ai`**, mimo `core` — ze stejného důvodu, proč jsou
oddělené schémata `off`/`osm` (`docs/datovy-model.md`, „Oddělení schémat kvůli ODbL"): čistý export
vlastních dat (`pg_dump --schema=core --schema=agg`) nemá obsahovat strojové odhady, které se
můžou přepočítat jindy jinak.

## Čtyři úlohy a jejich pořadí

| Úloha | Kdy | Poznámka |
|---|---|---|
| Předfiltr fotek pro moderaci | **před spuštěním veřejného provozu** | `core.media` existuje už dnes. Míří přímo na to, co `docs/soukromi.md` („Otevřená rizika") označuje za reálný limit projektu — kapacitu moderace jednoho člověka. Práh `app.moderation.photo-flags-to-hide = 1` funguje jen tehdy, když závadnou fotku někdo uvidí — model ji jen posune ve frontě k přezkumu výš, neskryje ji sám (viz „AI nikdy nerozhoduje" výš). |
| Vytěžení účtenky | **hotovo** (oddíl „Účtenky" níž) | Celá účtenka → obchod, datum a čas, řádky. Výsledek je návrh k potvrzení uživatelem; `core.price_observation.evidence_kind = RECEIPT_OCR` nastaví server u ceny potvrzené z řádku. |
| OCR ceny z fotky cedulky | s dodělaným `f_evid` (zbytek MVP — fotka jako důkaz ceny) | `docs/reputace.md` s tím už počítá (`f_evid = 1,30 účtenka+OCR / 1,15 foto cedulky`); sloupec `evidence_kind` už existuje (vznikl s účtenkami), chybí jen `PRICE_TAG_PHOTO` cesta. |
| Kontrola textů recenzí | další rozvoj | `core.product_review.text` (max 1000 znaků) už existuje a jde nahlásit (`RecordType.REVIEW`, `docs/reputace.md`) — chybí jen tenhle strojový předfiltr, nahlašování zatím řeší jen lidský hlas. |
| Detekce anomálií u cen | další rozvoj, jako doplněk | **Statistická pravidla zůstávají primární** — `BIASED`/`IMPOSSIBLE`/`TELEPORT`/`BURST`/`CLUSTER`/`COMMERCIAL` (`docs/reputace.md`, „Detekce zneužití") jsou deterministická a laditelná, což je u reputační váhy přednost, ne nedostatek. LLM tu má smysl jen na případy, které pravidla nezachytí, a jeho výstup je vždy poradní stejně jako u ostatních úloh výš — nikdy nový vstup do `w`. |

### Vazba na `f_evid`

Až se bude psát fotka jako důkaz ceny, schéma musí od začátku nést **druh důkazu**
(`RECEIPT_OCR` / `PRICE_TAG_PHOTO` / žádný), ne jen odkaz na fotku — jinak se rozlišení
„účtenka+OCR" (1,30) od „foto cedulky" (1,15) dopisuje pozdější migrací místo jednoho sloupce
navíc hned na začátku.

Jiná osa, stejná zásada: `core.media.photo_kind` (`ITEM`/`LABEL`/`OTHER`, docs/datovy-model.md)
už dnes rozlišuje fotku zboží od fotky etikety u formuláře nového zboží — `LABEL` je zamýšlený
budoucí vstup pro čtení textu/složení z etikety, obdoba dnešní „OCR ceny z fotky" výš, jen nad
jiným polem fotky. Nemá nic společného s `f_evid` (ten je na `core.price_observation`, ne na
`core.media`), ale stejná lekce platí obráceně: druh fotky je v datech od chvíle, kdy funkce
vznikla, ne dopisovaný později. Cílový stav čtení etikety (nutriční tabulka, složení, alergeny)
je zapsaný v `docs/rozvoj.md`, „Údaje z etikety" — LABEL je jeho pátá úloha v pořadí výš.

Od 2026-09 nese fotka i **jazyk** (`core.media.lang`, `docs/lokalizace.md`) a je to přímý
předpoklad té úlohy: etiketa je fotka TEXTU, takže čtení složení musí vědět, v jakém jazyce
text je — jinak nemá jak vybrat správný slovník alergenů ani si ověřit, že vytěžený text
patří k jazykové mutaci, kterou uživatel drží v ruce. Platí tu přesně totéž co u `photo_kind`
výš: rozlišení je v datech od chvíle, kdy funkce vznikla, ne dopisované později.

## Hardware a volba modelu

Zjištěný stroj (srpen 2026): **NVIDIA RTX 4060 Ti, 8 GB VRAM**, AMD Ryzen 9 7900 (24 vláken),
61 GB RAM, Ollama nainstalovaná a spuštěná lokálně (2026-09: verze 0.32, `glm-ocr:bf16` pro
OCR účtenek).

Dvě omezení určují volbu:

- **8 GB VRAM neuveze vision a textový model najednou.** Buď se budou střídat (Ollama
  `keep_alive`, za cenu latence při přepnutí), nebo jeden multimodální model obslouží obojí.
  Pro dávkové zpracování z fronty je střídání přijatelné — na rozdíl od interaktivního použití
  tu na latenci nikdo nečeká.
- **Licence modelu se řídí stejným pravidlem jako licence knihoven** (`CLAUDE.md`, „Pouze
  svobodné licence"; `README.md`, „Licence knihoven" — „výhradně svobodné licence, MIT,
  Apache-2.0, BSD, EPL"). Modely aktuálně nainstalované lokálně (`gemma3`, `llama3.2`) tohle
  pravidlo NESPLŇUJÍ — Gemma jede na Gemma Terms of Use s omezeními užití, Llama na Meta
  Community License. Nejsou tedy použitelný výchozí bod, i když jsou po ruce.
  Kandidáti pod Apache-2.0 jsou modely řady Qwen (vision i text) a Mistral 7B.
  **Konkrétní model i jeho aktuální licenci ověřit až ve chvíli implementace** — nabídka se
  mění rychleji než tenhle dokument a licence se liší i mezi velikostmi téže řady.

## Účtenky

Topologie přesně podle „Kde to běží" výš. Datový model `docs/datovy-model.md`, „Účtenky";
soukromí `docs/soukromi.md`, „Účtenky".

**Worker** (`tools/uctenky/`, Python jen se standardní knihovnou, README tamtéž) běží na
domácím PC cyklicky (smyčka, nebo `--once` z cronu/systemd timeru). Vyzvedne si dávku
z `POST /api/worker/receipts/lease`, stáhne části účtenky a každou přečte OCR modelem
(`glm-ocr:bf16`, licence MIT) — **model dělá výhradně obrázek → text**. Části spojí bez řádků
zopakovaných v překryvu, text rozebere **deterministicky regexy** (profil řetězce, jinak obecný
profil), zkontroluje součet proti vytištěné částce „Celkem" a výsledek vrátí přes
`POST /{id}/result`. Obrázek bez částek dostane `NOT_A_RECEIPT`, částky bez položek
`UNREADABLE`, chyba workeru `POST /{id}/fail` (po `max-attempts` → `FAILED`). Úloha, na které
worker spadl nebo zamrzl bez `/fail`, se po vypršení leasu vrátí do fronty a po `max-attempts`
skončí jako `FAILED` při dalším vyzvednutí. Výsledek i chyba k už ukončené úloze se ignorují
(worker nedostal odpověď a hlásí znovu). Syrový text (`rawText`) jde na server jen po řádek
s celkovou částkou — patička s kartou se zahodí i tady (`docs/soukromi.md`, „Účtenky"). Váhové
zboží s cenou za g/ml worker převede na kg/l v množství i v jednotkové ceně.

**Proč ne vision model se strukturovaným výstupem:** vyzkoušeno na reálných účtenkách
(větev `feat/import-uctenek`, 2026-09-07) — jazykový model nad textem účtenky zahodil řádek
`3.000 ks x 36.90 Kč /ks` (zůstalo zaplacených 110,70 za tři kusy jako cena kusu) a jindy
přejmenoval zboží, což kontrolní součet nezachytí. Dlouhá účtenka jako jeden obrázek navíc
OCR modelu padala uprostřed (Billa 940×3430 px → 4 řádky textu); stejná účtenka rozdělená na
5 dílů s ~40% překryvem (tak ji posílá mobil) prošla se součtem na haléř. OCR engine je za
rozhraním `Extractor` (`ocr.py`) — jiný model nebo služba jde vyměnit bez zásahu do zbytku,
ale **cloud jen s novou verzí souhlasu** (`app.receipt.consent-version`) a změnou zásad.

**Autentizace workeru:** strojový token `auth.api_token` (scope `RECEIPT_WORKER`, prefix
`kcw_`, v DB jen SHA-256), hlavička `Authorization: Bearer kcw_…`. Vydání tokenu:
`tools/uctenky/README.md`, „Token" (odkazuje i `docs/nasazeni.md`). Token nemá identitu uživatele a na nic mimo
`/api/worker/receipts/**` a obrázky účtenek ve frontě nedosáhne.

**AI nikdy nerozhoduje ani tady:** výsledek workeru zapíše jen řádky účtenky (návrh).
Server pak deterministicky spáruje, co jde (vnitroobchodní kód, známé označení z
`product_store_label`) — takové řádky jsou v UI předem zaškrtnuté, ale cenu zapíše až
uživatelovo „Potvrdit". Model sám cenu nezakládá.

**Archiv obrázků** pro zlepšování rozpoznávání: `GET /api/worker/receipts/archive-candidates`
vrací jen účtenky s `archive_consent`, worker obrázky stáhne do `ARCHIVE_DIR` a potvrdí
`POST /{id}/archived` — teprve pak je denní retenční job smaže ze serveru.

**Prahy a limity** (`app.receipt.*`, `ReceiptProperties`):

| Klíč | Hodnota | Proč |
|---|---|---|
| `consent-version` | 1 | zvednout při každé změně zpracovatele nebo účelu |
| `max-images-per-receipt` | 30 | dlouhá účtenka z OpenCV pipeline = desítky výřezů; celý upload ale strop `spring.servlet.multipart.max-request-size` 40 MB (nad ním 413 `RECEIPT_UPLOAD_TOO_LARGE`) |
| `max-receipts-per-day` | 10 | rate limit na uživatele |
| `max-image-bytes` | 4 MB | výřez je šedotónový JPEG, typicky desítky kB |
| `max-width` | 1600 px | šířka stačí na čitelnost; výška se neomezuje (jen `media.max-pixels`) |
| `image-retention-days` | 30 | od zpracování, pak obrázky pryč |
| `archive-grace-days` | 14 | kolik navíc počkat na stažení do archivu u `archive_consent` |
| `lease-minutes` | 15 | po vypršení leasu se úloha vrátí do fronty |
| `max-attempts` | 3 | pak `FAILED` — počítá se každé vyzvednutí, i to, po kterém worker spadl bez `/fail` |
| `label-confirmations` | 1 | aktivace označení; 1, dokud je přispěvatelů málo, pak zvednout na 2 (vzor alias) |
| `rematch-max-age-days` | 180 | pozdější návrh spárování jen u účtenek mladších než pseudonymizační okno |

**Snímání na klientovi (mobil):** prahy capture pipeline (`ui/receipt/capture/CaptureThresholds.kt`,
`docs/rozvoj.md`, „Sběr snímků"). V kódu jsou jen jako konstanty s odkazem sem.

| Konstanta | Hodnota | Proč |
|---|---|---|
| `ANALYSIS_WIDTH` | 480 px | analýza ostrosti/hran na zmenšeném šedotónu — rychlost |
| `MIN_SHARPNESS` | 60 | Laplacianova variance, pod ní se snímek zahodí jako rozmazaný |
| `NEW_PART_SHIFT_RATIO` | 0,60 | nový díl při posunu o 60 % výšky (~40 % překryv pro spojení ve workeru) |
| `OUTPUT_MAX_WIDTH` | 1600 px | stejně jako `app.receipt.max-width` |
| `MAX_PARTS` | 30 | stejně jako `app.receipt.max-images-per-receipt` |
| `JPEG_QUALITY` | 85 | šedotónový termotisk zůstane čitelný, díl má desítky kB |
| `MIN_QUAD_AREA_RATIO` | 0,10 | menší čtyřúhelník není účtenka, ale šum |
| `MIN_BAND_WIDTH_RATIO` / `MIN_BAND_CONTRAST` | 0,25 / 25 | záchrana podle bočních hran u dlouhé účtenky, kde se čtyřúhelník nevejde |
| `IMPORT_TILE_ASPECT` | 1,0 | dlouhý obrázek z galerie se řeže na díly s poměrem stran ~1:1 |
| `MIN_SHIFT_RESPONSE` / `MAX_SHIFT_RATIO` | 0,05 / 1,0 | `phaseCorrelate` — slabší odezva = posun se nepočítá |
| `CUT_SNAP_WINDOW_RATIO` / `INK_THRESHOLD` | ±5 % / 128 | řez dílu se posune do mezery mezi řádky textu, ať se řádek nepřeřízne |
| `DEFAULT_START_DELAY_SECONDS` / `START_DELAY_CHOICES` | 2 s / 1–5 s | první díl se uloží až po odpočtu, během kterého je účtenka v klidu vidět; délku si uživatel mění v Nastavení |
| `START_MAX_DRIFT_RATIO` | 0,06 | roh rámečku ujel od začátku odpočtu o víc než 6 % šířky záběru = uživatel ještě míří, odpočet začne znovu |
| `START_LOST_GRACE_MS` | 300 ms | tak dlouho smí účtenka z detekce vypadnout, než se odpočet zruší — jednotlivý snímek bez rámečku ho nesmí shodit |

Proč odpočet: dřív se první díl uložil hned na prvním ostrém snímku s nalezeným rámečkem, tedy
typicky ještě během míření — nakloněný, nebo s rámečkem chyceným za hranu stolu. Odpočet běží
jen na první díl (a na „nafotit znovu" jednoho dílu); další díly řídí posun (`NEW_PART_SHIFT_RATIO`).

Web díly nesnímá kamerou — uživatel vybírá fotky sám (`frontend/.../features/receipts/`);
výchozí maska je spodní pás 20 % výšky poslední části a odesílaná šířka nejvýš 1600 px.

## Konfigurace (až přijde čas)

Prefix `app.ai.*`, přes `@ConfigurationProperties` stejným vzorem jako `ModerationProperties`/
`TrustProperties` (`backend/src/main/java/cz/kvalitacena/config/`). Prahy jistoty (od
kdy fotku řadit výš ve frontě k přezkumu) patří sem do tohohle dokumentu, ne jako konstanty
rozeseté v kódu — stejné pravidlo jako u prahů reputace.
