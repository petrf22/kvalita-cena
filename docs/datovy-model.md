# Datový model

Zdroj pravdy pro schéma je Liquibase: `backend/src/main/resources/db/changelog/`. Tento
dokument vysvětluje ROZHODNUTÍ za schématem — proč jsou tabulky rozdělené tak, jak jsou —
ne úplný výpis sloupců (ten je v changelogu, tam by z něj snadno rozjel nesoulad).

## Oddělení schémat kvůli ODbL

```
core   vlastní komunitní data (katalog, obchody, ceny, recenze, ...)
auth   účty, přihlašovací výzvy, tokeny
agg    předpočítané agregáty pro grafy
off    data z Open Food Facts — hromadně/podstatně se nekopírují do core.*
osm    souřadnice provozoven z OpenStreetMap — schéma zatím nemá jedinou tabulku, je to
       rezervace pro budoucí synchronizaci; dnešní jediný dotek s OSM je jednotlivě
       zvolený geokódovaný výsledek uložený do core.store, viz níž
fx     kurzovní lístek ČNB (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna") —
       na rozdíl od off/osm sem appka sama PÍŠE, denně (ExchangeRateSyncService, hotovo)
```

Open Food Facts **i OpenStreetMap** jsou pod licencí ODbL se share-alike podmínkou. ODbL
rozlišuje **odvozenou** databázi (share-alike se vztáhne na celek) a **kolektivní** databázi
(nezávislá data u sebe mohou zůstat pod vlastní licencí, viz ODbL 1.0 a OSMF Collective
Database Guideline) — smíchání dat proto automaticky nerozšiřuje share-alike na celou appku
tak jednoznačně, jak by se čekalo. Oddělení schémat níž je proto **projektová bezpečnostní
politika, zvolená vědomě přísněji, než licence vyžaduje** — konzervativní, ne právně nutná.
Na OSM se přitom snadno zapomíná, protože souřadnice nevypadají jako "databáze" — jsou to
jen dvě čísla u obchodu.

Pravidla, která to drží čisté:

1. Žádný **hromadný ani podstatný výřez** `off.*`/`osm.*` se nekopíruje do `core.*`. Spojení
   vzniká až při čtení v service vrstvě; UI vždy uvede zdroj a licenci. Výjimka: jednotlivě
   zvolený geokódovaný výsledek (lat/lon + `osm_ref` konkrétního kandidáta) se uložit smí,
   s `geo_source` jako značkou původu — viz `core.store.geo_source` níž.
2. Pokud uživatel ručně opíše údaj z OFF do `core.product`, nastaví se
   `data_origin = 'OFF_DERIVED'` a tenhle produkt se vyloučí z "čistého" exportu.
3. Čistý export vlastních dat: `pg_dump --schema=core --schema=agg`. **Pozor:**
   `core.product_review.user_id` (hodnocení kvality a text recenze, viz níže) se do „čistého"
   exportu nesmí dostat beze změny — je to jediné místo v `core.*`, kde vazba na uživatele
   nepodléhá pseudonymizaci po 180 dnech (na rozdíl od `price_observation.submitter_id`,
   viz `docs/soukromi.md`). Export musí sloupec vynechat nebo hashovat, jinak GDPR záruka
   „starší příspěvky už o mně nikdo nedohledá" pro `pg_dump` ticho neplatí.
4. Aplikační DB uživatel má na `off`/`osm` jen `SELECT` mimo dedikovaný synchronizační job —
   technická pojistka, ne jen dohoda v hlavě. (U `osm` je to dnes bezpředmětné — schéma je
   prázdné.)

`core.store.geo_source` rozlišuje `COMMUNITY` (zadal uživatel) od `OSM` (převzato) —
i tak souřadnice zůstávají v `core.store`, protože jsou to fakta o konkrétní provozovně
potřebná pro běžný provoz appky (vyhledávání), ne surová OFF/OSM data k re-exportu.

## `core.price_observation` — jádro celé aplikace

Čtyři rozhodnutí, která by se zpětně opravovala nejhůř:

**Jednotková cena se počítá jako `GENERATED ALWAYS ... STORED` sloupec**
(`price_amount / NULLIF(net_content_base, 0)`), ne v Javě. Díky tomu je konzistentní
napříč všemi cestami zápisu (import, ruční oprava v DB) a jde podle ní přímo indexovat.
Liquibase `createTable` neumí generated sloupce vyjádřit přímo — proto je v changelogu
zvlášť `ALTER TABLE ... ADD COLUMN` (viz `2026-07-26/05-price-observation.yaml`).

**`net_content_base` je SNAPSHOT, ne odkaz na aktuální produkt.** Kdyby někdo později
opravil gramáž produktu (500 g → 450 g), přepsalo by to zpětně jednotkové ceny celé
historie a graf by najednou "lhal" o minulosti.

**`observed_at` ≠ `created_at`.** `observed_at` je, kdy uživatel cenu VIDĚL (může být
offline zápis z mobilu, doplněný později); `created_at` je, kdy záznam skutečně došel na
server. Agregace a grafy pracují s `observed_at`.

