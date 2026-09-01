# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Komunikuj s uživatelem česky.

## Přehled projektu

Komunitní aplikace pro sledování cen běžného zboží v obchodech. Uživatelé skenují mobilem čárový
kód, zapisují cenu a obchod; web ukazuje aktuální ceny, vývoj v čase a průměry napříč obchody.
Vedle řetězců se zobrazují i lokální dodavatelé (kvalita a lokálnost, ne jen cena).

Dvě zadání, která jdou proti samozřejmým řešením a určují celou architekturu:

1. **Uživatelé se nesledují** — přesto reputační systém potřebuje vazbu příspěvek → uživatel.
   Řeší se v datovém modelu (pseudonymizace po 180 dnech, žádné syrové GPS), ne v UI.
   Detaily: `docs/soukromi.md`.
2. **Komunita má být pozitivní** — proto žádné veřejné negativní hodnocení uživatelů, i když by
   bylo technicky nejjednodušší. Detaily: `docs/reputace.md`.

Odůvodnění klíčových rozhodnutí a přehled dokumentace: [`docs/README.md`](docs/README.md) —
rozcestník s tabulkou účel/charakter/zdroj pravdy pro každý dokument a jedna terminologie fází
napříč projektem (nahrazuje dřívější odkaz na samostatný plánovací soubor mimo repo, který
přestal existovat).

## Monorepo — tři samostatné aplikace

`backend/` (Spring Boot), `frontend/` (Angular), `mobile/` (Kotlin/Compose), `docs/` (datový
model, reputace, soukromí, AI, vydání — jeden zdroj pravdy pro vzorce a prahy). Konvence a
příkazy specifické pro jednu aplikaci jsou v jejím `CLAUDE.md` (`backend/CLAUDE.md`,
`frontend/CLAUDE.md`, `mobile/CLAUDE.md`) — načtou se jen při práci v daném adresáři.

Sdílený mezi aplikacemi je kontrakt API (GraphQL schéma
`backend/src/main/resources/graphql/schema.graphqls`). Frontend z něj přes `graphql-codegen`
(`frontend/codegen.ts`) generuje TypeScript typy a konstanty enumů do
`frontend/src/app/models/generated/` — čte schéma přímo z backendu, žádná kopie. Mobil zatím
typy z GraphQL schématu negeneruje (`network/Dto.kt` mapuje enumy ručně na `String`). Pozor na
pořadí: `graphql(...)` volání ve frontendu matchují dotaz na přesný string zachycený při
generování, takže Prettier musí proběhnout **před** posledním `npm run codegen`, jinak typová
kontrola i běhový match spadnou.

Druhá sdílená věc je **verze** — server, web i mobil mají jedno společné číslo. Zdroj pravdy
jsou kořenové `VERSION` a `CHANGELOG.md`; `node tools/version/sync.mjs` z nich generuje
`backend/build.gradle`, `frontend/package.json`, `mobile/app/build.gradle.kts` a seznamy změn
pro web (`/changelog`) i mobil („O aplikaci" → Novinky) — needituj tyhle výstupy ručně, uprav
zdroj a spusť skript znovu. Postup vydání (tag, z jaké větve/verze stavět server i mobil,
hotfix už vydané verze) je v [`docs/vydani.md`](docs/vydani.md), „Verzování a vydání".

## Stav implementace

Co je hotové a v jakém souboru to žije, včetně co (zatím) NE:
[`docs/stav-implementace.md`](docs/stav-implementace.md) — přehledová matice na začátku,
sekce „Neimplementováno" na konci. Rozvojové nápady mimo MVP (nezávazné, k realizaci až
přijde řada, se stavem NÁPAD/ROZHODNOUT/PLÁNOVÁNO/ČÁSTEČNĚ) jsou v `docs/rozvoj.md`.

