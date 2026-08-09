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

Plán založení a odůvodnění klíčových rozhodnutí: `/home/petr/.claude/plans/clever-puzzling-crescent.md`.

## Monorepo — tři samostatné aplikace

```
backend/    Spring Boot 4, Java 25, Gradle (Groovy DSL) — API pro web i mobil
frontend/   Angular 22 + ng-zorro-antd — webové rozhraní
mobile/     Kotlin + Jetpack Compose — nativní Android
docs/       datový model, reputace, soukromí — jeden zdroj pravdy pro vzorce a prahy
```

Každá část má vlastní build nástroj a vlastní `README`/konvence; sdílený je jen kontrakt API
(GraphQL schéma `backend/src/main/resources/graphql/schema.graphqls`).

## Stav implementace (etapa 1 — prochozí kostra)

Hotovo a ověřeno end-to-end (backend přes curl, web i mobil živě v prohlížeči/emulátoru):
passwordless auth (OTP + refresh rotace), GraphQL `searchProducts` (filtr obchod/město, řazení,
stránkování, agregáty v `ProductSearchItem`) / `searchFacets` / `product` / `productByCode` /
`nearbyStores` / `priceHistory` / `me` / `submitObservation` / `rateProduct`, vážený medián
i denní agregace v `PriceAggregationService` (`agg.price_current` + `agg.price_daily`),
hodnocení kvality jako známka 1–5 (`core.product_quality_rating`, `QualityRatingService` —
jen průměr a počet, žádné texty, žádná viditelnost).

