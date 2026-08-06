# Datový model

Zdroj pravdy pro schéma je Liquibase: `backend/src/main/resources/db/changelog/`. Tento
dokument vysvětluje ROZHODNUTÍ za schématem — proč jsou tabulky rozdělené tak, jak jsou —
ne úplný výpis sloupců (ten je v changelogu, tam by z něj snadno rozjel nesoulad).

## Oddělení schémat kvůli ODbL

```
core   vlastní komunitní data (katalog, obchody, ceny, recenze, ...)
auth   účty, přihlašovací výzvy, tokeny
agg    předpočítané agregáty pro grafy
off    data z Open Food Facts — NIKDY se nekopírují do core.*
osm    souřadnice provozoven z OpenStreetMap — NIKDY se nekopírují do core.*
```

Open Food Facts **i OpenStreetMap** jsou pod licencí ODbL se share-alike podmínkou — kdyby
se jejich data smíchala do vlastních tabulek, share-alike by se vztáhl na celou databázi.
Na OSM se přitom snadno zapomíná, protože souřadnice nevypadají jako "databáze" — jsou to
jen dvě čísla u obchodu.

Pravidla, která to drží čisté:

1. Žádná hodnota z `off.*`/`osm.*` se **nikdy nekopíruje** do `core.*`. Spojení vzniká až
   při čtení v service vrstvě; UI vždy uvede zdroj a licenci.
2. Pokud uživatel ručně opíše údaj z OFF do `core.product`, nastaví se
   `data_origin = 'OFF_DERIVED'` a tenhle produkt se vyloučí z "čistého" exportu.
3. Čistý export vlastních dat: `pg_dump --schema=core --schema=agg`. **Pozor:**
   `core.product_quality_rating.user_id` (hodnocení kvality, viz níže) se do „čistého"
   exportu nesmí dostat beze změny — je to jediné místo v `core.*`, kde vazba na uživatele
   nepodléhá pseudonymizaci po 180 dnech (na rozdíl od `price_observation.submitter_id`,
   viz `soukromi.md`). Export musí sloupec vynechat nebo hashovat, jinak GDPR záruka
   „starší příspěvky už o mně nikdo nedohledá" pro `pg_dump` ticho neplatí.
4. Aplikační DB uživatel má na `off`/`osm` jen `SELECT` mimo dedikovaný synchronizační job —
   technická pojistka, ne jen dohoda v hlavě.

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
vidět, ne zprůměrované pryč.

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

Unikátní index „1 záznam / uživatel / produkt / obchod / den"
(`uq_price_observation_submitter_per_day`) potřeboval `date_trunc('day', observed_at)` ve
výrazu indexu. `date_trunc(text, timestamptz)` je ale jen `STABLE` (výsledek závisí na
timezone session), ne `IMMUTABLE`, takže ho Postgres do indexu odmítne
(`functions in index expression must be marked IMMUTABLE`). Řešení: vlastní immutable
wrapper `core.day_utc(TIMESTAMPTZ) RETURNS DATE` s pevným `AT TIME ZONE 'UTC'`
(`2026-07-26/05-price-observation.yaml`) — obecný vzor, kdykoliv je potřeba datum/čas ve
výrazu indexu.

## Agregace jsou tabulky, ne materialized view

`agg.price_current` (PK `product_id, store_id, price_kind`) a `agg.price_daily` (PK navíc
`day`, doplněno pro graf vývoje ceny — viz `2026-08-05/02-agg-price-daily.yaml`) se plní přes
`agg.recompute_queue`, ne `REFRESH MATERIALIZED VIEW`.

Důvod: váhy záznamů se mění **zpětně** — klesne někomu reputace, odhalí se sybil klastr —
takže je potřeba cílený přepočet konkrétních buněk (`product_id, store_id`), ne přepočet
celé view. Při milionech observací by `REFRESH MATERIALIZED VIEW` bylo o řády dražší než
fronta + upsert nad změněnými buňkami. `agg.recompute_queue` zatím nezná `day` — přepočet
denní řady tedy vždy smaže a znovu spočítá celou historii buňky (`product_id, store_id`),
ne jen změněný den. Pro objemy etapy 1 v pořádku; až přepočet začne měřitelně trvat, signál
je přidat nullable `day` do fronty (`NULL` = přepočítat vše), ne to řešit preventivně teď.

Čtení grafu je vždy z `agg.*`, nikdy ze syrových `core.price_observation` — index-only
scan, jednotky milisekund i při statisících záznamů.

**Den v `agg.price_daily.day` = `core.day_utc(observed_at)`**, tatáž funkce jako u
`uq_price_observation_submitter_per_day` (viz níže) — záměrně, aby „den" znamenal v celé
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

Do etapy 1 vstupovaly obchody do appky jen dvěma cestami: `nearbyStores` (nutná poloha) nebo
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
Používá se v `uq_store_identity` (název + město + ulice, tvrdá pojistka proti duplicitám —
klient si musí před uložením ověřit `searchStores`, tohle je až poslední obrana), v
`uq_product_generic_name` (druhové položky, `docs/reputace.md`) a v `pg_trgm` indexech pro
podobnostní hledání. Past stejného druhu jako `core.day_utc` u `date_trunc`
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

