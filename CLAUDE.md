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
- **Gramáž/objem se do serveru posílá VŽDY jako dvojice `netContentValue`/`netContentUom`** —
  nikdy jen jedna z nich. Jednotku si od 2026-09 vybírá uživatel v comboboxu před číslem
  (`NET_CONTENT_UOM_CHOICES`: g/kg/ml/l), takže samotné číslo nic neznamená: 250 spárovaných
  s `KG` místo `G` je 1000× jiná hmotnost. `CatalogEditService.updateProduct` chybějící půlku
  doplní ze starého uloženého snapshotu, takže rozpojená dvojice tiše spočítá úplně jiné
  `net_content_base`. U `createProductFromOff` platí navíc, že se posílá jen skutečně změněná
  dvojice (jinak obojí `null`, ať hodnotu dál dodává OFF — `netContentForOffSubmit`
  v `product-form-validation.ts` / `ProductFormViewModel.kt`); u `updateProduct` musí dvojice
  dorazit i tehdy, když se změnila jen jednotka gramáže nebo přepínač váhového zboží, ne jen
  samotné číslo (`netContentForUpdateSubmit`/`buildUpdateProductInput`).
  `ExternalProductCandidate.netContentValue` chodí v jednotce z OFF (typicky `G`/`ML`,
  `OffNetContentConverter`) a formulář ji přebírá, jak je — nic se nepřepočítává. Do kg/l/ks
  převádí výhradně server (`NetContentCalculator` → `net_content_base`), klient nikdy neposílá
  přepočtenou hodnotu.
- **`unitBase` se od 2026-09 ODVOZUJE z vybrané jednotky, formulář se na něj neptá**
  (`unitBaseForUom`: g/kg→`MASS`, ml/l→`VOLUME`, nevybráno→`COUNT`) a do serveru chodí výhradně
  z `visibleNetContent` — jediného místa, kde vzniká, spolu s očištěnou gramáží a příznakem
  váhového zboží. Dřívější volba „Kus / Hmotnost / Objem" zmizela, protože `COUNT` znamenal
  přesně totéž co nevyplněná gramáž (`net_content_base = 1`, cena za balení — kusovou hodnotu
  formulář nikdy poslat neuměl) a nutil odpovídat na otázku, na kterou u rohlíku odpověď není.
  **Váhové zboží gramáž ignoruje úplně** (`NetContentCalculator` vrací 1 ještě před kontrolou
  jednotky a `PriceObservationService` bere základ z `QuantityBasis` u zápisu ceny), takže
  formulář po zapnutí přepínače celý blok skryje; `visibleNetContent` proto váhové zboží řeší
  jako PRVNÍ větev a `storedUnitBase` mu drží `VOLUME` u zboží, které ho už má.
- **Pole „Název" ve formuláři je VŽDY v jazyce appky** a cizojazyčný název z OFF se do něj
  nikdy nepředvyplňuje (`offCandidateDefaults`/`offNamesFrom` berou jen `names[lang]`) — jinak
  by se němčina uložila jako český název, což je přesně ta chyba, kvůli které vícejazyčnost
  vznikla. Cizojazyčná varianta se ukáže v upozornění a v sekci ostatních jazyků (ta se
  ZÁMĚRNĚ nerozbaluje sama a nabízí nejdřív jen jazyk zvolené země, `CountryInfo.defaultLocale`);
  do serveru se z ní posílá jen to, co uživatel změnil (`changedNames`), protože poslat zpátky
  nezměněnou hodnotu z OFF by znamenalo zapsat cizí data do `core.product_name` (ODbL). Pořadí
  vrstev a fallback napříč jazyky: `docs/lokalizace.md`, „Název zboží po jazycích".
