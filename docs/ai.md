# Lokální AI

Tento dokument popisuje **cílový stav**, ne implementaci — stejně jako `docs/reputace.md` v částech
za MVP. Účel dokumentu je mít jedno místo, kam patří rozhodnutí o roli AI v appce — ať se
neroztroušou po kódu jako vlastní konstanty na víc místech (stejný důvod, proč jsou vzorce
reputace jen v `docs/reputace.md`).

**Od 2026-09-07 už jedna část napsaná je**: vytěžení účtenky (schéma `ai`, `ai.receipt`/
`ai.receipt_line`, import a potvrzování cen — `docs/stav-implementace.md`, „Import účtenek").
Běží ale v jiné topologii, než popisuje „Kde to běží" níž — OCR zatím dělá provozovatel
lokálním nástrojem `tools/uctenky`, pull worker teprve přijde. Odchylka je popsaná tamtéž.
Zbytek dokumentu (předfiltr fotek, kontrola recenzí, detekce anomálií) je pořád jen rozhodnutí,
žádný kód.

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
můžou přepočítat jindy jinak. Schéma `ai` vzniklo 2026-09-07 s první úlohou (účtenky).

### Dočasná odchylka: OCR účtenek běží mimo server

První napsaná úloha topologii výš zatím nepoužívá. OCR i normalizaci textu dělá **lokální
nástroj `tools/uctenky` spouštěný z příkazové řádky u provozovatele**; na server jde přes
`POST /api/receipts` až hotový normalizovaný dokument. Fronta ve schématu `ai` v tomhle směru
neexistuje — `ai.receipt` je výsledek, ne úloha.

Proč tak: dokud účtenky nahrává jeden člověk a ladí se na nich parser, je pull worker práce
navíc bez užitku, a hlavně to **posouvá nejcitlivější krok mimo server** — snímek účtenky se
na server vůbec nedostane (`docs/soukromi.md`, „Účtenka"). Až funkci dostanou uživatelé,
přesune se OCR na worker podle topologie výš; `ai.receipt` a formát `receipt-v1` se tím
nemění, přibude před ně fronta snímků.

Důsledek pro `f_evid`: artefaktem, o který se dnes opírá `evidence_kind = RECEIPT_OCR`, je
uložená účtenka, jejíž součet položek sedí na vytištěnou částku — ne snímek, který server
sám přečetl. Dokud je import za přihlášením a používá ho provozovatel, je to přijatelné
(`f_evid` navíc zatím do vah vůbec nevstupuje); s otevřením uživatelům musí artefakt ověřovat
server, jinak by šlo důkaz sebedeklarovat (`docs/rozvoj.md`, „Zdroj ceny").

### Rozpoznávání ano, „vylepšování" textu ne

Ověřeno na reálných účtenkách: druhý průchod textovým modelem (`qwen2.5:14b-instruct`) nad
výstupem OCR data **poškozuje**. Na účtence Penny z 6. 9. 2026 zahodil celý řádek s množstvím
— ze `3.000 ks x 36.90 Kč /ks` + `Balsýr bloček 47% 200g C 110.70` zbylo
`Balsýr bloček 47% 200g 110.70 Kč`. Zůstala částka zaplacená za tři kusy, cena z regálu
(36,90/ks) zmizela; importér krmený takovým textem uloží trojnásobek. Týž model při jiném běhu
přejmenoval zboží (`GRAN MORAV.STR.100G` → „GRAN MAROKSKÝ STR."), což je horší kategorie chyby:
tichou halucinaci názvu kontrolní součet nezachytí, protože částky sedí.

Model tedy dělá **výhradně obrázek → text**; převod textu na strukturu je deterministický
parser (regexy), ne další model. Platí to i pro budoucí worker.

Kontrolu kvality nedělá model, ale **aritmetika**: Σ(položky + slevy) musí sednout na
vytištěné „Celkem". Na reálných účtenkách to vychází na haléř, takže rozsypané OCR se pozná
spolehlivě a účtenka, která nesedí, se do cen vůbec nedostane.

## Čtyři úlohy a jejich pořadí

| Úloha | Kdy | Poznámka |
|---|---|---|
| Předfiltr fotek pro moderaci | **před spuštěním veřejného provozu** | `core.media` existuje už dnes. Míří přímo na to, co `docs/soukromi.md` („Otevřená rizika") označuje za reálný limit projektu — kapacitu moderace jednoho člověka. Práh `app.moderation.photo-flags-to-hide = 1` funguje jen tehdy, když závadnou fotku někdo uvidí — model ji jen posune ve frontě k přezkumu výš, neskryje ji sám (viz „AI nikdy nerozhoduje" výš). |
| OCR ceny z fotky / vytěžení účtenky | **částečně hotovo (2026-09-07)** — účtenka ano, fotka cedulky ne | Vytěžení celé účtenky se ukázalo jako přirozenější první krok než jedna cena z fotky: dá desítky cen naráz a čte se z tištěného textu, ne z regálu. Hotové je schéma `ai`, import `receipt-v1` a potvrzování řádků člověkem (`docs/stav-implementace.md`, „Import účtenek"), včetně sloupce `core.price_observation.evidence_kind` — viz níže. Chybí fotka cedulky jako důkaz a přesun OCR na worker („Dočasná odchylka" výš). |
| Kontrola textů recenzí | další rozvoj | `core.product_review.text` (max 1000 znaků) už existuje a jde nahlásit (`RecordType.REVIEW`, `docs/reputace.md`) — chybí jen tenhle strojový předfiltr, nahlašování zatím řeší jen lidský hlas. |
| Detekce anomálií u cen | další rozvoj, jako doplněk | **Statistická pravidla zůstávají primární** — `BIASED`/`IMPOSSIBLE`/`TELEPORT`/`BURST`/`CLUSTER`/`COMMERCIAL` (`docs/reputace.md`, „Detekce zneužití") jsou deterministická a laditelná, což je u reputační váhy přednost, ne nedostatek. LLM tu má smysl jen na případy, které pravidla nezachytí, a jeho výstup je vždy poradní stejně jako u ostatních úloh výš — nikdy nový vstup do `w`. |

### Vazba na `f_evid`

Druh důkazu musí schéma nést od začátku (`RECEIPT_OCR` / `PRICE_TAG_PHOTO` / žádný), ne jen
odkaz na fotku — jinak se rozlišení „účtenka+OCR" (1,30) od „foto cedulky" (1,15) dopisuje
pozdější migrací místo jednoho sloupce navíc hned na začátku.

**Hotovo od 2026-09-07**: `core.price_observation.evidence_kind`
(`NONE`/`PRICE_TAG_PHOTO`/`RECEIPT_OCR`, `2026-09-07/03-observation-evidence-kind.yaml`).
Je to druhá OSA vedle `source` (kanál klienta), ne další hodnoty do jedné — „z mobilu" a
„z účtenky" nejsou alternativy (`docs/rozvoj.md`, „Zdroj ceny"). Hodnotu nastavuje výhradně
server podle cesty zápisu, klient ji v žádném inputu neposílá: důkaz je násobič váhy, takže
sebedeklarace by nebyla zobrazovací údaj, ale reputační útok. Samotný `f_evid` do
`PriceAggregationService.weightFor()` zatím nevstupuje — sloupec se plní dřív, než se začne
číst, přesně kvůli té migraci navíc.

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

**Ověřeno 2026-09-07 pro vytěžení účtenky:** používá se `glm-ocr:bf16`
([zai-org/GLM-OCR](https://huggingface.co/zai-org/GLM-OCR), 0,9 mld. parametrů), licence
**MIT** — pravidlo splňuje. Vejde se do 2 GB VRAM, jednu účtenku přečte za ~8 s na RTX 4060 Ti,
takže úvaha o střídání modelů výš se u téhle úlohy zatím vůbec neuplatní. Model je přepínač
nástroje (`--model`), ne natvrdo zadrátovaná hodnota.

## Konfigurace

Prefix `app.ai.*`, přes `@ConfigurationProperties` stejným vzorem jako `ModerationProperties`/
`TrustProperties` (`backend/src/main/java/cz/kvalitacena/config/`). Prahy jistoty (od
kdy fotku řadit výš ve frontě k přezkumu) patří sem do tohohle dokumentu, ne jako konstanty
rozeseté v kódu — stejné pravidlo jako u prahů reputace. Zatím žádná taková property
neexistuje: dnešní úloha (účtenky) nemá práh jistoty, model buď text přečte, nebo ne, a rozhoduje
o tom kontrolní součet.

Import účtenek má vlastní prefix **`app.receipt.*`** (`ReceiptProperties`) — limity a retence,
ne parametry modelu. Prahy párování zůstávají v `app.catalog.*`, kde už jsou ostatní
(`label-confirmations`, `suggestion-similarity`).