## Hodnocení kvality — jen známka, ne recenze

`core.product_quality_rating` (`2026-08-05/01-product-quality-rating.yaml`) je vědomě
minimální předstupeň k `core.product_review` z etapy 2: jen `grade SMALLINT CHECK (1–5)`
na dvojici `(product_id, user_id)`, žádný text, žádná viditelnost `PUBLIC`/`GROUPS`/`PRIVATE`,
žádný `ViewerContext`. Jedna známka na uživatele a produkt vynucuje `UNIQUE (product_id,
user_id)` a backendový upsert (`ON CONFLICT ... DO UPDATE`), ne aplikační logika.

Cena za tuhle jednoduchost: vazba `user_id` **se nepseudonymizuje** po 180 dnech jako
`price_observation.submitter_id` — bez trvalé vazby by nešlo vynutit „jedna známka na
uživatele". Je to vědomé zhoršení proti běžnému pravidlu projektu, podrobně v
`soukromi.md` (`ON DELETE CASCADE` při smazání účtu, `user_id` nikdy ven přes API, pozor
na `pg_dump` export výše).

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
| `verified_at` | Job uznal záznam za globální/ověřený. `NULL` ⇒ klient zobrazí štítek "neověřeno" — v etapě 1 tedy úplně všechno kromě seedu, to je očekávaný stav, ne chyba. |
| `processed_at` | Job se na patch/záznam podíval. Zpracováno ≠ uznáno za globální — odlišné od `verified_at`. Každá další úprava patche `processed_at` vynuluje (`CatalogEditService`), protože dřívější zpracování se týkalo starého obsahu. |
| `hidden_at` | Skryto po nahlášení (`core.record_flag`, viz `reputace.md`), čeká na přezkum. Vidí ho jen autor. |

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

**Viditelnost pod prahem důvěry** (práh je popsaný v `reputace.md`) řeší stejný predikát
všude, kde se vrací produkt/obchod: `status = 'ACTIVE' OR created_by_user_id = viewerId`.
Fulltextové hledání (`ProductSearchRepositoryImpl`) má tuhle podmínku navíc DVOUVĚTVOU kvůli
indexu — `to_tsvector('simple', COALESCE(e.name, p.name))` by obešel `idx_product_name_fts`
a vynutil seq scan, takže se hledá zvlášť přes `p.name` (index) a zvlášť přes patch `e.name`
(malý LEFT JOIN jen pro jednoho viewera). Skryté (`hidden_at`) záznamy z hledání zmizí úplně,
i autorovi — přímý dotaz `product(id)`/`productByCode` je autorovi pořád ukáže, s příznakem.

## Co ještě není v etapě 1

`agg.price_weekly_national` (týdenní řady pro delší grafy), plné textové recenze
(`core.product_review`, viditelnost `PUBLIC`/`GROUPS`/`PRIVATE`), skupiny důvěry
(`core.trust_group`, `core.trust_edge`), `core.user_flag`, `core.watch_subscription`,
lokální dodavatelé (`core.supplier`, `core.supplier_offer`) a `core.access_policy` —
to všechno je popsané v plánu založení projektu a přijde v etapě 2/3. Tabulky pro ně
ještě nejsou v changelogu, aby se neležel mrtvý kód/schéma, které nikdo nepoužívá.

**Konsolidační job** (vyhodnocuje `processed_at IS NULL` frontu z "Uživatelská vrstva nad
globálními daty" výš a rozhoduje, kdy patch/nový záznam povýšit na `verified_at`) taky ještě
není napsaný — vyhodnocovací pravidlo zatím není známé. Existuje jen to, co bude potřebovat
(sloupce, fronta), aby šel dopsat bez další migrace. Dokud neběží, je `verified_at` u
všeho `NULL` a klient všude ukazuje "neověřeno" — to je záměr, ne dočasná chyba k opravě.

## GraphQL kontrakt: breaking change bez verzování

`searchProducts` změnilo návratový typ z `[Product!]!` na `ProductSearchResult!` (přidání
filtrů obchod/město, řazení a agregátů v `ProductSearchItem`) — v etapě 1 bez dopadu, protože
nic není nasazené a mobil i web se aktualizují společně s backendem. Až bude appka v provozu,
takhle přímá změna typu existujícího pole by starším klientům (starší mobilní APK v terénu)
shodila dotaz. Další podobně tvarová změna už bude potřebovat buď nové pole vedle starého,
nebo verzované schéma — rozhodnout se má předem, ne až ve chvíli, kdy se to poprvé stane.

Druhý případ: `Store.lat`/`Store.lon` změnily typ z `Float!` na `Float` (nullable) kvůli
obchodům založeným bez GPS — viz „Identita provozovny" výš. Stejná poznámka platí i tady:
v provozu by tahle změna klientům, kteří nepočítají s `null`, mohla shodit zobrazení karty
obchodu (ne dotaz samotný, GraphQL by `null` u dřív non-null pole prostě propustil, ale
klientský kód typovaný na `Float!` by na runtime `null` mohl spadnout jinde).
