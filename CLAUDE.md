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

Plán založení a odůvodnění klíčových rozhodnutí: `docs/` (jednotlivé dokumenty datový model,
reputace, soukromí, AI, vydání, rozvoj — odkaz na samostatný plánovací soubor mimo repo tu dřív
byl, ale ten soubor už neexistuje).

## Monorepo — tři samostatné aplikace

```
backend/    Spring Boot 4, Java 25, Gradle (Groovy DSL) — API pro web i mobil
frontend/   Angular 22 + ng-zorro-antd — webové rozhraní
mobile/     Kotlin + Jetpack Compose — nativní Android
docs/       datový model, reputace, soukromí, AI, vydání — jeden zdroj pravdy pro vzorce a prahy
```

Každá část má vlastní build nástroj a vlastní `README`/konvence; sdílený je jen kontrakt API
(GraphQL schéma `backend/src/main/resources/graphql/schema.graphqls`). Frontend z něj přes
`graphql-codegen` (`frontend/codegen.ts`) generuje TypeScript typy a konstanty enumů do
`frontend/src/app/models/generated/` — čte schéma přímo z backendu, žádná kopie. Mobil zatím
typy z GraphQL schématu negeneruje (`network/Dto.kt` mapuje enumy ručně na `String`).

## Stav implementace (etapa 1 — prochozí kostra)

