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

## Úlohy a jejich pořadí

| Úloha | Kdy | Poznámka |
|---|---|---|
| Předfiltr fotek pro moderaci | **před spuštěním veřejného provozu** | `core.media` existuje už dnes. Míří přímo na to, co `docs/soukromi.md` („Otevřená rizika") označuje za reálný limit projektu — kapacitu moderace jednoho člověka. Práh `app.moderation.photo-flags-to-hide = 1` funguje jen tehdy, když závadnou fotku někdo uvidí — model ji jen posune ve frontě k přezkumu výš, neskryje ji sám (viz „AI nikdy nerozhoduje" výš). |
| Vytěžení účtenky | **hotovo** (oddíl „Účtenky" níž) | Celá účtenka → obchod, datum a čas, řádky. Výsledek je návrh k potvrzení uživatelem; `core.price_observation.evidence_kind = RECEIPT_OCR` nastaví server u ceny potvrzené z řádku. |
| Návrh kategorie a jednotky nového zboží | **hotovo** (oddíl „Účtenky" níž) | Z čitelného názvu řádku účtenky; jen předvyplní formulář, kontrolu dělá `accept` na workeru i serveru, deterministické zdroje (řádek, katalog) mají přednost. |
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
je zapsaný v `docs/rozvoj.md`, „Údaje z etikety" — LABEL je jeho další úloha v pořadí výš.

Od 2026-09 nese fotka i **jazyk** (`core.media.lang`, `docs/lokalizace.md`) a je to přímý
předpoklad té úlohy: etiketa je fotka TEXTU, takže čtení složení musí vědět, v jakém jazyce
text je — jinak nemá jak vybrat správný slovník alergenů ani si ověřit, že vytěžený text
patří k jazykové mutaci, kterou uživatel drží v ruce. Platí tu přesně totéž co u `photo_kind`
výš: rozlišení je v datech od chvíle, kdy funkce vznikla, ne dopisované později.

## Hardware a volba modelu

Zjištěný stroj (srpen 2026): **NVIDIA RTX 4060 Ti, 8 GB VRAM**, AMD Ryzen 9 7900 (24 vláken),
61 GB RAM, Ollama nainstalovaná a spuštěná lokálně (2026-09: verze 0.32, `glm-ocr:bf16` pro
OCR účtenek; 2026-11 přibyl `qwen2.5:7b-instruct`, Apache-2.0, na dočištění čitelných názvů
řádků, a `qwen2.5:14b-instruct`, Apache-2.0, na návrh kategorie a jednotky nového zboží — model se volá jednou dávkou za účtenku až po OCR, takže se s OCR modelem ve VRAM
střídá přes `keep_alive`, viz „Účtenky").

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
(worker nedostal odpověď a hlásí znovu). Chyba nese `leaseUntil` z `lease` jako identifikátor
pronájmu a hlášení ze staršího, mezitím vypršelého pronájmu server ignoruje — jinak by pokus,
který se probral po vypršení leasu, vrátil do fronty úlohu, na které už pracuje další pokus
(lease se proto zaokrouhluje na milisekundy, ať porovnání přežije cestu JSON → DB). Bez
`leaseUntil` (starší worker) platí jen kontrola ukončené úlohy. Syrový text (`rawText`) jde na server jen po řádek
s celkovou částkou — patička s kartou se zahodí i tady (`docs/soukromi.md`, „Účtenky"). Váhové
zboží s cenou za g/ml worker převede na kg/l v množství i v jednotkové ceně.

**Kontrolní součet řídí předvýběr, nic nezakazuje.** Server si ze `extraction` vezme
`totalMatches`/`printedTotal`/`computedTotal` a uloží je jako `core.receipt.sum_status`
a `computed_total` (`ReceiptWorkerService.sumCheck`, nové rozpoznání přepíše jen známým
výsledkem). Klient podle nich předvybere řádky ke zveřejnění: se sedícím součtem vše
kromě nezboží; s nesedícím nebo neověřitelným součtem taky, ale s upozorněním na rozdíl
a zvýrazněnými podezřelými řádky (`ReceiptLine.suspicious`, `ReceiptLineChecks`). Vybrané
řádky zveřejní až klik vlastníka — i nespárované, na „zboží z účtenky" (`docs/reputace.md`).
Předem se nezaškrtávají (web `receipt-status.ts`, mobil `ReceiptLineSelection.kt`):

- **odhad (`FUZZY`)** — jeho potvrzení by naučilo označení pro celý řetězec a připsalo zboží EAN
  z řádku, takže omyl z hromadného zaškrtnutí by se šířil na účtenky ostatních. Kdo odhad
  zamítne („Zrušit párování"), dostane nespárovaný řádek a ten se zaškrtne sám;
- **nezboží (`nonProduct`)** — u nespárovaného řádku zaškrtávátko vůbec není, server ho stejně
  přeskočí;
- **řádek, který server minule přeskočil** (`skipReason`, kromě `RECEIPT_LINE_NOT_MATCHED`,
  který teď zveřejnění bez spárování vyřeší) — jinak by se chybějící cena nebo vyčerpaný strop
  nabízely k odeslání pořád dokola. Ručně zaškrtnout jdou;
- ignorované a potvrzené řádky (zaškrtnout nejdou).

**Proč ne vision model se strukturovaným výstupem:** vyzkoušeno na reálných účtenkách
(větev `feat/import-uctenek`, 2026-09-07) — jazykový model nad textem účtenky zahodil řádek
`3.000 ks x 36.90 Kč /ks` (zůstalo zaplacených 110,70 za tři kusy jako cena kusu) a jindy
přejmenoval zboží, což kontrolní součet nezachytí. Dlouhá účtenka jako jeden obrázek navíc
OCR modelu padala uprostřed (Billa 940×3430 px → 4 řádky textu); stejná účtenka rozdělená na
5 dílů s ~40% překryvem (tak ji posílá mobil) prošla se součtem na haléř. OCR engine je za
rozhraním `Extractor` (`ocr.py`) — jiný model nebo služba jde vyměnit bez zásahu do zbytku,
ale **cloud jen s novou verzí souhlasu** (`app.receipt.consent-version`) a změnou zásad.

**Čitelný název řádku** (`tools/uctenky/pretty.py`, `ai_pretty.py`, od 2026-11): worker ke
každému řádku přidá `labelDisplay` („ROHLÍK43GR" → „Rohlík 43g") a vodítko gramáže. Postup:

1. Ruční přepis celého označení ze slovníku provozovatele (`slovnik/nazvy.tsv`).
2. Jinak deterministická pravidla: jednotky kompaktně česky a zkratky ze slovníku
   (`slovnik/zkratky.tsv`).
3. Teprve zbytek smí dočistit lokální model `qwen2.5:7b-instruct`. Má licenci Apache-2.0;
   pozor, varianty 3B a 72B mají vlastní licenci Qwen.

Model tu dělá **jen kosmetiku pod deterministickou kontrolou** (`ai_pretty.accept`). Smí
doplnit diakritiku, opravit velikost písmen a rozepsat zkratku s tečkou. Kontrola zamítne
jiný počet slov, změněné číslo nebo jednotku, změněné písmeno i odebranou vytištěnou
diakritiku („Ölz" → „Olz"). Zamítnutý výstup se zahodí. Výpadek modelu není chyba účtenky.

Struktura řádku (množství, cena) a syrový tisk (`label_raw`, podle kterého se učí mapování)
se tím nemění. Pravidlo „strukturu netahá model" tedy platí dál. Čitelný název je jen pro
oči, hledání a předvyplnění nového zboží.

**Autentizace workeru:** strojový token `auth.api_token` (scope `RECEIPT_WORKER`, prefix
`kcw_`, v DB jen SHA-256), hlavička `Authorization: Bearer kcw_…`. Vydání tokenu:
`tools/uctenky/README.md`, „Token" (odkazuje i `docs/nasazeni.md`). Token nemá identitu uživatele a na nic mimo
`/api/worker/receipts/**` a obrázky účtenek ve frontě nedosáhne. Kurátorství označení
(`tools/uctenky/curate.py`, `/api/curator/**`) má vlastní token se scope `LABEL_CURATOR`.
Token workeru na něj nedosáhne a naopak, takže stroj s OCR nemůže měnit katalog.

**AI nikdy nerozhoduje ani tady:** výsledek workeru zapíše jen řádky účtenky (návrh).
Server pak deterministicky spáruje, co jde (vnitroobchodní kód nebo EAN, známé označení
z `product_store_label`). Takové řádky jsou v UI předem zaškrtnuté, ale cenu zapíše až
uživatelovo „Potvrdit".

Řádek, který jistě spárovat nejde, může dostat **odhad** (`match_source = FUZZY`). Je to
jednoznačná podobnost názvu podle prahů `auto-match-*` níž a dále platí:
- gramáž z označení musí sedět se zbožím;
- zboží s jiným EAN odhad nedostane;
- zboží ve stavu DRAFT odhad nedostane, nepotvrzený název nemá řídit párování.

Klient odhad ukáže označený „odhad" a **nezaškrtne ho předem**. Potvrzený odhad se učí jako
označení a zboží dostane EAN z řádku, takže hromadné potvrzení omylem by chybu rozšířilo.
Model sám cenu nezakládá a odhad není model, jen trigramová podobnost v PostgreSQL.

**Návrh z Open Food Facts** (`match_source = OFF`) je deterministický jako shoda EAN. EAN
z řádku katalog nezná, ale OFF ho zná a dodá vše, co by jinak musel vyplnit uživatel
(kategorie namapovaná na katalog appky, gramáž; název stačí čitelný z řádku). Klient řádek
ukáže s náhledem z OFF a zaškrtne ho předem. Zboží vznikne nad OFF snapshotem až uživatelovým
„Potvrdit" (`ReceiptReviewService.productFromOff`), ne při zpracování účtenky. Když OFF něco
chybí, řádek zůstane nespárovaný a formulář si OFF dotáhne sám. Odhad podle názvu má před OFF
přednost: zboží bez EAN, které v katalogu už je, by jinak dostalo duplicitu.

**Návrh kategorie a jednotky** (`tools/uctenky/ai_classify.py`, od 2026-11-03). Když řádek
nejde spárovat a uživatel zakládá nové zboží, formulář má mít předvyplněnou kategorii
a jednotku gramáže, aby stačilo zkontrolovat a uložit.

Postup:
1. Worker ke každému řádku pošle model `qwen2.5:14b-instruct` (Apache-2.0, ověřeno
   `ollama show`) jednou dávkou za účtenku. Model dostane čitelné názvy a seznam kategorií
   appky s cestou česky (`GET /api/worker/receipts/categories`).
2. Model vrací slug kategorie a jen ROZMĚR gramáže (`G`/`KG`/`ML`/`L`). Číslo nikdy, to umí
   jen vytištěné označení.
3. `ai_classify.accept` výstup deterministicky zkontroluje a server totéž ověří znovu
   (`ReceiptWorkerService.unitHint`). Zahodí se:
   - slug mimo seznam,
   - jiná jednotka,
   - jednotka u váhového zboží,
   - jednotka v rozporu s vytištěnou gramáží (hmotnost proti objemu).
4. Výsledek se uloží do `ai.receipt_line_hint`, mimo `core`.

Formulář skládá předvyplnění v `NewProductDefaultsService` v tomto pořadí; první zdroj, který
něco dá, vyhrává:
1. řádek sám — váhové zboží, vytištěná gramáž;
2. kategorie, na které se shodnou aspoň 2 z 5 nejpodobnějších aktivních zboží v katalogu
   (remíza = nic);
3. teprve pak model.

Open Food Facts si formulář dotahuje sám a jeho hodnoty předvyplnění z řádku přepíšou. Hodnotu
od modelu formulář označí poznámkou ke kontrole. Model nikdy nezakládá zboží a nespouští
návrh z OFF výš, i když OFF chybí jen kategorie.

Na testovacích účtenkách zařazoval 7B model znatelně hůř (svíčka → pečivo, mikrovlnka →
papírové zboží), proto 14B. Ten se do 8 GB VRAM nevejde celý a běží zčásti na CPU (~30 s za
účtenku), což u dávkové úlohy nevadí. Ani 14B se nestrefí vždy, a proto jde jen o návrh.

**Nové rozpoznání** (od 2026-11-03). Po opravě OCR nebo normalizace ve workeru vrátí
provozovatel už zpracované účtenky do fronty: `worker.py --requeue ID…` nebo
`--requeue-since DATUM` → `POST /api/worker/receipts/requeue` (token `RECEIPT_WORKER`,
nejvýš 500 účtenek najednou, `ReceiptWorkerService.requeue`). Vlastník nic nedělá a o nic
nepřijde:

- **`PROCESSED` účtenka zůstává `PROCESSED`** se starými řádky, dokud nedorazí nová verze —
  dál s ní pracuje a potvrzuje. Selhání, `UNREADABLE` nebo `NOT_A_RECEIPT` v tomto režimu nic
  nemění, jen se zapíše do `ai.receipt_job.last_error`. Účtenka bez řádků (`FAILED`,
  `UNREADABLE`, `NOT_A_RECEIPT`) se zpracuje od nuly jako nová.
- **Řádky, na které uživatel sáhl** (`ReceiptLine.isUserTouched`: potvrzená cena, ručně
  vybrané nebo založené zboží, ignorovaný řádek), stroj nepřepíše. Ostatní nahradí nová verze.
  Nový řádek, který odpovídá ponechanému, se zahodí (`ReceiptLineMerger`: nejdřív stejné
  označení a částka, pak jednoznačně stejné částky — oprava OCR mění právě označení);
  ponechaný z něj převezme jen čitelný název. Nespárovaný ponechaný řádek zůstane na konci.
- **Hlavička:** obchod a datum nákupu potvrzuje uživatel, proto se jen doplní, když chybí;
  syrová hlavička a součet se nahradí jen novou neprázdnou hodnotou. Zápis workeru i úpravy
  vlastníka (`updateHeader`, párování řádků, potvrzení) berou účtenku se zámkem řádku
  (`ReceiptRepository.findForUpdateById`) a entita má `@DynamicUpdate` — jinak by worker
  souběžnou opravu obchodu nebo data přepsal hodnotou z dřív načteného snapshotu.
- **Bez obrázků** (po retenci) dostane worker místo nich uložený `raw_text` a rozebere ho
  znovu bez OCR. Ten končí řádkem „Celkem", takže co profil čte z patičky (datum nákupu,
  adresa prodejny COOP z bloku terminálu), opravit nejde a zůstane původní.
- Nová verze se stejným obsahem řádky nemění. Jinak se účtenka vlastníkovi ukáže jako nová
  (`seen_at = NULL`) a stav revize se přepočítá. Vodítka formuláře nového zboží (gramáž
  z tisku, návrh kategorie a jednotky v `ai.receipt_line_hint`) se ale obnoví i u řádků beze
  změny — včetně jejich zmizení; gramáž cíleným UPDATE, ať posun `updated_at` účtenku
  nerozsvítí (`countUnseen`).

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
| `item-confirmations` | 3 (profil `beta`: 0) | kolik jiných přispěvatelů musí zboží z účtenky potvrdit cenou, než se zveřejní (leave-one-out, `PublicationThresholds`). Vlastní práh místo `app.catalog.draft-confirmations`, ať jde v testovacím provozu zveřejňovat hned (0 = založit rovnou jako `ACTIVE`) bez změny ručního zakládání (`docs/reputace.md`, „Zboží z účtenky") |
| `rematch-max-age-days` | 180 | pozdější návrh spárování jen u účtenek mladších než pseudonymizační okno |
| `auto-match-similarity` | 0,75 | odhad (FUZZY) jen nad touhle podobností názvu (`ProductRepository.SIMILAR_BY_NAME_SCORE`, stejné měření jako našeptávač; čísla se z dotazu vynechávají, gramáž hlídá vodítko zvlášť). Výchozí hodnota, kalibrovat na reálných účtenkách |
| `auto-match-margin` | 0,2 | nejlepší kandidát musí být o tolik před druhým — dvě podobné položky (duplicita, jiná příchuť) jsou hádání, ne odhad |
| `notify-email` | prázdné (prod: `RECEIPT_NOTIFY_EMAIL` v `.env`) | kam `ReceiptQueueNotifier` hlásí čekající účtenky; prázdné = vypnuto. Worker se spouští ručně, takže bez e-mailu by provozovatel o frontě nevěděl |
| `notify-reminder-cron` / `-zone` | `0 0 8 * * *`, `Europe/Prague` | denní připomínka, dokud fronta není prázdná. Okamžitý e-mail chodí jen u první účtenky do prázdné fronty, aby víc nahrávajících lidí neznamenalo víc e-mailů. Text nese jen počet a čas nejstarší účtenky, nic o uživateli |

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
| `LOW_CONTRAST_CANNY_LOW` / `_HIGH` / `CLAHE_CLIP_LIMIT` | 15 / 45 / 2,0 | druhý průchod hledání obrysu, když první (Canny 50/150) nic nenajde: bílá účtenka na světlé podložce má hranu jen pár desítek úrovní jasu, po vyrovnání kontrastu (CLAHE) a s nižšími prahy vznikne; přerušený obrys spojí `MORPH_CLOSE` |
| `MIN_TEXT_DENSITY` | 0,03 | poslední záchrana podle **hustoty textu** po sloupcích (podíl hranových pixelů po vyhlazení): sloupce s tiskem účtenky jsou nad prahem, čistá podložka pod ním — funguje bez ohledu na barvu podložky |
| `TEXT_DENSITY_SMOOTH_RATIO` | 0,04 | šířka klouzavého průměru profilu hustoty (podíl šířky záběru) — přemostí mezery mezi slovy |
| `TEXT_BAND_MAX_OUTSIDE_RATIO` | 0,35 | hustota mimo pruh smí být nejvýš takový podíl hustoty uvnitř — vzorovaná podložka (dřevo, ubrus) by jinak prošla jako „text" |
| `TEXT_BAND_MARGIN_RATIO` | 0,04 | pruh textu se rozšíří o okraj papíru na každé straně (podíl šířky záběru) |
| `QUAD_SMOOTHING` | 0,5 | váha nového rámečku při vyhlazení v čase (zbytek drží předchozí) — rámeček neposkakuje a odpočet se nerestartuje kvůli šumu detekce; skok o víc než `START_MAX_DRIFT_RATIO` vyhlazení přeskočí |
| `QUAD_HOLD_MS` | 500 ms | jak dlouho platí poslední rámeček, když detekce na pár snímků vypadne |
| `NO_RECEIPT_HELP_MS` | 3000 ms | po takové době ostrých snímků bez nalezené účtenky appka poradí (tmavší podložka, svítilna) a nabídne **snímání podle rámečku**: za účtenku se bere pevný rámeček na displeji (boky 14 % od okraje náhledu jako vodítko), posun a dělení na díly fungují beze změny |

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