- **Prošlý access token není chyba, kterou by šlo počkat** — `JwtAuthenticationFilter` ho mlčky
  zahodí a request doběhne jako ANONYMNÍ (HTTP 200, žádné `errors`), takže dotaz s anonymní
  variantou (`me` vrátí `null`, hledání zamlčí vlastní DRAFTy, graf zkrátí okno) vypadá jako
  normální odpověď a reaktivní obnova na `classification: UNAUTHORIZED` se na něm NIKDY nechytí.
  Token se proto hlídá podle expirace (`TokenResponse.expiresInSec`) a pro request se bere
  VÝHRADNĚ přes `AuthRepository.validAccessToken` (mobil) / `AuthService.validAccessToken`
  (web) — nikdy ne z uloženého pole. Obnova smí běžet jen jedna naráz (refresh token rotuje,
  souběžné použití mimo 30s grace okno revokuje celou rodinu tokenů) a session ruší jen HTTP
  401, ne výpadek sítě. Podrobně `docs/soukromi.md`, „Passwordless auth".
- **Stav přihlášení na mobilu je `AuthRepository.isLoggedIn`, ne přítomnost access tokenu** —
  ten po startu procesu chybí, dokud nedoběhne obnova, takže by appka přihlášenému ukázala
  přihlašovací formulář. Zdroj pravdy je uložený refresh token.
- Klientský překlad chyb podle `code` na mobilu chybí — appka ukáže `serverMessage`, protože
  `network/Dto.kt` negeneruje typy ze schématu jako web (`docs/lokalizace.md`, „Co zbývá").
- **Stav appky v Android emulátoru se čte textově, ne ze screenshotu** — `python3
  tools/mobile/ui.py dump` vypíše prvky obrazovky (~330 B proti 16 kB syrového `uiautomator
  dump` a proti ceně obrázku), `tap`/`text`/`wait`/`open` ji ovládají. Screenshot patří jen
  tam, kde si o něj uživatel výslovně řekne. Podrobně `mobile/CLAUDE.md`, „Ladění
  v emulátoru"; skok na obrazovku obstarává `DebugRouteIntent` v `MainActivity.kt`, čtený
  jen v debug buildu.
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

### Recenze: hvězdičky a text jsou jeden záznam, autor je první veřejně viditelný v API

`core.product_review` (přejmenováno z `product_quality_rating`, `docs/datovy-model.md`) nese
hvězdičky povinně a text recenze volitelně (max 1000 znaků) — jeden řádek, ne dvě entity. Text
vidí jen přihlášený (`docs/reputace.md`, T1) — `ProductReviewService.reviewsFor` to řeší
ořezáním v service (anonym dostane `loginRequired: true` a prázdné `items`, ale reálný
`totalCount`), ne filtrem v resolveru, stejný vzor jako `PriceHistoryService` u anonymního
okna grafu.

Recenze je **první veřejný typ v API, kde je autor vidět** (`ProductReview.authorPublicUid`/
`authorName`, vykreslené `PublicNameRenderer`) — na rozdíl od zbytku appky, kde se autor
objevuje jen v moderátorském pohledu (`docs/soukromi.md`, „Podepsaná recenze"). Jemnější
viditelnost `PUBLIC`/`GROUPS`/`PRIVATE` nad `ViewerContext` (`JPA Specification`, Hibernate
`@Filter` jako pojistka pro zapomenuté cesty, `DataLoader` s viewerem v cache klíči
`(productId, viewerId)`) zůstává plán pro další rozvoj — dává smysl až se skupinami důvěry,
zatím by neměla co rozlišovat nad dnešní binární přihlášený/anonym.

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
- **Každá nová úprava = nová větev** (`git checkout -b`), nikdy práce rovnou na `main` — ten má
  na GitHubu ruleset „změny jen přes pull request", takže přímý push stejně skončí na
  `GH013: Repository rule violations`. Commitovat průběžně a tematicky (jedna ucelená změna =
  jeden commit), ne jedním velkým commitem na konci. Výjimka jen po výslovné domluvě.
- Pouze svobodné licence knihoven (MIT/Apache-2.0/BSD/EPL) — žádné knihovny s rizikem budoucí
  placené licence (proto např. ZXing místo ML Kit pro skenování, `cube`/`earthdistance` místo
  PostGIS)
- Konvence a odsazení specifické pro jednu aplikaci jsou v jejím `CLAUDE.md` (viz „Monorepo"
  výš)