**`price_kind` je součástí klíče každého agregátu.** `REGULAR`, `PROMO`, `CLUB_CARD`,
`CLEARANCE`, `MULTIBUY` se nikdy nemíchají do jedné řady — klubová cena zvlášť je pro
misi projektu podstatná: kdo nemá věrnostní kartu, platí jinou cenu, a přesně to má být
vidět, ne zprůměrované pryč. **Unikátnost observace zahrnuje druh ceny** — u regálu bývá
cenovka dvojitá i trojitá (běžná/klubová/množstevní), takže `uq_price_observation_
submitter_kind_per_day` dovoluje nejvýš jeden záznam na uživatele/produkt/obchod/den/druh
ceny, ne jen na den. Ceny z jedné cenovky se zapisují jedním voláním
`submitObservations` (`SubmitObservationsInput.prices`) v jedné transakci — kolize
jediného druhu ceny shodí **celou dávku** (uživatel má jeden formulář, ne pět opakovaných
zápisů), a celá dávka dělá jen jednu položku `agg.recompute_queue`.

**Platnost akce (`promo_valid_from`/`promo_valid_to`) se filtruje při čtení, ne v agregaci.**
Obě pole jsou nepovinná a smí je mít jen `PROMO` — `promo_valid_from` nesmí být v budoucnu,
protože se zapisuje cena, kterou uživatel VIDĚL v regále, ne cena z letáku, která ještě
nezačala platit (ta zůstává mimo tenhle model, viz `docs/rozvoj.md`, "Ceny předem z akčního
letáku"). `agg.price_current.promo_valid_to` drží `MAX(promo_valid_to)` napříč observacemi
buňky (raději akci zobrazit o něco déle než skrýt ještě platnou) — `PriceAggregationService`
ho jen dopočítává, samotné vyřazení vypršelé akce z `Product.prices` dělá až
`ProductGraphQlController` při čtení. Žádný noční job, žádný přepočet navíc: historie v
`agg.price_daily` a počet přispěvatelů ve `ProductStats` se vypršením akce nemění.

### Vnitroobchodní kódy vs. globální EAN

`core.product_code.code_type`:
- `GTIN` — normalizovaný EAN na **GTIN-14** (doplněný nulami zleva), platí globálně.
- `PLU` — kód bez celostátní databáze (typicky ovoce/zelenina).
- `STORE_INTERNAL` — kódy váhového zboží (`28xxxxxxxxxxx` apod.), které **nesou v sobě
  cenu** a v každém řetězci znamenají něco jiného. Mají **povinný `chain_id`**
  (`chk_product_code_chain_scope`) a NIKDY se nepoužijí jako globální identifikátor napříč
  řetězci — to je nejčastější tichá chyba podobných projektů.

Unikátnost `(code, code_type, COALESCE(chain_id, 0))` je proto raw SQL unique index, ne
prostý sloupcový `unique: true` — potřebuje `COALESCE`, protože globální GTIN kódy mají
`chain_id IS NULL` a přesto musí zůstat jedinečné.

### Past: `date_trunc` není IMMUTABLE

Unikátní index „1 záznam / uživatel / produkt / obchod / den / druh ceny"
(`uq_price_observation_submitter_kind_per_day`) potřeboval `date_trunc('day', observed_at)` ve
výrazu indexu. `date_trunc(text, timestamptz)` je ale jen `STABLE` (výsledek závisí na
timezone session), ne `IMMUTABLE`, takže ho Postgres do indexu odmítne
(`functions in index expression must be marked IMMUTABLE`). Řešení: vlastní immutable
wrapper `core.day_utc(TIMESTAMPTZ) RETURNS DATE` s pevným `AT TIME ZONE 'UTC'`
(`2026-07-26/05-price-observation.yaml`) — obecný vzor, kdykoliv je potřeba datum/čas ve
výrazu indexu.

## Agregace jsou tabulky, ne materialized view

`agg.price_current` (PK `product_id, store_id, price_kind, currency`) a `agg.price_daily` (PK
navíc `day`, doplněno pro graf vývoje ceny — viz `2026-08-05/02-agg-price-daily.yaml`) se plní
přes `agg.recompute_queue`, ne `REFRESH MATERIALIZED VIEW`. `currency` přibyla do obou PK až
`2026-08-09/01-agg-currency.yaml` (docs/lokalizace.md, „Multi-měna") — bez ní by u příhraniční
prodejny s částí záznamů v CZK a částí v EUR vážený medián počítal z čísel ve dvou měnách.

Důvod: váhy záznamů se mění **zpětně** — klesne někomu reputace, odhalí se sybil klastr —
takže je potřeba cílený přepočet konkrétních buněk (`product_id, store_id`), ne přepočet
celé view. Při milionech observací by `REFRESH MATERIALIZED VIEW` bylo o řády dražší než
fronta + upsert nad změněnými buňkami. `agg.recompute_queue` zatím nezná `day` — přepočet
denní řady tedy vždy smaže a znovu spočítá celou historii buňky (`product_id, store_id`),
ne jen změněný den. Pro dnešní objemy v pořádku; až přepočet začne měřitelně trvat, signál
je přidat nullable `day` do fronty (`NULL` = přepočítat vše), ne to řešit preventivně teď.

Čtení grafu je vždy z `agg.*`, nikdy ze syrových `core.price_observation` — index-only
scan, jednotky milisekund i při statisících záznamů.

**Den v `agg.price_daily.day` = `core.day_utc(observed_at)`**, tatáž funkce jako u
`uq_price_observation_submitter_kind_per_day` (viz níže) — záměrně, aby „den" znamenal v celé
databázi jednu a tutéž věc. Důsledek: cena zapsaná po půlnoci UTC (např. 01:30 SELČ v létě)
spadne do předchozího dne podle SELČ. Pro graf je to kosmetika, ne chyba; kdyby se to
v budoucnu mělo přepnout na den podle pražského času, je to jedna nová `core.day_prague()`
IMMUTABLE funkce + re-enqueue všech buněk fronty (levná migrace, protože se nemění nic
jiného).

## `cube`/`earthdistance` místo PostGIS

`nearbyStores(lat, lon, radius)` potřebuje jen "najdi provozovny do N km", ne obecnou
geometrii. PostGIS je na to výrazně silnější nástroj, ale je pod GPLv2 — u projektu, který
si výslovně žádá jen svobodné licence bez rizika budoucího zpoplatnění, to zbytečné riziko.
`cube` + `earthdistance` jsou rozšíření pod licencí PostgreSQL (stejná jako jádro) a na
tento jeden dotaz plně stačí:

```sql
CREATE INDEX idx_store_geo ON core.store USING gist (ll_to_earth(lat, lon));
```

Pokud by v budoucnu přibyla potřeba složitější geometrie (polygony spádových oblastí
apod.), je to signál k přehodnocení, ne důvod sahat po PostGIS hned teď.

## Identita provozovny

Dřív vstupovaly obchody do appky jen dvěma cestami: `nearbyStores` (nutná poloha) nebo
seed. Kdo nechce sdílet polohu, nebo zapisuje ceny zpětně doma, potřebuje třetí cestu —
napsat název/město a obchod ručně dohledat nebo založit (`searchStores`, `createStore`,
`StoreService`). Tři rozhodnutí za tím:

**`core.store.lat`/`lon` jsou nullable.** Obchod založený doma bez GPS musí jít uložit i bez
souřadnic — doplní je až geokódování (`geocodeAddress`, viz níže) nebo někdo, kdo u obchodu
fyzicky stojí. `StoreRepository.findNearby` proto explicitně filtruje `lat IS NOT NULL AND
lon IS NOT NULL` — GiST index nad `ll_to_earth(lat, lon)` řádky bez souřadnic sám nevyloučí
z plánu bez seq scanu.

**`core.norm_text(text)` — immutable normalizace pro hledání a unikátnost**
(`2026-08-06/01-text-normalization.yaml`): malá písmena, bez diakritiky, sjednocené mezery.
Používá se v `uq_store_identity` (**country** + název + město + ulice, tvrdá pojistka proti
duplicitám — klient si musí před uložením ověřit `searchStores`, tohle je až poslední obrana;
`country` přibyla do indexu až `2026-08-16/01-store-country.yaml` — do té doby dvě stejnojmenné
provozovny ve stejnojmenném městě ve dvou zemích kolidovaly, `docs/lokalizace.md`, „Country
selector v UI"), v
`uq_product_generic_name_scope` (druhové položky v rozsahu prodejce, viz níže a
`docs/reputace.md`) a v `pg_trgm` indexech pro podobnostní hledání. Past stejného druhu jako
`core.day_utc` u `date_trunc`
(„Past: `date_trunc` není IMMUTABLE" výš): **obě varianty `unaccent()` (jedno- i
dvouargumentová) jsou ve skutečnosti `STABLE`**, ne `IMMUTABLE`, přestože se dvouargumentová
varianta po síti běžně mylně vydává za immutable. Deklarovat obalovou funkci jako IMMUTABLE
navzdory tomu Postgres při prostém volání nerozporuje, ale při `CREATE INDEX` ji zkusí
inlinovat — a selže na „text search dictionary unaccent does not exist", protože se
`'unaccent'::regdictionary` musí dohledat podle `search_path` zrovna v kontextu inliningu, kde
`public` není k dispozici. Řešení v migraci: slovník i schéma rozšíření se vyhledají JEDNOU
za běhu migrace (`pg_ts_dict`/`pg_extension`) a jejich OID/jméno se natvrdo vloží do textu
definice funkce (`format(...) + EXECUTE`) — ve výsledném těle je pak jen numerický OID a plně
kvalifikované jméno, žádná identifikace závislá na `search_path`.

### Identita bezkódového zboží je lokální k prodejci

`core.product.catalog_scope` rozlišuje `GLOBAL` (GTIN/OFF), `CHAIN`, `STORE` a migrační
`LEGACY_GLOBAL`. U lokální druhové položky je právě jeden z `scope_chain_id`/`scope_store_id`
povinný a `chk_product_catalog_scope` hlídá všechny kombinace. Provozovna s řetězcem vždy
zakládá `CHAIN`, nezávislá provozovna `STORE`; unikátní index
`uq_product_generic_name_scope` brání duplicitě normalizovaného názvu a kategorie pouze ve
stejném rozsahu. Cena lokálního produktu se v jiné provozovně/řetězci odmítne i serverem —
klientský filtr není bezpečnostní hranice. Staré jednoznačné druhové produkty migrace přiřadí
podle jejich cen, nejednoznačné označí `LEGACY_GLOBAL` a zachová jen kvůli historii.

Varianty názvu jsou oddělené od kanonického produktu: `core.product_alias` má normalizovaně
unikátní `(product_id, name)`, stav `PENDING`/`ACTIVE` a trigramový index.
`core.product_alias_confirmation` dokládá potvrzení různými registrovanými uživateli; vzniká
výhradně spolu s úspěšným zápisem ceny a `UNIQUE (alias_id, user_id)` brání dvojímu hlasu.
Po prahu `app.catalog.alias-confirmations` (výchozí 2) se alias aktivuje pro všechny, předtím
jej ve výsledcích vidí jen jeho potvrzovatel. `user_id` potvrzení je po 180 dnech nebo při
smazání účtu nulováno, alias i jeho už získaný stav ale zůstávají komunitním údajem.

Moderátorské `mergeProducts(sourceId, targetId)` je transakční konsolidace dvou druhových
položek. Ověří rozsah cíle, přesune observace, recenze, média, patche, kódy i aliasy, vyřeší
unikátní kolize ve prospěch cíle, smaže staré agregáty a zařadí přepočet cílových buněk.
Zdroj zůstane jako `MERGED` s `merged_into_id`; přímé čtení starého ID vrátí kanonický cíl.
Původní název zdroje se aktivuje jako alias, takže sloučení nezhorší našeptávání.

**IČO (`core.store.ico`, `core.store.ico_verified_at`) je volitelný identifikátor
provozovatele** — pro podnikovou prodejnu zemědělského družstva nebo OSVČ, kde samotný název
nestačí k jednoznačné identifikaci. Validuje se jen tvar (8 číslic + kontrolní součet modulo
11, `IcoValidator`) — existenci firmy volitelně potvrdí `companyByIco` (ARES, veřejný
rejstřík ekonomických subjektů ČR). ARES **není ODbL data** (na rozdíl od OSM/OFF), nemusí se
tedy držet mimo `core.*`; ukládá se ale jen to, co uživatel skutečně potvrdí uložením obchodu,
ne celá odpověď ARES.

**Geokódování (OpenStreetMap Nominatim, `geocodeAddress`, `GeocodingService`) jde vždy ze
serveru, nikdy z klienta** (`docs/soukromi.md` — jinak by šla Nominatimu přímo IP uživatele).
Nominatimova usage policy vyžaduje identifikovatelný `User-Agent` a nejvýš 1 dotaz/s —
`GeocodingService` obojí vynucuje. Do `core.store` se z odpovědi dostane jen lat/lon a
`osm_ref` **zvoleného** kandidáta s `geo_source = 'OSM'`, nic dalšího — žádný import POI,
žádná surová kopie odpovědi. Výpadek/timeout Nominatimu se projeví jako prázdný seznam
kandidátů, nikdy jako chyba: založení obchodu bez souřadnic musí projít i tak.

## Hodnocení kvality a text recenze — jeden záznam, ne dvě entity

`core.product_review` (`2026-08-05/01-product-quality-rating.yaml`,
`2026-08-30/01-quality-stars.yaml`, přejmenování a text `2026-09-01/02-rename-product-review.yaml`
a `2026-09-01/03-product-review-text.yaml`) nese hvězdičky POVINNĚ (`stars SMALLINT CHECK
(1–5)`, 5 nejlepší; sloupec se dřív jmenoval `grade` a škála byla obrácená jako školní známka,
viz `docs/reputace.md`) a text recenze VOLITELNĚ (`text TEXT`, max 1000 znaků přes CHECK,
`text_updated_at` — na rozdíl od `updated_at` se nehýbe při pouhé změně hvězdiček, takže z něj
klient pozná štítek „upraveno" u textu). Jeden záznam na dvojici `(product_id, user_id)`, ne dvě
tabulky — text bez hvězdiček nedává smysl a oddělená entita by znamenala dva zdroje pravdy pro
totéž hodnocení. Jedno hodnocení na uživatele a produkt vynucuje `UNIQUE (product_id, user_id)`
a backendový upsert (`ON CONFLICT ... DO UPDATE`, nedotýká se `text`/`text_updated_at`) —
opakované hodnocení hvězdičkami existující text nemaže.

`hidden_at` (skrytí po nahlášení, `RecordType.REVIEW` v `core.record_flag`) a partial index
`idx_product_review_listing (product_id, created_at DESC) WHERE text IS NOT NULL AND hidden_at
IS NULL` — výpis recenzí pod zbožím vždy filtruje přesně takhle.

Cena za tuhle jednoduchost: vazba `user_id` **se nepseudonymizuje** po 180 dnech jako
`price_observation.submitter_id` — bez trvalé vazby by nešlo vynutit „jedno hodnocení na
uživatele". Je to vědomé zhoršení proti běžnému pravidlu projektu, podrobně v
`docs/soukromi.md` (`ON DELETE CASCADE` při smazání účtu, `user_id` nikdy ven přes API, pozor
na `pg_dump` export výše) — **s jednou vědomou výjimkou**: autor recenze je od tohohle
rozšíření vidět na veřejném typu `ProductReview.authorPublicUid`/`authorName`, viz
`docs/soukromi.md`, „Podepsaná recenze".

Text recenze jde nahlásit stejným kanálem jako zboží/obchod/fotka (`flagRecord(recordType:
REVIEW, recordId: <core.product_review.id>)`) — nahlašuje se TEXT, ne autor ani hodnocení
samotné (`docs/reputace.md`, „žádné veřejné negativní hodnocení uživatelů"). Práh skrytí
(`app.moderation.review-flags-to-hide`) je mezi fotkou (nejnižší) a zbožím/obchodem.

## Uživatelská vrstva nad globálními daty

Do teď platilo: `createProduct`/`createStore` zapíšou řádek do `core.product`/`core.store`
a ten je hned společný pro všechny; upravit existující globální záznam nešlo vůbec. Cílem
`2026-08-06/04-user-layer.yaml` je oddělit **globální ověřená data** (výchozí, co vidí
každý) od **uživatelské vrstvy** (co k nim ten který člověk doplnil nebo opravil), aniž by
úprava jednoho uživatele rozbila data ostatním.

**Úprava jde do vedlejší "patch" tabulky (`core.product_user_edit`, `core.store_user_edit`),
ne do kopie globálního řádku.** PK `(product_id, user_id)` resp. `(store_id, user_id)`,
sloupce jsou nullable zrcadla editovatelných polí — `NULL` znamená "nezměněno". Kopie celého
řádku byla zavržená hned ze dvou důvodů: produkt/obchod by měl dvě `id`, což by rozbilo
`uq_product_code_code_type_chain` (stejný EAN na dvou produktech) i agregaci
`agg.price_current` (`price_observation.product_id` by ukazoval na originál, ne na kopii, a
cena zapsaná uživatelem k jeho kopii by se v agregátu nikdy neobjevila). Jedno `id` po celou
dobu životnosti záznamu je přesně to, co tenhle problém obchází.

**`cleared_fields TEXT[]`** na obou patch tabulkách odlišuje "pole nezměněno" (`NULL` ve
sloupci) od "uživatel volitelné pole vědomě vymazal" (např. odstranění chybně zadaného IČO)
— bez něj by šlo pole jen přepsat, nikdy smazat, protože `NULL` už má jiný význam.

**Tři nové sloupce na `core.product`/`core.store`** připravují budoucí konsolidační job (jeho
vyhodnocovací pravidlo zatím není známé, proto se zatím nepíše, jen datový model pro něj):

| Sloupec | Význam |
|---|---|
| `verified_at` | Job uznal záznam za globální/ověřený. `NULL` ⇒ klient zobrazí štítek "neověřeno" — dnes tedy úplně všechno kromě seedu, to je očekávaný stav, ne chyba. |
| `processed_at` | Job se na patch/záznam podíval. Zpracováno ≠ uznáno za globální — odlišné od `verified_at`. Každá další úprava patche `processed_at` vynuluje (`CatalogEditService`), protože dřívější zpracování se týkalo starého obsahu. |
| `hidden_at` | Skryto po nahlášení (`core.record_flag`, viz `docs/reputace.md`), čeká na přezkum. Vidí ho jen autor. |

Index `(processed_at) WHERE processed_at IS NULL` na obou patch tabulkách je fronta, kterou
bude konsolidační job odebírat — existuje už teď, i když job ještě neběží.

**Překryv (patch nad globálem) se skládá čtením, nikdy zápisem do spravované entity.** Kdyby
se hodnoty z patche nasypaly do JPA entity `Product`/`Store` uvnitř transakce, Hibernate by
je při flushi propsal zpátky do globálního řádku — přesně to, čemu se celý návrh vyhýbá.
Řešení backendu (`ProductOverlayService`, `StoreOverlayService`) je vracet **detached kopii**
entity (`entity.toBuilder().pole(patchováHodnota).build()`) — takový objekt nikdy neprošel
`EntityManager.find()`, Hibernate ho tedy nesleduje a nemá jak zapsat zpátky, i kdyby se o to
někdo pokusil. Efekt je stejný jako u typované read-only projekce, jen bez nutnosti
zdvojovat GraphQL typ zvlášť pro "s překryvem" a "bez překryvu" — CLAUDE.md požaduje pro
podobné případy "autorizaci jako predikát v dotazu, ne filtr v resolveru"; tady je to
"překryv v service vrstvě čtení, nikdy mutace spravované entity", stejný princip jinou
cestou.

**Výjimka z pravidla výš: `core.store.country`.** Na rozdíl od zbytku téhle vrstvy má country
tvrdý dopad na měnu zápisu (`CurrencyResolver.forStore`) a validaci IČO/NIP pro VŠECHNY
uživatele, ne jen na to, jak provozovnu vidí autor patche — kdyby šla přes `store_user_edit`
jako ostatní pole, autor by ve svém vlastním pohledu viděl opravenou zemi, ale
`submitObservation` (čte spravovanou entitu přímo, ne přes overlay) by dál ukládal ceny ve
staré měně, a všem ostatním by se obchod dál tvářil jako z jiné země. `CatalogEditService.
updateStore` proto `country` zapisuje rovnou do spravované entity `core.store`
(`store.setCountry(...)`, `storeRepository.save(store)`), gatováno `TrustLevelService.
isTrusted` — jediné pole v `updateStore`, které mutuje spravovanou entitu uvnitř transakce.
`store_user_edit.country` byl zrušen (`docs/lokalizace.md`, „Country selector v UI"), aby
zůstal jen jeden způsob, jak se country vůbec dá změnit.

**Viditelnost pod prahem důvěry** (práh je popsaný v `docs/reputace.md`) řeší stejný predikát
všude, kde se vrací produkt/obchod: `status = 'ACTIVE' OR created_by_user_id = viewerId`.
Hledání (`ProductSearchRepositoryImpl`) skládá kandidáty ve čtyřvětvém CTE `candidate`
(UNION, ne jeden OR přes čtyři tabulky) — název zboží (`idx_product_name_norm_fts`, nad
`to_tsvector('simple', core.norm_text(name))`), uživatelský patch názvu (`core.product_user_edit
.name`, malý LEFT JOIN jen pro jednoho viewera), kategorie (podstrom podle `core.category.path`,
`idx_product_category_status`) a čárový kód (`core.product_code`, jen GTIN). Stejný důvod jako
u původní dvouvětvé fulltextové podmínky: `to_tsvector('simple', COALESCE(e.name, p.name))` by
obešel index a vynutil seq scan; víc nezávislých větví umožňuje planneru použít index každé
zvlášť. Viditelnost i explicitní filtr `categoryId` se aplikují AŽ na sjednocené kandidáty
(CTE `matched`), ne opakovaně v každé větvi. Skryté (`hidden_at`) záznamy z hledání zmizí úplně,
i autorovi — přímý dotaz `product(id)`/`productByCode` je autorovi pořád ukáže, s příznakem.

**Čtecí cesta k vlastní uživatelské vrstvě** — GraphQL `myProducts`/`myStores`/
`myObservations`/`myEdits` (`MyContributionsGraphQlController`/`MyContributionsService`,
vyžadují přihlášení) vrátí přihlášenému vlastní založené zboží/obchody, vlastní zapsané ceny
a vlastní patche (`core.product_user_edit`/`core.store_user_edit`) nad cizími záznamy —
poslední jmenované se ve výpisu vždy označí `PublicationState.PENDING_MERGE`, protože
konsolidační job (viz výš) zatím neběží. `myProducts`/`myStores` k tomu dopočítají i konkrétní
`confirmationsReceived`/`confirmationsRequired` (viz `docs/reputace.md`, "Práh důvěry pro
zveřejnění nového záznamu") — bez toho by uživatel viděl jen "čeká na potvrzení" bez čísel.

## Fotky zboží a provozoven

`core.media` (`2026-08-08/01-media.yaml`) nese jen metadata jedné fotky — binární obsah
(originál i náhled) leží MIMO databázi, na disku pod `app.media.root`
(`MediaStorage`/`LocalFileSystemMediaStorage`, rozhraní kvůli pozdější výměně za S3/MinIO).
Důvod je stejný jako u oddělení `off`/`osm` výš, jen obráceně: tady nejde o cizí licenci, ale
o to, že by tisíce binárních souborů v Postgresu zbytečně nafoukly `pg_dump --schema=core
--schema=agg` (čistý export vlastních dat, viz „Oddělení schémat" výš) — ten zůstává malý,
zálohu adresáře s fotkami řeší nezávislý mechanismus (rsync/snapshot disku), ne `pg_dump`.

**`record_type`/`record_id` je stejný polymorfní vzor bez FK jako `core.record_flag`** —
`core.media.record_type` nese jen `PRODUCT`/`STORE` (čí je fotka), `RecordType` v GraphQL má
navíc `PHOTO` pro `flagRecord` (nahlašovaný typ je jiná osa než "čí je fotka"). Cizí klíč na
dvě různé tabulky najednou v Postgresu nejde, stejná úvaha jako u nahlášení.

**Fotky se nahrávají výhradně na existující záznam** (žádné osiřelé uploady) přes REST
`POST /api/media/{recordType}/{recordId}` — GraphQL zůstává pro metadata (`Photo` typ,
`Product.photos`/`Store.photos` přes `@BatchMapping`, `updatePhoto`/`deletePhoto`), protože
multipart upload (`graphql-multipart-request-spec`) Spring for GraphQL nepodporuje. Binárky se
čtou přes `GET /api/media/{id}`/`{id}/thumb`, veřejně, s dlouhým `Cache-Control` (obsah pod
daným id se nikdy nemění, jen jeho existence).

**`core.media.photo_kind` (`2026-08-29/01-media-photo-kind.yaml`) je nezávislá osa od
`record_type`** — ten říká čí je fotka (`PRODUCT`/`STORE`), `photo_kind` co na ní je
(`ITEM`/`LABEL`/`OTHER`, default `OTHER`). Přidáno rovnou při zavedení slotů na fotky ve
formuláři nového zboží, ne až dodatečně — `docs/ai.md` u `f_evid` varuje přesně před opačným
postupem: druh musí schéma nést od začátku, jinak se pozdější rozlišení dopisuje migrací navíc.
`ITEM`, ne `PRODUCT` — to už znamená totéž na ose `record_type`, dvojice `record_type='STORE',
photo_kind='PRODUCT'` by byla matoucí. `LABEL` je zamýšlený budoucí vstup pro čtení
složení/textu z etikety (`docs/ai.md`); fotky provozoven a avatar druh nerozlišují, zůstávají na
`OTHER`.

**Formulář nového zboží nabízí dva sloty na fotku (zboží/etiketa), oba nepovinné** — appka
soubor jen podrží v paměti (`File`/`Uri`) a nahraje ho AŽ po úspěšném `createProduct`/
`createProductFromOff`, přes stejný `POST /api/media/PRODUCT/{id}` s parametrem `kind`. Pravidlo
„výhradně na existující záznam" výš tím není porušené — sloty nejsou samostatný upload cíl,
jen odloží existující tok o jeden krok. Selhání uploadu nezruší založené zboží (produkt v tu
chvíli už existuje); appka jen upozorní, fotku jde doplnit později z detailu.

**Skrytí po nahlášení má stejnou sémantiku jako `core.product.hidden_at`/`core.store.hidden_at`**
— vidí ji dál jen autor. Práh je ale jiný a mnohem nižší (`app.moderation.photo-flags-to-hide`,
výchozí 1) — zdůvodnění patří do `docs/reputace.md`.

**Obrázky z Open Food Facts (`off.*`) se do `core.media` nikdy nekopírují** — jsou pod CC-BY-SA
stejně jako zbytek OFF dat, platí pro ně přesně to pravidlo oddělení schémat, které je popsané
na začátku tohohle dokumentu. Případné zobrazení OFF fotky by šlo výhradně odkazem přes
`Product.externalLinks`, ne uložením do `core.media`.

## Profil uživatele a viditelnost

Podrobné odůvodnění (šifrování, žádná spoluúčast na "žádné veřejné negativní hodnocení",
tok změny e-mailu) je v `docs/soukromi.md`, „Profil uživatele a viditelnost" — tady jen
datový tvar. `auth.user_profile` (`2026-08-12/01-user-profile.yaml`) je 1:1 s `app_user`,
ale VE VLASTNÍ tabulce, aby `app_user` zůstal "identita bez osobních údajů" (viz
`docs/soukromi.md`) a smazání profilu bylo jeden `DELETE`/`CASCADE`. Sloupce
`first_name_enc`/`last_name_enc`/`phone_enc`/`contact_email_enc` jsou `BYTEA`, ne `VARCHAR`
— šifrovaná hodnota nemá smysluplnou textovou délku ani se nedá `LIKE`-filtrovat, což je
zamýšlené (appka nikdy nehledá uživatele podle jména).

`auth.user_profile_field_visibility` je matice `(user_id, field, audience)` s kompozitním
primárním klíčem — **existence řádku** znamená "tohle pole vidí tohle publikum", ne boolean
sloupec na pole. Nevýhoda (o řád víc řádků než sloupců) se vyplatí tím, že přidání dalšího
pole do budoucna je jen nová hodnota v `CHECK`, ne migrace schématu přidávající sloupec ke
každému budoucímu poli.

**Avatar jde přes `core.media` s `RecordType.USER`** (rozšíření `chk_media_record_type` ve
stejném changelogu) — `record_id` je `app_user.id`, ne nějaké nové ID. Odkaz zpátky
(`user_profile.avatar_media_id`) je bez FK, stejně jako `media`→`product`/`store` výš —
schémata `auth`/`core` se mezi sebou cizím klíčem nekříží nikde v projektu. Na rozdíl od
zboží/obchodu je avatar nejvýš jeden na uživatele: druhý upload STARÝ smaže (soubor
i řádek), nepřidává frontu jako `PhotoGallery`.

**`RecordType.USER` je jen pro `core.media`, ne pro `core.record_flag`** — GraphQL `enum
RecordType` (pro `flagRecord`) proto `USER` vůbec neobsahuje, jen Java/Kotlin enum. Avatar se
nenahlašuje stejným kanálem jako fotka zboží/obchodu (`docs/reputace.md`, "žádné veřejné
negativní hodnocení uživatelů" — nahlašování cizího avataru by tomu odporovalo).

## Zpětná vazba

`core.feedback` (`2026-08-20/02-feedback.yaml`) je jediný first-party kanál zpětné vazby od
uživatelů appky (`docs/nasazeni.md`, „Než pozvat první lidi" — uzavřená beta neměla kam
posílat hlášení, jen `mailto:` na dosud nezřízenou schránku). Funguje **i anonymně**
(`user_id` nullable) — zrovna nepřihlášený tester narazí na nejcennější hlášení, třeba že se
nedokázal přihlásit vůbec.

**Vědomá odchylka od `core.record_flag`:** tam se `user_id` z API nikdy nevrací
(`docs/soukromi.md`), tady se autor (je-li přihlášený) naopak vrací moderátorovi vždycky —
jinak není komu na hlášení odpovědět. Je to jiná věc s jiným pravidlem, stejně jako dnes
`authorPublicUid` u `FlaggedRecordItem` vs. skrytý `record_flag.user_id`
(`docs/reputace.md`, „Moderace"). Volitelný `contact_email_enc` (`BYTEA`, šifrovaný stejným
AES-256-GCM jako ostatní textová PII profilu, `EmailCipher.encryptValue`) dovolí i anonymnímu
odesílateli nechat kontakt na sebe, aniž by si zakládal účet.

`client_kind`/`app_version`/`platform_info`/`locale`/`country`/`page_ref` se čtou/odvozují na
serveru z hlaviček requestu (`X-Client-Kind`, `X-Client-Version` u mobilu — `FeedbackGraphQlController`
stejným způsobem jako `ObservationGraphQlController.resolveSource`), ne z toho, co klient
tvrdí v inputu — jen `appVersion` je klientem dodaná doplňková hodnota. `diagnostics`
(volitelný stacktrace posledního pádu appky, jen Android) se přenáší v `FeedbackInput`, appka
ho tam ale vloží JEN po výslovné akci uživatele (`ui/feedback/FeedbackScreen.kt`, checkbox
s výchozí hodnotou nezaškrtnuto) — nikdy automaticky, viz `docs/soukromi.md`.

Fronta pro moderátora (`feedbackItems`/`setFeedbackHandled`) žije v `ModerationGraphQlController`
vedle existující fronty nahlášení, ne jako samostatný gatovaný kontroler — logika je ve vlastní
`FeedbackService`, jen autorizace (`requireModerator`) se sdílí. Jen web (`/moderation`, záložka
„Zpětná vazba"), stejně jako zbytek moderace není appce, je nástroj provozovatele.

### Obrana proti spamu (`2026-09-01/01-feedback-spam.yaml`)

Před veřejnou betou (`docs/nasazeni.md`, „Zbývá") přibyly čtyři sloupce, které `FeedbackSpamDetector`
používá k odložení podezřelé zprávy stranou, aniž by ji zahodil nebo poslal moderátorovi do
běžné fronty — vzor `hidden_at`/`resolved_at` u `core.record_flag`:

- `spam_score`/`spam_reasons` — součet skóre za jednotlivé signály (honeypot, chybějící/
  neplatná proof-of-work výzva, duplicitní zpráva, moc odkazů) a čárkou oddělené kódy PROČ,
  ať moderátor v záložce „Podezřelé" vidí důvod, ne jen číslo.
- `quarantined_at` — NULL = normální fronta, vyplněné = karanténa. `setFeedbackQuarantined` je
  cesta zpět pro falešný poplach, stejný princip jako `resolveFlags DISMISSED`.
- `message_hash` — SHA-256 NORMALIZOVANÉ zprávy (trim + lowercase) pro dedup opakovaného spamu
  za posledních 24 h. **Nikdy IP** — ta zůstává jen v paměti `FeedbackRateLimiter`
  (`docs/soukromi.md`, „Zpětná vazba").

Proof-of-work výzvu (`FeedbackChallengeService`, GraphQL `feedbackChallenge`) appka nepersistuje
vůbec — token nese celou výzvu podepsanou HMAC odvozeným z `JWT_SECRET`, jen krátkodobá
in-memory cache brání přehrání jednou vyřešeného saltu.

## Co ještě není implementováno

### Open Food Facts — zpětné publikování oprav

Samotný EAN lookup, cache OFF snapshotu a použití OFF hodnot jako výchozích při založení
produktu jsou HOTOVÉ (`docs/stav-implementace.md`, „Open Food Facts") — tahle sekce popisovala
ranou fázi vývoje, dnes by čtenáře matla. Jediné, co z původního záměru zbývá: appka umí
komunitní opravu jen uložit lokálně (`core.product_user_edit`), ne ji poslat zpátky do OFF.
Odeslání zpět do OFF je mimo dnešní rozsah.

`agg.price_weekly_national` (týdenní řady pro delší grafy), skupiny důvěry (`core.trust_group`,
`core.trust_edge`), `core.user_flag`, `core.watch_subscription`, lokální dodavatelé
(`core.supplier`, `core.supplier_offer`) a `core.access_policy` — to všechno patří do dalšího
rozvoje (`docs/README.md`, „Terminologie fází"). Tabulky pro ně ještě nejsou v changelogu, aby
se neležel mrtvý kód/schéma, které nikdo nepoužívá. Recenze (`core.product_review`) mají dnes
jen binární viditelnost textu (přihlášený/anonym, T1 v `docs/reputace.md`) — jemnější
`PUBLIC`/`GROUPS`/`PRIVATE` z původního plánu by dávalo smysl až se skupinami důvěry, zatím by
neměl co rozlišovat.

Nápady mimo tenhle plán (název věrnostního programu podle obchodu, ceny předem z akčního
letáku, načtení celé účtenky, nákup podle receptu nebo seznamu) jsou v `docs/rozvoj.md`.

**Konsolidační job** (vyhodnocuje `processed_at IS NULL` frontu z "Uživatelská vrstva nad
globálními daty" výš a rozhoduje, kdy patch/nový záznam povýšit na `verified_at`) taky ještě
není napsaný — vyhodnocovací pravidlo zatím není známé. Existuje jen to, co bude potřebovat
(sloupce, fronta), aby šel dopsat bez další migrace. Dokud neběží, je `verified_at` u
všeho `NULL` a klient všude ukazuje "neověřeno" — to je záměr, ne dočasná chyba k opravě.

## GraphQL kontrakt: breaking change bez verzování (historie) a dnešní pravidlo

Dva breaking changy proběhly ještě před nasazením, kdy „nic není nasazené a mobil i web se
aktualizují společně s backendem" byla platná úvaha — dnes už není, appka je v provozu od
2026-08-24 (`docs/vydani.md`) a starší mobilní APK můžou být v terénu. Ponecháno jako historický
záznam PROČ jsou typy takové, jaké jsou, ne jako platný návod pro budoucí změny:

- `searchProducts` změnilo návratový typ z `[Product!]!` na `ProductSearchResult!` (přidání
  filtrů obchod/město, řazení a agregátů v `ProductSearchItem`).
- `Store.lat`/`Store.lon` změnily typ z `Float!` na `Float` (nullable) kvůli obchodům založeným
  bez GPS — viz „Identita provozovny" výš.

**Dnešní pravidlo, už implementované:** budoucí breaking change (změna typu existujícího pole,
non-null → nullable a naopak) se nedělá tiše — appka místo toho zvedne
`app.client.min-android-version` (`application.yml`) na `versionCode` prvního APK, které change
obsahuje. `ClientVersionFilter` pak starším klientům vrátí srozumitelnou chybu „aktualizuj
appku" místo tichého pádu dotazu nebo nevysvětleného selhání na `null` (`docs/vydani.md`,
„Verzování a vydání"). Přesně tenhle mechanismus se použil, když se `rateProduct(grade:)`
přejmenovalo na `rateProduct(stars:)` a otočila se škála. Nekompatibilní pole vedle sebe (nebo
verzované schéma) zůstává řešením pro změny, které i takhle zablokovaný starý klient nemá
snést — rozhodnout se má předem, ne až ve chvíli, kdy se to poprvé stane.
