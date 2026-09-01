# Rozvojové požadavky

Tenhle dokument popisuje **cílový stav, ne implementaci** — stejně jako `docs/ai.md` v částech
za MVP (`docs/README.md`, „Terminologie fází" — jedna osa zralosti produktu napříč docs).
Účel je mít jedno místo pro nápady, které nemají zapadnout, než na ně dojde řada — ať se
neroztroušou po kódu nebo po hlavě jako poznámky bez kontextu. Nahrazuje dřívější odkaz na
samostatný plánovací soubor mimo repo, který přestal existovat (viz `CLAUDE.md`, „Přehled
projektu"). Všechno tady patří do stadia „další rozvoj" (po veřejné betě) — položky se
netřídí do vlastních podfází, jen podle toho, jak daleko má která rozmyšlený návrh:

- **NÁPAD** — zadání je jasné, návrh řešení ještě ne.
- **ROZHODNOUT** — návrh existuje, ale visí na něm otevřená otázka, kterou je potřeba zodpovědět
  před psaním kódu.
- **PLÁNOVÁNO** — návrh je hotový a implementovatelný, jen na něj ještě nedošla řada.
- **ČÁSTEČNĚ** — část už je v kódu, zbytek je v jednom z stavů výš.

Zařazení u jednotlivých položek je odhad k datu zápisu, ne závazný harmonogram.

## Název věrnostního programu u typu ceny (PLÁNOVÁNO)

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
„Uživatelská vrstva nad globálními daty"). Klienti od 2026-08-29 nabízejí číselník řetězců
k výběru při zakládání/editaci obchodu (`Query.chains`, `docs/stav-implementace.md`) — vazba
`Store.chain` už tedy jde nastavit z UI. **Otevřená otázka zůstává jen u samotného řetězce:**
`core.retail_chain` je pořád fixní kurátorský číselník naplněný migrací (žádná `createChain`/
`updateChain` mutace), takže než půjde zapsat „Billa klub" na `RetailChain`, je potřeba nejdřív
uživatelskou editaci řetězce vůbec zavést, nebo název dát jen na `core.store` a smířit se s tím,
že se zadává opakovaně za každou provozovnu jednoho řetězce.

Agregace se nemění — jde čistě o zobrazovací text, klíč agregátu zůstává `price_kind`.

## Ceny předem z akčního letáku a časově omezená nabídka (ROZHODNOUT)

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

## Načtení celé účtenky (ROZHODNOUT)

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

**Sběr snímků na klientovi (capture pipeline) — jak se vyhnout obřím souborům.** Naivní panorama
(jeden dlouhý sešitý obrázek celé účtenky) by frontu zatěžoval zbytečně velkými soubory. Cílový
přístup: z kamery (CameraX) se v reálném čase vybírají jen ostré snímky (Laplacianova variance
rozostření — snímek pod prahovou hodnotou se zahodí), každý se detekcí hran a kontur (Canny +
`approxPolyDP` na čtyřúhelník) perspektivně narovná (`warpPerspective`) na přímý pohled, a nový
snímek se uloží jen tehdy, když se jeho spodní okraj posune o víc než ~60 % výšky proti poslednímu
uloženému (coverage tracking, ~40% překryv mezi snímky). Výsledkem je nejvýš pár desítek malých
ostrých výřezů (řádově stovky kB celkem), ne jeden obří soubor ani syrové video. UI dostane živou
zpětnou vazbu (rámeček na účtence, progress bar podle pokrytí), dokud sken sám neskončí. OpenCV
(BSD licence, `CLAUDE.md` — „Pouze svobodné licence") pro tohle stačí — **žádný OCR engine na
klientovi není potřeba**, rozpoznání textu dál dělá vision model na workeru podle topologie výš;
appka jen posílá malé narovnané výřezy místo syrového videa nebo panoramatu. Tím padá i dřívější
úvaha o ML Kitu (Google, vyžaduje GMS) vs. Tesseract pro on-device OCR pro dvě distribuce
appky (F-Droid/Play) — na klientovi žádné rozpoznávání textu neběží, takže se appka kvůli téhle
funkci nemusí ohýbat kvůli druhé licenci ani druhé závislosti jen pro jednu z variant.

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

**Otevřená otázka: záměrně částečný sken.** Hlavička a patička účtenky nesou údaje, které jde
spojit s konkrétním člověkem a jeho nákupem — adresa/IČO provozovny + přesný čas nákupu
prakticky identifikují zákazníka, k tomu často poslední čtyřčíslí platební karty nebo číslo
věrnostní karty. Appka pro cenová data potřebuje jen blok s řádky položek (název, cena,
množství). Záměr do budoucna: capture pipeline výše by měla cíleně sbírat jen tenhle blok, ne
hlavičku/patičku, takže nejcitlivější údaje se na server v ideálním případě vůbec nedostanou.
Konkrétní mechanismus (oříznutí podle pozice v obraze, ruční vymezení oblasti uživatelem, nebo
worker, který citlivé řádky rozpozná a zahodí ještě před uložením) není rozhodnutý — k dořešení
spolu s oddílem v `docs/soukromi.md` zmíněným výš.

## Rozšíření číselníku kategorií zboží (ČÁSTEČNĚ)

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

## Údaje z etikety: nutriční hodnoty, složení, alergeny (ČÁSTEČNĚ — aditiva hotová, zbytek ROZHODNOUT)

**Zadání:** appka dnes o zboží ví jen to, co potřebuje k ceně — název, značka, kategorie,
gramáž/objem, kusů v balení, čárový kód. Nic z etikety (nutriční tabulka, složení, alergeny)
v datovém modelu není. Cíl je posunout appku od „kolik to stojí" k „co v tom je", v duchu mise
(`CLAUDE.md`, „Přehled projektu" — „kvalita a lokálnost, ne jen cena").

**Co je hotové:** aditiva (E-čka) jako odkazy z OFF `additives_tags` (`off.product`,
`ProductGraphQlController.externalLinksFor`, `docs/stav-implementace.md`) — čistě čtecí, beze
změny klientů, karta „Další informace" odkazy renderuje generickým cyklem. Zbytek níž je cílový
stav, ne implementace — žádná migrace, žádné API pole, žádný kód.

Rozděleno na dva kroky, protože každý řeší jiný problém — nejde o obecnou osu fází výš, jen o
pořadí uvnitř týhle jedné položky:

- **Krok A — čtení z OFF.** Pokrývá drtivou většinu balených potravin, nulové riziko ODbL, žádná
  nová editační obrazovka.
- **Krok B — vlastní vrstva.** Pro zboží, které OFF nezná (lokální pekárna, řeznictví) a pro
  opravy chyb v OFF.

**Kam data patří (krok A, vrstva OFF):** rozšíření `off.product` o ploché whitelistované
sloupce — `energy_kcal_100g`, `fat_100g`, `saturated_fat_100g`, `carbohydrates_100g`,
`sugars_100g`, `proteins_100g`, `salt_100g`, `fiber_100g` (NUMERIC), `ingredients_text` (TEXT),
`allergens_tags` (TEXT[]), stejným vzorem jako `additives_tags` výš. OFF `nutriments` je vnořený
objekt s desítkami klíčů — ukládat ho jako JSONB by porušilo dnešní vzor „plochá whitelistovaná
podmnožina, ne syrová kopie odpovědi" (`docs/datovy-model.md`, „Oddělení schémat kvůli ODbL");
`OpenFoodFactsApiClient.ApiProduct` proto dostane vnořený record a vybere z něj jen tyhle klíče.
Čtení skládá `ProductOverlayService` stejným pořadím jako dnes gramáž — komunitní základ → OFF →
osobní patch, vždy na detached kopii. Žádná hodnota z `off.*` se nekopíruje do `core.*`.

**Kam data patří (krok B, vlastní vrstva):** `core.product_nutrition` (PK `product_id`, tytéž
sloupce). Vlastní tabulka, ne sloupce na `core.product` — je jich ~10, vyplněné je bude mít
zlomek zboží, a `core.product` je horká tabulka čtená při každém hledání. Vlastní `data_origin`
na `core.product_nutrition`, ne spoléhat na ten na `core.product` — zboží může mít vlastní název
(`OWN`) a přitom nutrienty opsané z OFF (`OFF_DERIVED`).

**Otevřená otázka — seznamy v patch tabulce:** nutrienty a `ingredients_text` jsou skaláry, do
`core.product_nutrition_user_edit` sednou přesně dnešním vzorem uživatelské vrstvy
(`docs/datovy-model.md`, „Uživatelská vrstva nad globálními daty" — nullable zrcadla +
`cleared_fields`). **Alergeny jsou seznam** a do skalárního patche se nevejdou. Návrh k
ověření: uložit je jako `TEXT[]` a patchovat celý seznam najednou (nahradit, ne slučovat) —
`NULL` znamená nezměněno, zápis do `cleared_fields` znamená „uživatel tvrdí, že tam žádné
nejsou". Bez `cleared_fields` by nešlo odlišit „žádný alergen" (informace) od „nikdo nezadal"
(prázdno) — přesně ten problém, kvůli kterému `cleared_fields` vzniklo.

**Číselník alergenů:** `core.allergen` + `core.allergen_i18n`, stejný vzor jako
`core.category_i18n` (`docs/lokalizace.md`, „Kategorie: `core.category_i18n`, ne klíče v
bundlech") — 14 zákonných alergenů EU je sice fixní seznam, kde by klíče v bundlech obstály, ale
konzistence s aditivy (`core.additive`/`core.additive_i18n`, otevřené jako zadní vrátka v
podobném duchu jako „Zadní vrátka pro oficiální kódy" u kategorií výš) mluví pro stejný
mechanismus.

**Dopad na klienty:** editační formulář zboží v duálním režimu založení/úprava už existuje
(`frontend/src/app/features/product-form/product-form.ts` `product = input<Product | null>(null)`
+ `effect()`, otevřený z detailu; `mobile/.../ui/product/ProductFormScreen.kt` s volitelným
`productId`, otevřený z `ui/detail/ProductDetailScreen.kt`) — stejný vzor jako dřívější inline
editace obchodu (`shared/store-form.ts`/`StoreFormScreen.kt`). Dnes edituje jen pole, která
`UpdateProductInput` umí (název, značka, kategorie, gramáž/objem, kusů v balení, váhové zboží);
Krok B na něj nutrienty/alergeny/etiketu jen doplní — nejde o novou obrazovku. Krok A (jen čtení
z OFF) klienty nutí míň — nová karta na detailu (`productDetailFieldsFragment`/
`PRODUCT_DETAIL_FIELDS`) a i18n × 5 jazyků, žádná editace.

**Odpovědnost za alergeny — rozhodnout před spuštěním, ne až se to stane.** Alergeny jsou
zdravotní údaj, ne cena. Chybná cena mrzí; chybné „neobsahuje lepek" je jiná třída rizika než
cokoli, co appka dnes nese, a `docs/podminky-uziti.md` §9 (bez záruky) na to samo nestačí.
Otevřené otázky k rozhodnutí: zobrazovat alergeny jen z OFF, nebo i z uživatelského zadání (a
pokud ano, vždy viditelně označené „zadal uživatel, ověř na obalu")? A hlavně — nikdy nezobrazovat
negativní tvrzení („neobsahuje lepek") na základě prázdného seznamu, jen „neuvedeno": prázdno
není důkaz nepřítomnosti. Patří sem i odstavec do `docs/podminky-uziti.md` §8/§9.

**Vazba na fotku etikety a lokální AI:** `core.media.photo_kind = LABEL` (formulář nového zboží,
`docs/ai.md`) je zamýšlený budoucí vstup pro čtení složení/textu z etikety — pro zboží, které OFF
nezná, pravděpodobně jediná praktická cesta k nutričním datům bez ODbL zátěže (fotka etikety je
vlastní data, ne cizí). Navazuje na `docs/ai.md`, „Čtyři úlohy a jejich pořadí" jako pátá úloha,
stejnou infrastrukturou (fronta vzorem `agg.recompute_queue`, verdikty do schématu `ai`, AI nikdy
nerozhoduje sama — jen předvyplní formulář, potvrzuje člověk).

Agregace, ceny ani reputace se tímhle nedotknou vůbec — údaje z etikety jsou atribut katalogu, ne
vstup do váženého mediánu.

## Nákup podle receptu nebo seznamu (NÁPAD)

**Zadání:** uživatel vybere recept (způsob zadávání receptů zatím nevymyšlen) nebo vlastní
seznam a appka z něj sestaví nákupní seznam zboží. Nad seznamem dvě možné optimalizace:
appka doporučí **jeden obchod**, kde vyjde nákup celkově nejlevněji s ohledem na kvalitu (ne jen
cenu), nebo vypíše **obchody v okolí**, kde je které zboží ze seznamu nejlevnější.

Data pro obě optimalizace ve velké míře už existují: `agg.price_current` (aktuální cena na
provozovnu, měna součástí primárního klíče), `nearbyStores` (`cube`/`earthdistance`,
`docs/datovy-model.md`) a hodnocení kvality hvězdičkami 1–5 (`core.product_quality_rating`,
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

## Nápověda k polím při zadání nového zboží (PLÁNOVÁNO)

**Zadání:** popsat, co konkrétně patřit do jednotlivých políček formuláře zadání nového zboží —
dnešní labely samy o sobě nemusí stačit (např. co přesně je „gramáž/objem" u kusového zboží,
jaký formát čeká pole s vnitroobchodním kódem). Jde o UI text (nápověda pod polem, placeholder
nebo tooltip), ne o změnu datového modelu ani validace, ta už existuje (`product-form-
validation.ts` na webu, `ProductFormViewModel.kt` na mobilu). Rozsah: formulář založení/editace
zboží na obou klientech, případně i lokalizační soubory (`docs/lokalizace.md`), pokud nápověda
půjde přes překladové klíče.

## Kalkulačka a porovnání cen při zadání zboží (PLÁNOVÁNO)

**Zadání:** při zadávání nebo prohlížení zboží doplnit pomocné přepočty, aby šlo cenu z regálu
hned posoudit, ne až zpětně v grafu:

- balení víc kusů za jednu cenu (např. 6 limonád za 89,90) → dopočítat cenu za kus,
- cena za nestandardní gramáž (např. 125 g za 45,-) → dopočítat cenu za kg,
- porovnání dvou balení stejného druhu zboží s různou gramáží (0,5 kg za 300,- vs. 250 g za
  180,-) → která varianta je ve skutečnosti levnější,
- ikona „porovnat" — zapamatovat si prohlížené zboží a porovnat ho s příště naskenovaným.

Cena za jednotku hmotnosti/objemu se dnes už počítá jako `GENERATED ALWAYS` sloupec nad
`net_content_base` (`docs/datovy-model.md`, „`core.price_observation` — jádro celé aplikace"),
takže přepočet na kg/l při zadání jedné položky je hlavně o tom, zobrazit existující hodnotu
živě ve formuláři, ne o nové datové struktuře. Přepočet na **kus** je jiná jednotka než gramáž/
objem — na rozdíl od dřívějšího stavu už je `pieces_in_pack` v modelu (`Product.piecesInPack`,
`docs/stav-implementace.md`), otevřená otázka je jen, jestli se z něj má počítat jednotková
cena za kus (analogicky ke `GENERATED` sloupci výš), nebo je to čistě klientský přepočet.
Porovnání dvou zboží
(existující vs. právě skenované) může jít čistě přes existující data (`agg.price_current` pro
obě položky) bez nové perzistence — „zapamatované" zboží pro porovnání stačí držet v paměti
klienta, dokud se appka nezavře.

## Drobné zbytky z kontroly lokalizace data a čísel (ROZHODNOUT + PLÁNOVÁNO)

Při opravě lokalizace kalendáře a posunu dne (2026-08) vyšly najevo dvě menší nesrovnalosti,
které nesouvisely s opravovaným problémem a nebyly opraveny — zapsáno sem, ať nezapadnou.

**Čas dne se nikde nezobrazuje ani nezadává.** `core.price_observation.observed_at` je
`TIMESTAMPTZ` (celý okamžik, ne jen den), ale žádný klient čas dne neukazuje (web
`FormatService.date()` používá jen `dateStyle`, nikdy `timeStyle`; mobilní `formatShortDate`
podobně jen `FormatStyle.MEDIUM` pro datum) ani nezadává (web `nz-date-picker` bez
`nzShowTime`, mobilní `DatePickerDialog`, ne `DateTimePickerDialog`). Zápis ceny tak vždy nese
buď přesné „teď" (mikrosekundová přesnost), nebo poledne vybraného dne (`toObservedAtIso`
na webu i na mobilu) — nikdy čas, který si uživatel sám zvolil. Otevřená otázka: stálo by za to
umožnit zadat i čas (typicky nepodstatné pro cenu v regálu), nebo je „jen den" záměr, který
patří zdokumentovat jako vědomé rozhodnutí, ne mezeru.

**Příklad ceny v nápovědě je natvrdo v CZK.** `frontend/src/app/features/product-form/
product-form.ts` (`codeHintExample()`, `format.money(45, 'CZK')`) a mobilní
`ui/product/ProductFormScreen.kt` (`rememberMoneyFormatter("CZK").format(45)`) ukazují ukázkovou
cenu vždy v korunách bez ohledu na zvolenou zemi/zobrazovací měnu (`docs/lokalizace.md`) —
jediná drobnost, kde appka měnu nedomýšlí z kontextu jako všude jinde. Oprava je triviální
(`currencyForCountry`/`CountryService.country()` už existují a používají se vedle), jen to
nesouviselo s kalendářem/posunem dne, který se právě opravoval, proto zůstalo stranou.