**Pasti, které z kódu nejsou vidět:**
- Sken/zadání EANu, který v katalogu není, ale zná ho Open Food Facts, se ukládá VÝHRADNĚ přes
  `createProductFromOff`, nikdy přes `createProduct` — jinak by OFF hodnoty skončily zkopírované
  do `core.product`, což ODbL share-alike zakazuje (`docs/datovy-model.md`, „Oddělení schémat
  kvůli ODbL"). `OffProductCatalogService.create()` je nechává v `core.product` `NULL`; spojení
  vzniká až čtením v `ProductOverlayService`.
- `ExternalProductCandidate.netContentValue` chodí v jednotce z OFF (typicky `G`/`ML`,
  `OffNetContentConverter`), formulář vždy v kg/l — klient MUSÍ gramáž pro zobrazení převést a
  při submitu poslat `netContentValue`/`netContentUom` vždy jako dvojici (buď obojí `null`, ať
  hodnotu dál dodává OFF, nebo obojí z formuláře). Poslání převedené hodnoty s jinou jednotkou,
  než jakou má uložený OFF snapshot, by `CatalogEditService.updateProduct` spočítalo jako úplně
  jiné číslo (250 g vs. 0,25 kg → 250× větší patch) — viz `netContentForOffSubmit`
  v `product-form-validation.ts` / `ProductFormViewModel.kt`. Stejné pravidlo platí i pro inline
  editaci existujícího zboží (`updateProduct`) — tam dvojice musí dorazit i tehdy, když se
  změnila jen základní jednotka nebo přepínač váhového zboží, ne jen samotné číslo
  (`netContentForUpdateSubmit`/`buildUpdateProductInput`).
- Klientský překlad chyb podle `code` na mobilu chybí — appka ukáže `serverMessage`, protože
  `network/Dto.kt` negeneruje typy ze schématu jako web (`docs/lokalizace.md`, „Co zbývá").
- Geometrie ikon (favicon, PWA manifest, Android launcher) žije v `tools/icons/generate.py`,
  zdroj pravdy `docs/branding.md` — po každé úpravě kresby spustit `python3
  tools/icons/generate.py`.

## Příkazy

### Lokální prostředí

```bash
docker compose up -d                              # PostgreSQL 17 na 127.0.0.1:5437
docker compose exec postgres psql -U postgres -d kvalitaacena   # psql není nainstalované lokálně
```

Příkazy a konvence pro jednotlivé aplikace: [`backend/CLAUDE.md`](backend/CLAUDE.md),
[`frontend/CLAUDE.md`](frontend/CLAUDE.md), [`mobile/CLAUDE.md`](mobile/CLAUDE.md).

## Architektura, která se později mění nejhůř

Podrobný datový model je v `docs/datovy-model.md`; tady jen to, co je nutné znát před jakoukoli
změnou v daných oblastech.

### Oddělení schémat kvůli ODbL

PostgreSQL schémata: **`core`** (vlastní data), **`auth`**, **`agg`** (agregáty pro grafy),
**`off`** (Open Food Facts), **`osm`** (souřadnice provozoven z OpenStreetMap — schéma zatím
nemá jedinou tabulku, je to rezervace pro budoucí synchronizaci), **`fx`** (kurzovní lístek ČNB,
`docs/lokalizace.md` — na rozdíl od `off`/`osm` sem appka sama píše, hotovo).

Open Food Facts **i OpenStreetMap** jsou pod ODbL se share-alike podmínkou. Oddělení schémat je
**projektová bezpečnostní politika zvolená vědomě přísněji, než ODbL vyžaduje** (ta rozlišuje
odvozenou a kolektivní databázi; podrobné odůvodnění a odkazy na ODbL 1.0/OSMF guideline jsou
v `docs/datovy-model.md`, „Oddělení schémat kvůli ODbL") — žádný hromadný ani podstatný výřez
`off.*`/`osm.*` se nekopíruje do `core.*`; jednotlivě zvolený geokódovaný výsledek (lat/lon +
`osm_ref`) se do `core.store` uložit smí, s `geo_source` jako značkou původu. UI vždy uvede
zdroj. Čistý export vlastních dat je `pg_dump --schema=core --schema=agg`. Aplikační DB
uživatel má na `off`/`osm` jen `SELECT`, zapisuje jen synchronizační job.

### `core.price_observation` je jádro aplikace

- **`net_content_base` (gramáž/objem) se snapshotuje na observaci**, ne odkazuje na aktuální
  hodnotu z produktu — jinak by pozdější oprava gramáže přepsala jednotkové ceny celé historie.
- **`observed_at` (kdy to uživatel viděl) ≠ `created_at`** (kdy to došlo na server) — kvůli
  offline zápisu z mobilu.
- **`price_kind` (REGULAR/PROMO/CLUB_CARD/CLEARANCE/MULTIBUY) je součástí klíče agregátu**
  I unikátního indexu observace (`uq_price_observation_submitter_kind_per_day`) — akční,
  klubová a běžná cena se nikdy nemíchají do jedné řady, a zároveň jde stejný den zapsat víc
  cen různého druhu z jedné cenovky (`submitObservations`, jedna transakce, kolize jediného
  druhu shodí celou dávku).
- **Vnitroobchodní kódy váhového zboží nejsou globální identifikátor** — mají povinný `chain_id`
  (`core.product_code.code_type = STORE_INTERNAL`). EAN se normalizuje na GTIN-14.

### Agregace jsou tabulky (`agg.price_current`, `agg.price_daily`), ne materialized view

Váhy záznamů se mění zpětně (klesne něčí reputace, odhalí se sybil klastr), takže je potřeba
cílený přepočet konkrétních buněk přes `agg.recompute_queue` — `REFRESH MATERIALIZED VIEW` by
při milionech observací přepisovalo zbytečně celou view. Graf se čte vždy z `agg.price_daily`,
nikdy ze syrových observací. Národní cena je **medián mediánů** (nejdřív uvnitř provozovny, pak
přes provozovny).

### Recenze (další rozvoj, zatím neimplementováno): autorizace je predikát v dotazu, ne filtr v resolveru

Recenze, skupiny důvěry a `ViewerContext` dnes vůbec neexistují (žádné entity, žádné
tabulky) — až budou, platí tohle:

Viditelnost `PUBLIC`/`GROUPS`/`PRIVATE` se vynucuje výhradně v `ReviewQueryService`
(JPA `Specification` z `ViewerContext`), s Hibernate `@Filter` jako pojistkou pro zapomenuté cesty
(nativní dotazy, DataLoader). GraphQL `DataLoader` musí mít **viewera v cache klíči**
(`(productId, viewerId)`) — jinak se cache prolije mezi uživateli. Neviditelná recenze vrací
`NOT_FOUND`, ne `FORBIDDEN`.

### Lokalizace: `docs/lokalizace.md` je jeden zdroj pravdy

Jazyky, mapa země→měna→locale, kontrakt chyb (`extensions.code`/`params`), pravidla pro `{0}`/
`{{param}}` (jen datová hodnota, nikdy přeložený kus věty) a přehled i18n testů/CI guardů patří
tam, ne rozeseté po kódu jako `docs/reputace.md` pro prahy. Klíčové, co je nutné znát před
jakoukoli změnou v katalogu/cenách:

- **Měna je součástí primárního klíče `agg.price_current`/`agg.price_daily`.** Nový sloupec bez
  úpravy PK/indexů by vážený medián tiše mísil napříč CZK/EUR/PLN — bez chyby při zápisu, jen
  špatné číslo v grafu. Index má `currency` **před** `unit_price`.
- **Přepočet do zobrazovací měny je jen čtecí vrstva, nikdy se neukládá do `agg.*`.** Kurz musí
  být vždy platný K DATU CENY, nikdy dnešní — jinak by graf vývoje ceny v cizí měně mísil pohyb
  ceny s pohybem kurzu. USD je jen zobrazovací, nejde v ní zapsat cenu (`app.fx.display-
  currencies` ≠ `app.i18n.country-currency`).
- **Volba jazyka je na klientovi.** `auth.app_user.locale`/`country` slouží výhradně
  asynchronnímu výstupu (OTP e-mail) — appka z nich nikdy nerozhoduje, co klient uvidí, jen se
  tam volba klienta uloží.
- **`values/` na Androidu je čeština** (zdroj i fallback), ne angličtina — vědomé rozhodnutí,
  appka vznikla pro český trh.

### Reputace: vzorce jsou jen v `docs/reputace.md`

**Stav v MVP**: implementovaná je jen složka `L` v `PriceAggregationService.weightFor()`
(anonym 0,15 / registrovaný 1,00) a vážený medián samotný. Plný vzorec `S` (přesnost ×
zkušenost × stáří účtu × úroveň identity × penalizace), `f_conf`/`f_evid`/`f_recency`/`f_group`,
`ReputationService` a `core.access_policy` jsou cílový stav pro další rozvoj — až se budou psát,
prahy patří do `docs/reputace.md`, ne rozeseté po kódu jako vlastní konstanty na více místech.
Klíčové už teď: souhlas (až bude implementovaný) se musí počítat **leave-one-out** (medián bez
vlastního záznamu uživatele), jinak si osamělý přispěvatel vždy "potvrdí sám sebe".

## Konvence

- `group = 'cz.kvalitacena'`/`applicationId`, package `cz.kvalitacena.*` napříč backendem i
  mobilem
- **Komentáře, commit zprávy a dokumentace česky**, identifikátory v kódu anglicky
- Pouze svobodné licence knihoven (MIT/Apache-2.0/BSD/EPL) — žádné knihovny s rizikem budoucí
  placené licence (proto např. ZXing místo ML Kit pro skenování, `cube`/`earthdistance` místo
  PostGIS)
- Konvence a odsazení specifické pro jednu aplikaci jsou v jejím `CLAUDE.md` (viz „Monorepo"
  výš)
