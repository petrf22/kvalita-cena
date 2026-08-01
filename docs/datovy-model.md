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
3. Čistý export vlastních dat: `pg_dump --schema=core --schema=agg`.
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

`agg.price_current` (PK `product_id, store_id, price_kind`) a plánované `agg.price_daily`
(etapa 2) se plní přes `agg.recompute_queue`, ne `REFRESH MATERIALIZED VIEW`.

Důvod: váhy záznamů se mění **zpětně** — klesne někomu reputace, odhalí se sybil klastr —
takže je potřeba cílený přepočet konkrétních buněk (`product_id, store_id, day`), ne
přepočet celé view. Při milionech observací by `REFRESH MATERIALIZED VIEW` bylo o řády
dražší než fronta + upsert nad změněnými buňkami.

Čtení grafu je vždy z `agg.*`, nikdy ze syrových `core.price_observation` — index-only
scan, jednotky milisekund i při statisících záznamů.

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

## Co ještě není v etapě 1

`agg.price_daily` a `agg.price_weekly_national` (denní/týdenní řady pro delší grafy),
recenze (`core.product_review`, viditelnost `PUBLIC`/`GROUPS`/`PRIVATE`), skupiny důvěry
(`core.trust_group`, `core.trust_edge`), `core.user_flag`, `core.watch_subscription`,
lokální dodavatelé (`core.supplier`, `core.supplier_offer`) a `core.access_policy` —
to všechno je popsané v plánu založení projektu a přijde v etapě 2/3. Tabulky pro ně
ještě nejsou v changelogu, aby se neležel mrtvý kód/schéma, které nikdo nepoužívá.