Hotovo a ověřeno end-to-end (backend přes curl, web i mobil živě v prohlížeči/emulátoru):
passwordless auth (OTP + refresh rotace), GraphQL `searchProducts` (filtr obchod/město, řazení,
stránkování, agregáty v `ProductSearchItem`) / `searchFacets` / `product` / `productByCode` /
`nearbyStores` / `priceHistory` / `me` / `submitObservations` / `rateProduct`, vážený medián
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
testováno Vitestem) a formulářem zápisu ceny přes sdílenou `shared/price-entry-form.ts`
(pohlcuje i `shared/store-picker.ts` — nahrazuje dřívější dvě skoro identické kopie formuláře
na stránce „Zadat cenu" a v detailu produktu), samostatná stránka „Zadat cenu"
(`features/price-entry`) hledá zboží podle názvu i kódu a umí založit nové zboží/obchod
(`features/product-form`, `shared/store-form.ts`) včetně zpětného data (`observedAt`), stránka
nastavení. Zápis ceny umí víc cen z jedné cenovky najednou (`shared/price-rows.ts` — běžná +
klubová + množstevní/MULTIBUY se zadají jedním „+" formulářem a odešlou jedním voláním
`submitObservations`, kolize jediného druhu ceny shodí celou dávku). Android: bottom navigation
ze 4 záložek (Sken/Hledat/Nastavení/Účet — `ui/navigation/AppDestinations.kt`), hledání, detail
s Canvas grafem (`PriceChartGeometry.kt`, testováno JUnitem), zápis ceny ze skenu i z detailu
přes sdílený `ui/common/StorePicker.kt` (napovídání podle názvu/města, ne jen GPS) a
`ui/price/PriceEntryScreen.kt`/`PriceEntryViewModel.kt` se stejným seznamem řádků „(druh ceny,
částka)" jako web (`ui/price/PriceRowValidation.kt`, testováno JUnitem), založení obchodu
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
hlasů — hlasuje se o záznamu, nikdy o autorovi; přezkum viz „Moderace" níže. Čtení s překryvem (`ProductOverlayService`/
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

**Výpis „Moje příspěvky"** (čtecí vrstva nad výše popsanou uživatelskou vrstvou): `myProducts`/
`myStores`/`myObservations`/`myEdits` (`MyContributionsGraphQlController`,
`MyContributionsService`, vyžadují přihlášení) vrací vlastní založené zboží/obchody, vlastní
zapsané ceny a vlastní úpravy cizích záznamů, každý s `PublicationStatus` (`state` PUBLIC/
AWAITING_CONFIRMATIONS/HIDDEN_AFTER_FLAGS/PENDING_MERGE + konkrétní
`confirmationsReceived`/`confirmationsRequired`, dopočítané dávkově přes
`PriceObservationRepository.countDistinctProductContributorsExcludingBatch`/
`countDistinctContributorsExcludingBatch`) — cíl je, aby uživatel viděl „zatím 1 ze 3", ne jen
štítek „čeká na potvrzení" bez kontextu. `MyObservationItem.publication` dědí horší ze stavů
blokujícího zboží a obchodu (cena sama žádný práh nemá). Web má stránku `/my`
(`features/my-contributions`, odkaz z Účtu, sdílená `shared/publication-status.ts` +
`publication-status-text.ts` s testem), mobil obrazovku `ui/contributions/
MyContributionsScreen.kt` + `MyContributionsViewModel.kt` (odkaz z `AccountScreen.kt`,
`GraphQlClient.myProducts`/`myStores`/`myObservations`/`myEdits`,
`PublicationStatusText.kt` s JUnit testem) — obojí čtyři záložky Zboží/Obchody/Ceny/Úpravy.

**Moderace** (`docs/reputace.md`, „Moderace"; T4 v „Odstupňování přístupu"): nástroj pro
přezkum nahlášených záznamů, chyběl přesně tam, kde appka i uživatelům slibovala „čeká na
přezkum". `ModerationService`/`ModerationGraphQlController` — role je sloupec
`auth.app_user.is_moderator` (nastavuje se ručně SQL, `docs/nasazeni.md`, promítne se do
`ROLE_MODERATOR` nejpozději do 60 s přes stejnou TTL cache v `JwtAuthenticationFilter`, co
hlídá `token_version`). `flaggedRecords` vypíše frontu nevyřízených nahlášení včetně skrytého
obsahu (predikáty viditelnosti v `Product`/`Store`/`MediaController`/`MediaService` mají navíc
větev `|| viewer.moderator()`); `resolveFlags` je jediná cesta zpět (`DISMISSED` vrátí
`hidden_at` na `NULL`, `UPHELD` skrytí potvrdí i pod prahem). Cenu nejde nahlásit komunitně
(`core.record_flag` míří jen na katalog), moderátor ji zamítá přímo přes
`moderationObservations`/`setObservationRejected` → `ObservationStatus.REJECTED` + povinné
zařazení do `agg.recompute_queue`. `setUserSuspended` pozastaví účet
(`docs/podminky-uziti.md`, „Ukončení a vyloučení") — `AppUserStatus.SUSPENDED` blokuje
autentizaci i nový OTP kód (`OtpService`), refresh tokeny se revokují
(`RefreshTokenService.revokeAllForUser`). Kdo nahlásil zůstává skryté i moderátorovi
(`record_flag.user_id`), kdo záznam založil vidí naopak jen moderátor
(`authorPublicUid`/`authorHandle`) — dvě různé věci se schválně jiným pravidlem
(`docs/soukromi.md`). Jen web (`/moderation`, odkaz z Účtu jen pro moderátora), mobil nemá —
je to nástroj provozovatele, ne appky.

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

**Profil uživatele a viditelnost** (`docs/soukromi.md`, „Profil uživatele a viditelnost";
datový tvar v `docs/datovy-model.md` pod stejným názvem): jméno, příjmení, přezdívka, telefon,
kontaktní e-mail (všechno nepovinné, `auth.user_profile`, šifrované stejným AES-256-GCM jako
`email_enc` — `EmailCipher.encryptValue`/`decryptValue`) a avatar (`core.media`,
`RecordType.USER`, šifrovaný NENÍ, vlastní REST `POST /api/media/user/avatar` — recordId se
bere z přihlášení, ne z URL). Výchozí viditelnost `ANONYMOUS`; u `PUBLIC`/`FRIENDS` rozhoduje
matice `auth.user_profile_field_visibility` po jednotlivých polích a publikách
(`UserProfileService.isFieldVisible`, jediné místo pravdy i pro viditelnost avataru v
`MediaController`) — řádky pro `FRIENDS` se zatím nikdy neuplatní, skupiny důvěry v etapě 1
neexistují. Přihlašovací e-mail se mění VÝHRADNĚ přes samostatný OTP tok
(`POST /api/auth/email/change/request`+`/confirm`, `EmailChangeService`) na NOVOU adresu, ne
polem v profilu — potvrzení inkrementuje `token_version` (odhlásí ostatní zařízení). GraphQL
`Viewer.profile`/`updateProfile`/`deleteAvatar`. Web (`features/profile`) i Android
(`ui/profile/ProfileScreen.kt`) mají formulář, tabulku viditelnosti a odkaz z Účtu; „Seznam
přátel"/„Hodnocení systémem"/„Důvěra od přátel"/„Moje statistiky" jsou zatím jen neaktivní
odkazy (`docs/reputace.md`).

**Adresa/mapa provozovny**: `reverseGeocode` (souřadnice → adresa, `GeocodingService`, vždy ze
serveru jako `geocodeAddress`) doplňuje adresu po „Použít mou polohu". Mapa nad OpenStreetMap
(web `shared/location-map.ts` — Leaflet, lazy `import()`; mobil `ui/common/LocationMap.kt` —
osmdroid) umožňuje náhled i výběr bodu klikem/přetažením značky — dlaždice se na rozdíl od
geokódování stahují přímo z klienta, vědomá výjimka zapsaná v `docs/soukromi.md`, zmírněná
tím, že se mapa nenačte, dokud si o to uživatel výslovně neřekne.

**Lokalizace: cs/sk/en/pl, multi-měna, strojově čitelný kontrakt chyb** (`docs/lokalizace.md`
je jeden zdroj pravdy — jazyky, mapa země→měna→locale, pravidla překladu, přehled testů):
backend má `ErrorCode`/`AppException` (`extensions.code` je závazný kontrakt, `message` jen
lokalizovaný fallback podle `Accept-Language`), `MessageSource`/`UserAwareLocaleResolver`,
měnu jako součást PK `agg.price_current`/`agg.price_daily` (jinak by vážený medián mísil CZK/
EUR/PLN), `core.category_i18n`, strukturovaný `HandleGenerator` (rod přídavného jména),
`CompanyIdValidator`/`CompanyRegistry` per zemi (IČO CZ/SK, NIP PL). **Kurzovní lístek ČNB a
zobrazovací měna** (`docs/lokalizace.md`, „Kurzovní lístek a zobrazovací měna"): denní stahování
do vlastního schématu `fx.exchange_rate` (`ExchangeRateSyncService`, backfill od nejstarší ceny
v DB), přepočet vždy kurzem platným k datu CENY, nikdy dnešním (`FxRateService`), nic z toho
nejde do `agg.*` — přepočet je čistě čtecí vrstva (`ConvertedPrice`), přenášená hlavičkou
`X-Display-Currency`, ne argumentem dotazu; USD je jen zobrazovací (nejde v ní zapsat cenu).
Frontend má Transloco (runtime přepínání, `FormatService` nad `Intl.*` misto `CurrencyPipe`/
`DatePipe`/`DecimalPipe`, anglické routy s českými redirecty) se všemi stránkami přepsanými na
i18n klíče a přepínačem zobrazovací měny v Nastavení (`DisplayCurrencyService`). Mobil má
`values-{sk,en,pl}/` vedle `values/` (čeština, zdroj i fallback), `AppCompatDelegate
.setApplicationLocales()`, `UiText` (`Res`/`Plural`/`Raw`) pro odklad `stringResource` do
Compose kontextu, `Money.kt`/`CompanyId.kt` zrcadlící backendová pravidla, stejný přepínač měny
(`ui/settings/DisplayCurrencyStore`) — všechny tři appky mají testy/lint guardy hlídající shodu
klíčů napříč jazyky (`docs/lokalizace.md`, „Testy a CI guardy"). **Country selector v UI**
(`docs/lokalizace.md`, „Country selector v UI"): `Query.countries` (číselník ze
`app.i18n.country-currency`), `CountryResolver` sjednocující dřívější duplicitní odvození země
ve `StoreGraphQlController`/`ProductGraphQlController`, `CreateStoreInput.country` bez
literálního defaultu (dřív se slovenský/polský obchod založený bez „Použít mou polohu" tiše
uložil jako český a dostal CZK navěky), oprava země existujícího obchodu jako jediná výjimka
zapisující rovnou do `core.store` místo `store_user_edit` (gatováno `TrustLevelService.
isTrusted`, `docs/datovy-model.md`, „Uživatelská vrstva nad globálními daty"), `uq_store_identity`
s `country` v indexu, nezávislý přepínač v Nastavení (`CountryService`/`CountryStore`) a
zobrazení kódu země u obchodu jen když se liší od zvolené domácí země
(`shared/store-label.ts`/`ui/common/StoreLabel.kt`). **Neimplementováno**: klientský překlad
chyb podle `code` na mobilu (appka ukáže `serverMessage`, protože `network/Dto.kt` negeneruje
typy ze schématu jako web).

**Stránka „O aplikaci"**: popis appky, odkud appka bere data (dřív karta „Zdroje dat"
v Nastavení, přesunuto sem i s řádkem verze appky), otevřený kód (GNU AGPL-3.0, odkaz na
`github.com/petrf22/kvalita-cena`) a kontakt — web `features/about` (`/about`, odkaz ze
`features/settings`), mobil `ui/about/AboutScreen.kt` (odkaz ze `SettingsScreen.kt`, otevírání
externích odkazů přes `ui/common/ExternalLinks.kt`). Na rozdíl od Podmínek užití/Zásad ochrany
osobních údajů se text plně překládá do všech pěti jazyků (není to právní text).

Neimplementováno (etapa 2/3): textové recenze (`core.product_review`, viditelnost
`PUBLIC`/`GROUPS`/`PRIVATE`, `ViewerContext` pro recenze), skupiny důvěry, plný reputační vzorec
(jen složka `L`), notifikace, lokální dodavatelé, OFF/OSM synchronizace mimo jednorázové
geokódování adresy, `agg.price_weekly_national`, offline fronta v mobilu, výběr řetězce při
zakládání obchodu (`chainId` v `CreateStoreInput` existuje, ale klienti zatím nenabízí číselník
řetězců k výběru), konsolidační job nad uživatelskou vrstvou (jen datový model a fronta,
vyhodnocovací pravidlo zatím není známé — viz výš), inline edit UI pro ZBOŽÍ na obou klientech
(mutace jsou hotové, jen je zatím nevolá žádná obrazovka — u OBCHODU už hotové je, viz výš),
fotka jako důkaz ceny (`core.price_observation`, `f_evid` v `docs/reputace.md` — fotky zatím
váží jen na katalogový záznam, ne na cenový zápis), další jazyky appky nad `de` (fr/es/it/hu/
ro/hr/si/bg/sr — plán expanze rozšířil na 16 ZEMÍ, ale jazyků je zatím jen pět, viz
`docs/lokalizace.md`, „Co zbývá"), lokální AI (`docs/ai.md` — čtení čísel
z fotek, kontrola textů, předfiltr moderace; zatím jen rozhodnutí v docs, žádný kód — výjimkou
je předfiltr fotek pro moderaci, který podle `docs/ai.md` patří před spuštění veřejného
provozu, ne až za etapu 2) — viz `docs/reputace.md` pro poznámku o hodnocení kvality vs.
dodavatelích. Další rozvojové nápady mimo etapu 1 (nezávazné, k realizaci až přijde řada) jsou
v `docs/rozvoj.md`: pojmenování slevové karty podle obchodu, ceny předem z akčního letáku,
načtení celé účtenky, nákup podle receptu nebo seznamu.

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
npm run codegen                      # typy z backend/.../schema.graphqls do models/generated/ (viz codegen.ts)
npm start                            # dev server na :4200 (s proxy na backend)
npm test                             # Vitest (Angular 22 default, ne Karma/Jasmine)
npm run build
```

`npm run codegen` spusť po každé změně `schema.graphqls` nebo dotazu v `graphql(...)` volání —
výstup v `src/app/models/generated/` se commituje, CI ho přegeneruje a shodí build, pokud se
rozejde (`git diff --exit-code`). Pozor na pořadí: `graphql(...)` matchuje dotaz na přesný
string zachycený při generování, takže Prettier (nebo jakákoli jiná změna whitespace uvnitř
těch template literálů) musí proběhnout **před** posledním `npm run codegen`, jinak typová
kontrola i běhový match spadnou.

### Mobil (`mobile/`)

Vlastní Gradle wrapper (9.6.1, AGP vyžaduje ≥ 9.4.1 — stejná verze jako backend). Volba JDK je
přenositelná přes Gradle toolchain (`kotlin { jvmToolchain(17) }` v `app/build.gradle.kts`,
`foojay-resolver-convention` v `settings.gradle.kts` dotáhne chybějící JDK samo), ne přes
`org.gradle.java.home` — ten by na cizím stroji (i v CI) build hned na startu shodil. **AGP 9+ už nepotřebuje plugin
`org.jetbrains.kotlin.android`** (Kotlin podpora je vestavěná) — nepřidávej ho zpět, build by
rovnou spadl. `compileSdk 37` (víc novějších knihoven — activity-compose, core-ktx,
okhttp-android — to vyžaduje), `minSdk 26`, `targetSdk 36` (Play od 31. 8. 2026 odmítá nižší,
viz `docs/vydani.md`); AGP chybějící SDK komponenty (platformy, build-tools) při buildu sám
dostáhne.

```bash
./gradlew :app:assembleDebug
./gradlew :app:compileDebugKotlin     # rychlejší kontrola bez balení APK
```

Emulátor (AVD `Pixel_6_API_30`) je vyzkoušený a funkční — `~/Android/Sdk/emulator/emulator -avd
Pixel_6_API_30 -no-snapshot -no-boot-anim -gpu swiftshader_indirect` (Mesa/X11 GPU passthrough
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
**`off`** (Open Food Facts), **`osm`** (souřadnice provozoven z OpenStreetMap), **`fx`**
(kurzovní lístek ČNB, `docs/lokalizace.md` — na rozdíl od `off`/`osm` sem appka sama píše).

Open Food Facts **i OpenStreetMap** jsou pod ODbL se share-alike podmínkou. Žádná hodnota z
`off.*`/`osm.*` se **nikdy nekopíruje** do `core.*` — spojení vzniká až při čtení v service vrstvě
a UI vždy uvede zdroj. Čistý export vlastních dat je `pg_dump --schema=core --schema=agg`.
Aplikační DB uživatel má na `off`/`osm` jen `SELECT`, zapisuje jen synchronizační job.

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

### Recenze (etapa 2, zatím neimplementováno): autorizace je predikát v dotazu, ne filtr v resolveru

Recenze, skupiny důvěry a `ViewerContext` v etapě 1 vůbec neexistují (žádné entity, žádné
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
- Angular: standalone komponenty, signály, bez state managementu, bez Apollo v runtime
  (`provideHttpClient` s funkcionálními interceptory, jeden POST `/graphql` v
  `services/graphql-service.ts`) — typy a tvary dotazů generuje `graphql-codegen` ze
  `schema.graphqls` do `src/app/models/generated/` (`npm run codegen`, commituje se), `graphql`
  balíček je tak jen build-time závislost, ne runtime; `LOCALE_ID: 'cs-CZ'`; Prettier
  `printWidth: 100`, `singleQuote: true`
- Odsazení 2 mezery, kromě Kotlinu (4 mezery) — viz `.editorconfig`
- Android: jeden Activity + Compose Navigation (`ui/<feature>/XxxScreen.kt` + `XxxViewModel.kt`),
  ruční DI přes `AppContainer` (bez Hiltu — appka je malá), skener schovaný za
  `scanner/BarcodeScanner.kt` rozhraní (implementace `ZxingBarcodeScanner`), refresh token jen
  v `EncryptedSharedPreferences` (`auth/TokenStore.kt`), poloha přes obyčejný `LocationManager`
  (`location/LocationHelper.kt`), ne Play Services Fused Location — appka má běžet i bez GMS
- **Komentáře, commit zprávy a dokumentace česky**, identifikátory v kódu anglicky
- **Lokalizace** (`docs/lokalizace.md`): Angular nepoužívá `CurrencyPipe`/`DatePipe`/
  `DecimalPipe` (formátování jde přes `FormatService` nad `Intl.*`, protože `LOCALE_ID` se
  vyhodnocuje jen jednou při bootstrapu a měna přichází z dat, ne z locale); routy jsou anglické
  a jazykově neutrální, české cesty jsou jen redirecty; pole `ico` v GraphQL je název z historie
  (nese IČO i NIP), validace i popisek jdou per `country`
- Pouze svobodné licence knihoven (MIT/Apache-2.0/BSD/EPL) — žádné knihovny s rizikem budoucí
  placené licence (proto např. ZXing místo ML Kit pro skenování, `cube`/`earthdistance` místo
  PostGIS)
