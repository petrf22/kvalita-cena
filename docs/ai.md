# Lokální AI

Tento dokument popisuje **cílový stav**, ne implementaci — stejně jako `reputace.md` v částech
za etapu 1. Nic z tohohle ještě není napsané: žádná migrace, žádné schéma, žádný worker. Účel
dokumentu je mít jedno místo, kam patří rozhodnutí o roli AI v appce, než se první úloha vůbec
začne psát — ať se neroztroušou po kódu jako vlastní konstanty na víc místech (stejný důvod,
proč jsou vzorce reputace jen v `reputace.md`).

## Proč lokálně

Appka neposílá uživatelský obsah třetím stranám — `soukromi.md` to shrnuje větou „Žádná
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
  `f_conf`/`f_evid`/`f_recency`/`f_group` z `reputace.md` — o vahách rozhoduje chování lidí,
  ne odhad modelu.
- **U fotek verdikt jen řadí frontu k přezkumu.** Rozhoduje pořád člověk, stejně jako
  u `core.record_flag` (`reputace.md`, „Nahlášení záznamu — hlasuje se o faktu, ne o člověku").
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
oddělené schémata `off`/`osm` (`datovy-model.md`, „Oddělení schémat kvůli ODbL"): čistý export
vlastních dat (`pg_dump --schema=core --schema=agg`) nemá obsahovat strojové odhady, které se
můžou přepočítat jindy jinak.

## Čtyři úlohy a jejich pořadí

| Úloha | Kdy | Poznámka |
|---|---|---|
| Předfiltr fotek pro moderaci | **před spuštěním veřejného provozu** | `core.media` existuje už dnes. Míří přímo na to, co `soukromi.md` („Otevřená rizika") označuje za reálný limit projektu — kapacitu moderace jednoho člověka. Práh `app.moderation.photo-flags-to-hide = 1` funguje jen tehdy, když závadnou fotku někdo uvidí — model ji jen posune ve frontě k přezkumu výš, neskryje ji sám (viz „AI nikdy nerozhoduje" výš). |
| OCR ceny z fotky | s dodělaným `f_evid` (zbytek etapy 1 — fotka jako důkaz ceny) | `reputace.md` s tím už počítá (`f_evid = 1,30 účtenka+OCR / 1,15 foto cedulky`), ale schéma dnes neukládá druh důkazu, jen že fotka existuje — viz níže. |
| Kontrola textů recenzí | etapa 2 | Až vznikne `core.product_review`. Dnes nemá co kontrolovat — volný text je jen `Media.caption` (200 zn.) a `RecordFlag.reason` (500 zn.), ani jedno není recenze. |
| Detekce anomálií u cen | etapa 2/3, jako doplněk | **Statistická pravidla zůstávají primární** — `BIASED`/`IMPOSSIBLE`/`TELEPORT`/`BURST`/`CLUSTER`/`COMMERCIAL` (`reputace.md`, „Detekce zneužití") jsou deterministická a laditelná, což je u reputační váhy přednost, ne nedostatek. LLM tu má smysl jen na případy, které pravidla nezachytí, a jeho výstup je vždy poradní stejně jako u ostatních úloh výš — nikdy nový vstup do `w`. |

### Vazba na `f_evid`

Až se bude psát fotka jako důkaz ceny, schéma musí od začátku nést **druh důkazu**
(`RECEIPT_OCR` / `PRICE_TAG_PHOTO` / žádný), ne jen odkaz na fotku — jinak se rozlišení
„účtenka+OCR" (1,30) od „foto cedulky" (1,15) dopisuje pozdější migrací místo jednoho sloupce
navíc hned na začátku.

## Hardware a volba modelu

Zjištěný stroj (srpen 2026): **NVIDIA RTX 4060 Ti, 8 GB VRAM**, AMD Ryzen 9 7900 (24 vláken),
61 GB RAM, Ollama 0.6.1 nainstalovaná a spuštěná lokálně.

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

## Konfigurace (až přijde čas)

Prefix `app.ai.*`, přes `@ConfigurationProperties` stejným vzorem jako `ModerationProperties`/
`TrustProperties` (`backend/src/main/java/cz/kvalitacena/config/`). Prahy jistoty (od
kdy fotku řadit výš ve frontě k přezkumu) patří sem do tohohle dokumentu, ne jako konstanty
rozeseté v kódu — stejné pravidlo jako u prahů reputace.
