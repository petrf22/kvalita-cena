# Rozvojové požadavky

Tenhle dokument popisuje **cílový stav, ne implementaci** — stejně jako `docs/ai.md` v částech
za etapu 1. Nic z tohohle ještě není napsané: žádná migrace, žádné API pole, žádný kód. Účel je
mít jedno místo pro nápady, které nemají zapadnout, než na ně dojde řada — ať se neroztroušou po
kódu nebo po hlavě jako poznámky bez kontextu. Nahrazuje dřívější odkaz na samostatný plánovací
soubor mimo repo, který přestal existovat (viz `CLAUDE.md`, „Přehled projektu").

Fáze: **fáze 1** je zprovoznění a zveřejnění (probíhá teď), **fáze 2** rozvoj hned po zveřejnění,
**fáze 3** pozdější rozvoj. Zařazení níž je odhad k datu zápisu, ne závazný harmonogram.

## Název věrnostního programu u typu ceny (fáze 2)

**Zadání:** `PriceKind.CLUB_CARD` má dnes jeden obecný překlad („Klubová cena"). Každý řetězec
ale svůj věrnostní program pojmenovává jinak — Billa „Billa klub", Lidl „S aplikací", podobně
Kaufland a Albert. Cíl: při zápisu ceny u konkrétního obchodu se ve výběru druhu ceny místo
obecného textu nabídne název programu daného obchodu, aby bylo hned jasné, o jakou slevu jde.

Číselník `PriceKind` (`backend/src/main/java/cz/kvalitacena/db/entity/PriceKind.java`) se
**nemění** — žádná nová hodnota, žádná změna překladů (`frontend/public/i18n/*.json` klíče
`enum.priceKind.*`, `frontend/src/app/shared/enum-labels.ts` — `PRICE_KIND_KEYS`,
`SELECTABLE_PRICE_KINDS`, `mobile/.../ui/common/PriceKindLabels.kt`). Nový název je **override**
zobrazený vedle/místo dnešního popisku, ne nová položka číselníku — když chybí, zůstává dnešní
přeložený text jako fallback.

Kam název patří: **`core.retail_chain`** (`RetailChain.java` — dnes `name`, `slug`, `chainType`,
`country`, `website`), protože „Billa klub" platí pro celý řetězec, ne pro jednu provozovnu.
Nezávislé obchody bez řetězce (`Store.chain` nullable) by potřebovaly vlastní override na
`core.store`. Důležité: název programu **není překladový klíč** — je to konkrétní obchodní
značka, nepřekládá se, jen se vloží do UI jako data (`docs/lokalizace.md`, pravidlo že `{0}`/
`{{param}}` nese jen datovou hodnotu, nikdy přeložený kus věty).

API a UI: nové pole na GraphQL typu `Store`/`RetailChain`
(`backend/src/main/resources/graphql/schema.graphqls`, `type Store` — dnes už vrací
`chain: RetailChain`), které formulář zápisu ceny přečte a použije místo generického popisku
(web `features/price-entry`, `features/product-detail`; mobil `ui/price/PriceEntryScreen.kt`).

Kdo název vyplní: jde o úpravu existujícího obchodu/řetězce, takže přirozeně sedí do uživatelské
vrstvy (`core.store_user_edit`, `CatalogEditService.updateStore`, `docs/datovy-model.md` —
„Uživatelská vrstva nad globálními daty"). **Otevřená otázka:** u řetězce dnes žádná uživatelská
editace neexistuje a klienti ani nenabízejí číselník řetězců k výběru (`CLAUDE.md`,
„Neimplementováno") — než se tohle udělá, je potřeba nejdřív editaci `core.retail_chain` vůbec
zavést, nebo název dát jen na `core.store` a smířit se s tím, že se zadává opakovaně za každou
provozovnu jednoho řetězce.

Agregace se nemění — jde čistě o zobrazovací text, klíč agregátu zůstává `price_kind`.

## Ceny předem z akčního letáku a časově omezená nabídka (fáze 3)

**Zadání:** umožnit zadat akční ceny z letáku dopředu — leták obvykle platí týden (např. úterý
až pondělí) a obsahuje ceny, které ještě nezačaly platit. Uživatelé by je viděli předem, s
viditelnou platností „od–do". Příbuzný případ: majitel obchodu zadá platnost i u **běžné** ceny,
protože zboží je k mání jen v určitém čase (např. „příští středa 16:00, brambory a mrkev na
náměstí").

`core.price_observation` má `promo_valid_from`/`promo_valid_to` (`PriceObservation.java`), obě
zapojené v GraphQL schématu i v obou klientech — ale jen pro akci, která **už běží**:
`promo_valid_from` nesmí být v budoucnu, appka tedy zapisuje platnost ceny, kterou uživatel
VIDĚL v regále, ne cenu, která se teprve chystá (viz `docs/datovy-model.md`,
„`core.price_observation` — jádro celé aplikace"). Vypršelou akci pak `ProductGraphQlController`
při čtení vyřadí z aktuálních cen (`agg.price_current.promo_valid_to`), historie v grafu zůstává.

Zásadní rozdíl proti tomuhle modelu: `observed_at` znamená „kdy to uživatel VIDĚL", vždy
v minulosti nebo přítomnosti. Cena z letáku je naopak **oznámení budoucí platnosti** — potřeba
rozhodnout, jestli jde o `price_observation` s platností posunutou do budoucna, nebo o
samostatný typ záznamu (např. `core.price_announcement`). Doporučení k rozvaze: **oddělený
záznam**, protože do agregace (`agg.price_current`/`agg.price_daily`, vážený medián)
budoucí/neplatná cena vstoupit nesmí — nejčistší je, aby vůbec nebyla v tabulce, kterou
agregace čte, a překlopila se do `price_observation` (a tím do agregátu) až v den, kdy
platnost skutečně začne. Existující `promo_valid_from`/`promo_valid_to` na observaci se
tímhle nepoužijí znovu — mají už jasně vymezenou roli (platnost právě běžící akce) a
zavádět do nich druhý, časově opačný význam by matlo obě čtení.

Důvěryhodnost: leták je fakt vyhlášený obchodem, ne pozorování v regálu — jiný charakter důkazu
než `f_evid` z `docs/reputace.md`. Otevřená otázka: jak se taková cena váží a jak se pozná (a co
se stane), když obchod cenu z letáku v den akce nedodrží.

Časově omezená nabídka od majitele předpokládá **ověřeného provozovatele** — dnes jediná vazba
na provozovatele je nepovinné IČO (`Store.ico` + `ico_verified_at`, ověřované proti ARES), ne
skutečné přiřazení účtu k provozovně. Zapsat jako předpoklad k dořešení, ne jako hotovou věc.

Souvislost s lokálními dodavateli (`core.supplier`/`core.supplier_offer`,
`docs/datovy-model.md`, „Co ještě není v etapě 1") — „brambory na náměstí ve středu" je přesně
tenhle případ. Až se bude navrhovat jedno nebo druhé, řešit oba nápady společně, ať nevznikají
dvě podobné datové struktury vedle sebe.

## Načtení celé účtenky (rozvoj po zprovoznění, ne fáze 2 ani 3)

**Zadání:** naskenovat celou účtenku z obchodu a vytěžit z ní názvy zboží, ceny, slevy,
vnitroobchodní kódy, počty kusů, případně obchod a datum. Dvojí přínos: hromadné zadání/
aktualizace více cen najednou a uložení účtenky jako přehledu vlastního nákupu pro uživatele.
Zpracování přes lokálně nainstalovanou AI (Ollama na domácím PC, alespoň zpočátku) jako noční
fronta úloh — náročnost a nároky na grafickou kartu se ověří až v testovacím režimu.

Navazuje přímo na `docs/ai.md` (tabulka „Čtyři úlohy a jejich pořadí", řádek „OCR ceny z fotky",
a oddíl „Vazba na `f_evid`" — schéma má od začátku nést druh důkazu `RECEIPT_OCR` /
`PRICE_TAG_PHOTO`, ne jen odkaz na fotku). Celá účtenka je přirozené rozšíření týž úlohy z jedné
ceny na celý nákup najednou — nejde o novou architekturu.

Topologie zpracování se nevymýšlí znovu — použije se přesně to, co `docs/ai.md` už popisuje:
domácí PC jako **pull worker** přes HTTPS (žádný otevřený port, appka funguje beze změny, když
worker neběží), fronta vzorem `agg.recompute_queue` + `PriceAggregationService.processQueue()`,
verdikty do vlastního schématu `ai` mimo `core`/`agg`. Hardware (RTX 4060 Ti, 8 GB VRAM) a
licenční pravidlo pro modely (jen svobodné licence — Apache-2.0/MIT, tedy řada Qwen nebo
Mistral 7B, ne Gemma/Llama) platí beze změny, viz `docs/ai.md`, „Hardware a volba modelu".

Platí i „**AI nikdy nerozhoduje**" (`docs/ai.md`): vytěžení účtenky vytvoří **návrh k potvrzení
uživatelem**, model sám nezakládá `core.price_observation` ani nic jiného rovnou.

Vnitroobchodní kódy z účtenky nejsou globální identifikátor —
`core.product_code.code_type = STORE_INTERNAL` s povinným `chain_id`
(`docs/datovy-model.md`, „Vnitroobchodní kódy vs. globální EAN"). Párování řádku účtenky na
existující katalogové zboží (podle názvu, kódu, ceny) je hlavní otevřený problém — bez něj se
vytěžená data nedají spojit s tím, co appka už zná.

**Soukromí je tu jiná liga než dnešní fotky.** Účtenka nese čas, místo a celý obsah jednoho
nákupu konkrétního člověka — mnohem citlivější než fotka regálu, ze které `ImageProcessingService`
navíc dnes strhává EXIF (`docs/soukromi.md`). Uložení účtenky jako „mého nákupu" je nová
kategorie osobních dat a bude si žádat vlastní oddíl v `docs/soukromi.md` (výchozí viditelnost,
retence, vztah k pseudonymizaci po 180 dnech) — psát ho zároveň s návrhem téhle funkce, ne
dodatečně.

## Rozšíření číselníku kategorií zboží (fáze 2)

**Stav:** startovní sada 24 kategorií (`2026-08-19/01-category-seed.yaml`) byla nahrazena
plným stromem pro běžný supermarket — `2026-08-20/01-category-tree.yaml`, ~106 položek, šest
kořenů (Potraviny/Nápoje/Drogerie a kosmetika/Domácnost/Dětské zboží/Chovatelské potřeby), max
tři úrovně hloubky, `core.category_i18n` doplněná pro všechny čtyři jazyky mimo češtinu (sk/en/
pl/de). Klienti staví hierarchický výběr (`nz-tree-select` na webu, `SearchableDropdown` nad
`CategoryTree.kt` na mobilu) místo dřívějšího plochého seznamu — viz `docs/lokalizace.md`,
„Kategorie". Zdroj taxonomie je vlastní strom tvarovaný jako supermarket, ne import CPV/CZ-CPA
(úřednické názvy, jiné členění, atribuční povinnost — zvažováno a zamítnuto při návrhu).

**Co je hotové:** `searchProducts` od `2026-08-26/03-product-name-norm-fts.yaml` hledá ZÁROVEŇ
v názvu zboží a v číselníku kategorií — dotaz „mléko" najde i zboží, které to slovo v názvu
nemá, ale je zařazené v kategorii Mléko (nebo jejím podstromu, `core.category.path` prefix
přes hranici `/`, viz `ProductSearchRepositoryImpl` CTE `cat_hit`/`cat_scope`). Kategorie se
matchuje pod lokalizovaným názvem (`core.category_i18n`, fallback `core.category.name`, stejný
zdroj jako `@BatchMapping Category.name`) plus jazykově neutrální `slug`. Vedle toho existuje
i explicitní filtr `searchProducts(categoryId: ID)` — AND nad textovou shodou, bere taky celý
podstrom, neplatné id vrací `CATEGORY_NOT_FOUND` (fixní číselník, ne tiché prázdno jako
u `storeId`/`city`). UI na obou klientech znovupoužívá existující strom (web `nz-tree-select`
+ `buildCategoryTree`, mobil `SearchableDropdown` + `categoryChoicesFor`/`CategoryTree.kt`) —
stejné komponenty jako ve formuláři zboží. Fulltext nad názvem zboží se při té příležitosti
sjednotil s `core.norm_text` (nový `idx_product_name_norm_fts`, starý `idx_product_name_fts`
zrušen), aby „mleko" bez diakritiky našlo „Mléko" stejně spolehlivě jako kategorii.

**Co zůstává:**

- Fasety (`SearchFacets`) se kategorií nerozšiřují — klienti berou číselník z existující
  `Query.categories` (fixní kurátorský strom, ne datově odvozený seznam jako `stores`/`cities`,
  a strom potřebuje i rodiče bez zboží, aby šel poskládat).
- Bez zadaného textu filtr kategorie nic nevrátí (žádný „browse" režim) — chová se stejně jako
  filtr obchod/město.
- `searchFacets(query: String)` argument je pořád ignorovaný (fasety se nefiltrují dotazem).
- **Zadní vrátka pro oficiální kódy** (CPV/CZ-CPA/UNSPSC) zůstávají otevřená, ne implementovaná
  — až bude skutečná potřeba interoperability, jde o samostatnou tabulku
  `core.category_external_code (category_id, scheme, code)` + entita/repozitář + volitelné pole
  na GraphQL `Category` přes stejný `@BatchMapping` vzor jako `categoryName`. Tabulka, ne sloupec
  na `core.category` — jedna nákupní kategorie typicky odpovídá VÍC kódům cizího číselníku (jiné
  členění), 1:1 sloupec by to nedokázal vyjádřit. Klienti by se měnit nemuseli vůbec.
- Číselník je pořád fixní, kurátorský — `createCategory` mutace v GraphQL schématu záměrně
  neexistuje (na rozdíl od zboží/obchodu, které si uživatelé zakládají sami). Další rozšíření
  jde stejnou cestou jako vznik obou sad výš: doplnit řádky do `category.csv`/
  `category-i18n.csv` (`backend/.../db/changelog/2026-08-20/`) a přidat další Liquibase
  changeset ve stejném vzoru — `CategorySeedIntegrationTest` (Testcontainers) hlídá konzistenci
  (`path` odpovídá řetězci rodičů, každá kategorie má překlad pro každý podporovaný jazyk mimo
  češtinu). Otevřená otázka: až narostou requesty od uživatelů na chybějící kategorii (přes
  nahlášení nebo jinak), jestli se přidávání zpřístupní nějakým lehkým UI pro moderátora
  (`ModerationService`, `docs/reputace.md` — „Moderace"), nebo zůstane čistě ruční
  CSV/migrace jako dnes.

## Nákup podle receptu nebo seznamu (fáze 3)

**Zadání:** uživatel vybere recept (způsob zadávání receptů zatím nevymyšlen) nebo vlastní
seznam a appka z něj sestaví nákupní seznam zboží. Nad seznamem dvě možné optimalizace:
appka doporučí **jeden obchod**, kde vyjde nákup celkově nejlevněji s ohledem na kvalitu (ne jen
cenu), nebo vypíše **obchody v okolí**, kde je které zboží ze seznamu nejlevnější.

Data pro obě optimalizace ve velké míře už existují: `agg.price_current` (aktuální cena na
provozovnu, měna součástí primárního klíče), `nearbyStores` (`cube`/`earthdistance`,
`docs/datovy-model.md`) a známka kvality 1–5 (`core.product_quality_rating`,
`QualityRatingService`). Nová je hlavně doména receptů/seznamů a **párování ingredience na
konkrétní zboží** (recept říká „500 g mouky", ne EAN nebo konkrétní produkt) — tohle je jádro
práce, výpočet nejlevnější kombinace je až druhý krok.

Bezkódové druhové položky (`DRAFT`/`isGeneric`, `ProductCatalogService`, `docs/reputace.md` —
„Zboží bez čárového kódu") jsou pravděpodobný most mezi obecnou ingrediencí a konkrétním zbožím
v katalogu — zapsat jako hypotézu k ověření, až se bude tahle funkce navrhovat.

Optimalizace „jeden obchod" musí umět chybějící položku (obchod má v katalogu jen 8 z 10 věcí ze
seznamu) — otevřená otázka, jestli takový obchod z výběru vyřadit, nebo ho penalizovat a přesto
nabídnout.

Kvalita jako součást výběru vedle ceny je přímo v duchu mise projektu („kvalita a lokálnost, ne
jen cena", `CLAUDE.md`, „Přehled projektu") — konkrétní vzorec kombinace ceny a kvality patří
sem do tohohle dokumentu, až vznikne, ne jako konstanty rozeseté po kódu (stejné pravidlo jako
u prahů v `docs/reputace.md`).

Nákupní seznam je záměr konkrétního uživatele, ne veřejný fakt o ceně — patří mezi soukromá data
uživatele (výchozí neveřejné), nikdy do veřejné vrstvy nad katalogem; při návrhu ověřit proti
`docs/soukromi.md`.