Zakládání katalogu bez skenování/GPS (`docs/datovy-model.md`, „Identita provozovny"):
`searchStores`/`createStore` (`StoreService` — vyžaduje přihlášení, `uq_store_identity` jako
pojistka proti duplicitám, volitelné IČO s kontrolním součtem + `companyByIco` přes ARES,
volitelné souřadnice přes `geocodeAddress`/OpenStreetMap Nominatim vždy ze serveru) a
`productSuggestions`/`categories`/`createProduct` (`ProductCatalogService` — zboží s EANem
i bez něj; bezkódová druhová položka vzniká jako `DRAFT`/`isGeneric`, po potvrzení
`app.catalog.draft-confirmations` různými přispěvateli se překlopí na `ACTIVE`, confidence
agregátu má zastropovaná na `MEDIUM` — `docs/reputace.md`, „Zboží bez čárového kódu").

Angular: menu Hledání/Zadat cenu/Nastavení/Účet (na mobilním prohlížeči spodní lišta), stránka
hledání s filtry a tabulkou, detail produktu s SVG grafem vývoje ceny (`price-chart-geometry.ts`,
testováno Vitestem) a formulářem zápisu ceny přes sdílený `shared/store-picker.ts`, samostatná
stránka „Zadat cenu" (`features/price-entry`) hledá zboží podle názvu i kódu a umí založit nové
zboží/obchod (`features/product-form`, `shared/store-form.ts`) včetně zpětného data
(`observedAt`), stránka nastavení. Android: bottom navigation ze 4 záložek (Sken/Hledat/
Nastavení/Účet — `ui/navigation/AppDestinations.kt`), hledání, detail s Canvas grafem
(`PriceChartGeometry.kt`, testováno JUnitem), zápis ceny ze skenu i z detailu přes sdílený
`ui/common/StorePicker.kt` (napovídání podle názvu/města, ne jen GPS), založení obchodu
(`ui/store/StoreFormScreen.kt`) i zboží (`ui/product/ProductFormScreen.kt`), mapa/OFF odkazy.

**Uživatelská vrstva nad globálními daty** (`docs/datovy-model.md`, „Uživatelská vrstva nad
globálními daty"; práh důvěry a nahlašování v `docs/reputace.md`): úprava existujícího zboží/
obchodu jde do vedlejších patch tabulek (`core.product_user_edit`/`core.store_user_edit`,
`CatalogEditService.updateProduct`/`updateStore`), globální řádek se nemění — vidí ji jen autor,
dokud neproběhne (zatím nenapsaný) konsolidační job. Nový záznam se zveřejní podle prahu
důvěry autora (`TrustLevelService`, stáří účtu + `auth.app_user.observation_count`) — pod
prahem je vidět jen autorovi, dokud ho nepotvrdí `app.catalog.draft-confirmations` jiných
přispěvatelů (leave-one-out, stejně jako bezkódové zboží výš). Nahlášení (`core.record_flag`,
`RecordFlagService`, `flagRecord`) skryje záznam po `app.moderation.flags-to-hide` různých
hlasů — hlasuje se o záznamu, nikdy o autorovi. Čtení s překryvem (`ProductOverlayService`/
`StoreOverlayService`, `ViewerContext`/`ViewerContextResolver`) vrací vždy DETACHED kopii
entity (`toBuilder()`), nikdy nepřepisuje spravovanou entitu uvnitř transakce — Product/Store
mají proto GraphQL pole `verified`/`editedByMe` (Store navíc `pendingConfirmation`), Product
`myPrices` („Vaše cena" vedle komunitního agregátu, i dřív než ji zpracuje agregace). Angular
i Android odráží čtecí stranu stejným rozsahem (badge „neověřeno"/„vaše úprava", „Vaše cena",
tlačítko „Nahlásit", gating zakládání pro anonyma — web `store-picker`/`price-entry-page`,
mobil `ui/common/StorePicker.kt`/`ui/price/PriceEntryScreen.kt`) — **inline úprava existujícího
obchodu v UI je teď hotová** (web `features/store-detail`, mobil `ui/store/StoreDetailScreen.kt`
+ `StoreFormScreen.kt` v režimu editace, oba volají `updateStore`); **inline úprava zboží
(`updateProduct` z formuláře) zatím na žádném z klientů nechybí implementačně, ale UI ji pořád
nevolá** — `GraphQlClient.updateProduct` (mobil) a `ProductService.updateProduct` (web)
i backend mutace jsou hotové a otestované, jen na ně nemíří žádná obrazovka.

**Fotky zboží a provozoven** (`docs/datovy-model.md`, „Fotky zboží a provozoven"; práh
nahlášení v `docs/reputace.md`): `core.media` nese metadata, binární obsah (originál i náhled)
leží mimo databázi za rozhraním `MediaStorage`/`LocalFileSystemMediaStorage`, zpracování
(`ImageProcessingService`) fotku vždy překreslí z pixelů do nového JPEGu — strhne tak veškerá
metadata včetně EXIF GPS (`docs/soukromi.md`), otočí podle EXIF `Orientation` a zmenší jen
dolů. Upload jde přes REST (`MediaController`, multipart — GraphQL to nepodporuje), metadata
přes GraphQL (`Photo` typ, `Product.photos`/`Store.photos` přes `@BatchMapping`,
`updatePhoto`/`deletePhoto`). Nahlášení fotky (`RecordType.PHOTO`) má mnohem nižší práh než
katalog (`app.moderation.photo-flags-to-hide = 1`, `docs/reputace.md`). Web (`shared/
photo-gallery.ts`) i Android (`ui/common/PhotoGallery.kt`/`PhotoPicker.kt`, Coil) mají galerii
s náhledem, smazáním vlastní fotky a nahlášením cizí, na detailu zboží i obchodu.

**Adresa/mapa provozovny**: `reverseGeocode` (souřadnice → adresa, `GeocodingService`, vždy ze
serveru jako `geocodeAddress`) doplňuje adresu po „Použít mou polohu". Mapa nad OpenStreetMap
(web `shared/location-map.ts` — Leaflet, lazy `import()`; mobil `ui/common/LocationMap.kt` —
osmdroid) umožňuje náhled i výběr bodu klikem/přetažením značky — dlaždice se na rozdíl od
geokódování stahují přímo z klienta, vědomá výjimka zapsaná v `docs/soukromi.md`, zmírněná
tím, že se mapa nenačte, dokud si o to uživatel výslovně neřekne.

Neimplementováno (etapa 2/3): textové recenze (`core.product_review`, viditelnost
`PUBLIC`/`GROUPS`/`PRIVATE`, `ViewerContext` pro recenze), skupiny důvěry, plný reputační vzorec
(jen složka `L`), notifikace, lokální dodavatelé, OFF/OSM synchronizace mimo jednorázové
geokódování adresy, `agg.price_weekly_national`, offline fronta v mobilu, výběr řetězce při
zakládání obchodu (`chainId` v `CreateStoreInput` existuje, ale klienti zatím nenabízí číselník
řetězců k výběru), konsolidační job nad uživatelskou vrstvou (jen datový model a fronta,
vyhodnocovací pravidlo zatím není známé — viz výš), inline edit UI pro ZBOŽÍ na obou klientech
(mutace jsou hotové, jen je zatím nevolá žádná obrazovka — u OBCHODU už hotové je, viz výš),
fotka jako důkaz ceny (`core.price_observation`, `f_evid` v `docs/reputace.md` — fotky zatím
váží jen na katalogový záznam, ne na cenový zápis) — viz konec plánu založení projektu pro
rozpis a `docs/reputace.md` pro poznámku o hodnocení kvality vs. dodavatelích.

## Příkazy

### Lokální prostředí

```bash
docker compose up -d                              # PostgreSQL 17 na 127.0.0.1:5437
docker compose exec postgres psql -U postgres -d kvalitaacena   # psql není nainstalované lokálně
```

### Backend (`backend/`)

```bash
./gradlew bootRun                    # spustí appku, Boot si přes spring-boot-docker-compose sám nastartuje DB
./gradlew test                       # všechny testy
./gradlew test --tests "*.PriceAggregationServiceTest"   # jeden test
./gradlew clean build
```

Maven ani Gradle nejsou nainstalované globálně — vždy přes `./gradlew`, nikdy `gradle`.

### Frontend (`frontend/`)

Vyžaduje Node ≥ 22.22.3 (Angular 22) — systémový Node je 22.17.0, starý na to. Aktivuj přes
nvm: `source ~/.nvm/nvm.sh && nvm use 24` (Node 24 je nainstalované vedle systémového, výchozí
Node se neměnil — viz `.bashrc`). `npm start` používá `proxy.conf.json`, který přeposílá
`/api` a `/graphql` na `localhost:8080`, takže backend musí běžet zároveň.

```bash
npm install
npm start                            # dev server na :4200 (s proxy na backend)
npm test                             # Vitest (Angular 22 default, ne Karma/Jasmine)
npm run build
```

### Mobil (`mobile/`)

Vlastní Gradle wrapper (9.6.1, AGP vyžaduje ≥ 9.4.1 — stejná verze jako backend). Volba JDK je
přenositelná přes Gradle toolchain (`kotlin { jvmToolchain(17) }` v `app/build.gradle.kts`,
`foojay-resolver-convention` v `settings.gradle.kts` dotáhne chybějící JDK samo), ne přes
`org.gradle.java.home` — ten by na cizím stroji (i v CI) build hned na startu shodil. **AGP 9+ už nepotřebuje plugin
`org.jetbrains.kotlin.android`** (Kotlin podpora je vestavěná) — nepřidávej ho zpět, build by
rovnou spadl. `compileSdk 37` (víc novějších knihoven — activity-compose, core-ktx,
okhttp-android — to vyžaduje), `minSdk 26`, `targetSdk 35`; AGP chybějící SDK komponenty
(platformy, build-tools) při buildu sám dostáhne.

```bash
./gradlew :app:assembleDebug
./gradlew :app:compileDebugKotlin     # rychlejší kontrola bez balení APK
```

Emulátor (AVD `Medium_Phone`) je vyzkoušený a funkční — `~/Android/Sdk/emulator/emulator -avd
Medium_Phone -no-snapshot -no-boot-anim -gpu swiftshader_indirect` (Mesa/X11 GPU passthrough
v tomto stroji párkrát spadl s X errorem, `swiftshader_indirect` /software renderování/ je
spolehlivější). Instalace/spuštění: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
+ `adb shell am start -n cz.kvalitacena/.MainActivity`. Emulátor vidí hostitelský
backend na `10.0.2.2:8080` (viz `network/ApiConfig.kt` a `network_security_config.xml`, který
tam cleartext HTTP výslovně povoluje jen pro dev).

## Architektura, která se později mění nejhůř

Podrobný datový model je v `docs/datovy-model.md`; tady jen to, co je nutné znát před jakoukoli
změnou v daných oblastech.

### Oddělení schémat kvůli ODbL

PostgreSQL schémata: **`core`** (vlastní data), **`auth`**, **`agg`** (agregáty pro grafy),
**`off`** (Open Food Facts), **`osm`** (souřadnice provozoven z OpenStreetMap).

Open Food Facts **i OpenStreetMap** jsou pod ODbL se share-alike podmínkou. Žádná hodnota z
`off.*`/`osm.*` se **nikdy nekopíruje** do `core.*` — spojení vzniká až při čtení v service vrstvě
a UI vždy uvede zdroj. Čistý export vlastních dat je `pg_dump --schema=core --schema=agg`.
Aplikační DB uživatel má na `off`/`osm` jen `SELECT`, zapisuje jen synchronizační job.

### `core.price_observation` je jádro aplikace

- **`net_content_base` (gramáž/objem) se snapshotuje na observaci**, ne odkazuje na aktuální
  hodnotu z produktu — jinak by pozdější oprava gramáže přepsala jednotkové ceny celé historie.
- **`observed_at` (kdy to uživatel viděl) ≠ `created_at`** (kdy to došlo na server) — kvůli
  offline zápisu z mobilu.
- **`price_kind` (REGULAR/PROMO/CLUB_CARD/CLEARANCE/MULTIBUY) je součástí klíče agregátu** —
  akční, klubová a běžná cena se nikdy nemíchají do jedné řady.
- **Vnitroobchodní kódy váhového zboží nejsou globální identifikátor** — mají povinný `chain_id`
  (`core.product_code.code_type = STORE_INTERNAL`). EAN se normalizuje na GTIN-14.

### Agregace jsou tabulky (`agg.price_current`, `agg.price_daily`), ne materialized view

Váhy záznamů se mění zpětně (klesne něčí reputace, odhalí se sybil klastr), takže je potřeba
cílený přepočet konkrétních buněk přes `agg.recompute_queue` — `REFRESH MATERIALIZED VIEW` by
při milionech observací přepisovalo zbytečně celou view. Graf se čte vždy z `agg.price_daily`,
nikdy ze syrových observací. Národní cena je **medián mediánů** (nejdřív uvnitř provozovny, pak
přes provozovny).

### Recenze (etapa 2, zatím neimplementováno): autorizace je predikát v dotazu, ne filtr v resolveru

Recenze, skupiny důvěry a `ViewerContext` v etapě 1 vůbec neexistují (žádné entity, žádné
tabulky) — až budou, platí tohle:

Viditelnost `PUBLIC`/`GROUPS`/`PRIVATE` se vynucuje výhradně v `ReviewQueryService`
(JPA `Specification` z `ViewerContext`), s Hibernate `@Filter` jako pojistkou pro zapomenuté cesty
(nativní dotazy, DataLoader). GraphQL `DataLoader` musí mít **viewera v cache klíči**
(`(productId, viewerId)`) — jinak se cache prolije mezi uživateli. Neviditelná recenze vrací
`NOT_FOUND`, ne `FORBIDDEN`.

### Reputace: vzorce jsou jen v `docs/reputace.md`

**Stav v etapě 1**: implementovaná je jen složka `L` v `PriceAggregationService.weightFor()`
(anonym 0,15 / registrovaný 1,00) a vážený medián samotný. Plný vzorec `S` (přesnost ×
zkušenost × stáří účtu × úroveň identity × penalizace), `f_conf`/`f_evid`/`f_recency`/`f_group`,
`ReputationService` a `core.access_policy` jsou cílový stav pro etapu 2/3 — až se budou psát,
prahy patří do `docs/reputace.md`, ne rozeseté po kódu jako vlastní konstanty na více místech.
Klíčové už teď: souhlas (až bude implementovaný) se musí počítat **leave-one-out** (medián bez
vlastního záznamu uživatele), jinak si osamělý přispěvatel vždy "potvrdí sám sebe".

## Konvence

- Backend: Gradle **Groovy DSL**; mobil: Gradle **Kotlin DSL** (Android konvence) — `group =
  'cz.kvalitacena'`/`applicationId`, package `cz.kvalitacena.*` v obou
- Balíčky v backendu: `config`, `controller`, `service`, `security`, `exception`, `db/{entity,repo}`
- Lombok ano, MapStruct ne; konstruktorová injektáž přes `@RequiredArgsConstructor` (odlišně od
  `prani-pani-doktorce`, kde je psaná ručně — u tohoto projektu zvoleno kvůli menší, rychleji
  rostoucí sadě služeb v security/auth vrstvě)
- Liquibase YAML, `db/changelog/<datum>/NNN-nazev.yaml` + master changelog; entity `Persistable<Long>`,
  sloupce `TIMESTAMPTZ` ↔ `OffsetDateTime`
- Angular: standalone komponenty, signály, bez state managementu, bez Apollo (`provideHttpClient`
  s funkcionálními interceptory), `LOCALE_ID: 'cs-CZ'`; Prettier `printWidth: 100`, `singleQuote: true`
- Odsazení 2 mezery, kromě Kotlinu (4 mezery) — viz `.editorconfig`
- Android: jeden Activity + Compose Navigation (`ui/<feature>/XxxScreen.kt` + `XxxViewModel.kt`),
  ruční DI přes `AppContainer` (bez Hiltu — appka je malá), skener schovaný za
  `scanner/BarcodeScanner.kt` rozhraní (implementace `ZxingBarcodeScanner`), refresh token jen
  v `EncryptedSharedPreferences` (`auth/TokenStore.kt`), poloha přes obyčejný `LocationManager`
  (`location/LocationHelper.kt`), ne Play Services Fused Location — appka má běžet i bez GMS
- **Komentáře, commit zprávy a dokumentace česky**, identifikátory v kódu anglicky
- Pouze svobodné licence knihoven (MIT/Apache-2.0/BSD/EPL) — žádné knihovny s rizikem budoucí
  placené licence (proto např. ZXing místo ML Kit pro skenování, `cube`/`earthdistance` místo
  PostGIS)
