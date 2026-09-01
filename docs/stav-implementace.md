# Stav implementace

Podrobný inventář hotových a nehotových funkcí napříč backendem, frontendem a mobilem — jeden
zdroj pravdy pro „co už appka umí a v jakém souboru to žije", odkazovaný z [`CLAUDE.md`](../CLAUDE.md).
Přehledová matice níž je jen rozcestník — podrobnosti (proč tak, jaké soubory, jaké pasti) jsou
v příslušné sekci pod ní.

## Přehled

| Oblast | Backend | Web | Mobil | Stav |
|---|---|---|---|---|
| [Auth, hledání, agregace](#passwordless-auth-a-graphql-základ) | hotovo | hotovo | hotovo | HOTOVO |
| [Zakládání katalogu bez skenování/GPS](#zakládání-katalogu-bez-skenovánígps) | hotovo | hotovo | hotovo | HOTOVO |
| [Uživatelská vrstva nad globálními daty](#uživatelská-vrstva-nad-globálními-daty) | hotovo | hotovo | hotovo | HOTOVO |
| [Výpis „Moje příspěvky"](#výpis-moje-příspěvky) | hotovo | hotovo | hotovo | HOTOVO |
| [Moderace](#moderace) | hotovo | hotovo | — (záměr) | HOTOVO |
| [Fotky zboží a provozoven](#fotky-zboží-a-provozoven) | hotovo | hotovo | hotovo | HOTOVO |
| [Profil uživatele a viditelnost](#profil-uživatele-a-viditelnost) | hotovo | hotovo | hotovo | HOTOVO (skupiny důvěry mimo MVP) |
| [Adresa/mapa provozovny](#adresamapa-provozovny) | hotovo | hotovo | hotovo | HOTOVO |
| [Hledání podle čárového kódu](#hledání-podle-čárového-kódu) | hotovo | hotovo | hotovo | HOTOVO |
| [Hledání podle kategorie](#hledání-podle-kategorie) | hotovo | hotovo | hotovo | HOTOVO |
| [Lokalizace a multi-měna](#lokalizace-csskenplde-multi-měna-strojově-čitelný-kontrakt-chyb) | hotovo | hotovo | chybí klientský překlad chyb podle `code` | ČÁSTEČNĚ |
| [Vizuální identita](#vizuální-identita) | — | hotovo | hotovo | HOTOVO |
| [Stránka „O aplikaci"](#stránka-o-aplikaci) | — | hotovo | hotovo | HOTOVO |
| [Zpětná vazba](#zpětná-vazba) | hotovo | hotovo | hotovo | HOTOVO |
| [Open Food Facts — základní integrace](#open-food-facts) | hotovo | hotovo | hotovo | HOTOVO |
| [Aditiva (E-čka) z OFF](#aditiva-e-čka-z-off) | hotovo | hotovo (beze změny) | hotovo (beze změny) | HOTOVO |
| Neimplementováno | — | — | — | viz [„Neimplementováno"](#neimplementováno) níž |

## Passwordless auth a GraphQL základ

Hotovo a ověřeno end-to-end (backend přes curl, web i mobil živě v prohlížeči/emulátoru):
passwordless auth (OTP + refresh rotace), GraphQL `searchProducts` (filtr obchod/město/kategorie,
hledání i v číselníku kategorií, řazení, stránkování, agregáty v `ProductSearchItem`) /
`searchFacets` / `product` / `productByCode` /
`nearbyStores` / `priceHistory` / `me` / `submitObservations` / `rateProduct`, vážený medián
i denní agregace v `PriceAggregationService` (`agg.price_current` + `agg.price_daily`),
hodnocení kvality jako hvězdičky 1–5 (`core.product_quality_rating`, `QualityRatingService` —
jen průměr a počet, žádné texty, žádná viditelnost).

## Zakládání katalogu bez skenování/GPS

`docs/datovy-model.md`, „Identita provozovny":
`searchStores`/`createStore` (`StoreService` — vyžaduje přihlášení, `uq_store_identity` jako
pojistka proti duplicitám, volitelné IČO s kontrolním součtem + `companyByIco` přes ARES,
volitelné souřadnice přes `geocodeAddress`/OpenStreetMap Nominatim vždy ze serveru) a
`productSuggestions`/`categories`/`createProduct` (`ProductCatalogService` — zboží s EANem
i bez něj; bezkódová druhová položka vzniká jako `DRAFT`/`isGeneric`, po potvrzení
`app.catalog.draft-confirmations` různými **registrovanými** přispěvateli se překlopí na
`ACTIVE`, confidence agregátu má zastropovaná na `MEDIUM` — `docs/reputace.md`, „Zboží bez
čárového kódu").

### Web

Angular: menu Hledání/Zadat cenu/Nastavení/Účet (na mobilním prohlížeči spodní lišta), stránka
hledání s filtry a tabulkou, detail produktu s SVG grafem vývoje ceny (`price-chart-geometry.ts`,
testováno Vitestem) a formulářem zápisu ceny přes sdílenou `shared/price-entry-form.ts`
(pohlcuje i `shared/store-picker.ts` — nahrazuje dřívější dvě skoro identické kopie formuláře
na stránce „Zadat cenu" a v detailu produktu), samostatná stránka „Zadat cenu"
(`features/price-entry`) hledá zboží podle názvu i kódu a umí založit nové zboží/obchod
(`features/product-form`, `shared/store-form.ts`) včetně zpětného data (`observedAt`), stránka
nastavení. Zápis ceny umí víc cen z jedné cenovky najednou (`shared/price-rows.ts` — běžná +
klubová + množstevní/MULTIBUY se zadají jedním „+" formulářem a odešlou jedním voláním
`submitObservations`, kolize jediného druhu ceny shodí celou dávku). PROMO navíc umí nepovinnou
platnost „od–do" (`promo_valid_from`/`promo_valid_to`, `docs/datovy-model.md`) — `od` nesmí být
v budoucnu, vypršelá akce zmizí z aktuálních cen (`ProductGraphQlController`, filtr při čtení),
v grafu historie zůstává.

### Mobil

Android: bottom navigation ze 4 záložek (Sken/Hledat/Nastavení/Účet —
`ui/navigation/AppDestinations.kt`), hledání s filtry obchod/město/řazení přežívajícími
přepnutí záložky (`ui/settings/SearchFilterStore.kt` — appka jinak maže při přepnutí celý
navigační zásobník i s `SearchViewModel`), detail s Canvas grafem (`PriceChartGeometry.kt`,
testováno JUnitem), zápis ceny ze skenu i z detailu přes sdílený `ui/common/StorePicker.kt`
(napovídání podle názvu/města, ne jen GPS) a `ui/price/PriceEntryScreen.kt`/
`PriceEntryViewModel.kt` se stejným seznamem řádků „(druh ceny, částka)" jako web
(`ui/price/PriceRowValidation.kt`, testováno JUnitem), založení obchodu
(`ui/store/StoreFormScreen.kt`) i zboží (`ui/product/ProductFormScreen.kt`), mapa/OFF odkazy.
Sekce „Zadat cenu" na `PriceEntryScreen` je schovaná za tlačítkem, dokud uživatel jednou
úspěšně nezapíše cenu (`ui/settings/PriceEntryVisibilityStore.kt`) — appka tak stejně dobře
slouží lidem, co jen hledají ceny poblíž.

## Uživatelská vrstva nad globálními daty

`docs/datovy-model.md`, „Uživatelská vrstva nad globálními daty"; práh důvěry a nahlašování
v `docs/reputace.md`: úprava existujícího zboží/obchodu jde do vedlejších patch tabulek
(`core.product_user_edit`/`core.store_user_edit`, `CatalogEditService.updateProduct`/
`updateStore`), globální řádek se nemění — vidí ji jen autor, dokud neproběhne (zatím
nenapsaný) konsolidační job. Nový záznam se zveřejní podle prahu důvěry autora
(`TrustLevelService`, stáří účtu + `auth.app_user.observation_count`) — pod prahem je vidět
jen autorovi, dokud ho nepotvrdí `app.catalog.draft-confirmations` jiných **registrovaných**
přispěvatelů (leave-one-out, stejně jako bezkódové zboží výš). Nahlášení (`core.record_flag`,
`RecordFlagService`, `flagRecord`) skryje záznam po `app.moderation.flags-to-hide` různých
hlasů — hlasuje se o záznamu, nikdy o autorovi; přezkum viz „Moderace" níž. Čtení s překryvem
(`ProductOverlayService`/`StoreOverlayService`, `ViewerContext`/`ViewerContextResolver`) vrací
vždy DETACHED kopii entity (`toBuilder()`), nikdy nepřepisuje spravovanou entitu uvnitř
transakce — Product/Store mají proto GraphQL pole `verified`/`editedByMe` (Store navíc
`pendingConfirmation`), Product `myPrices` („Vaše cena" vedle komunitního agregátu, i dřív než
ji zpracuje agregace). Angular i Android odráží čtecí stranu stejným rozsahem (badge
„neověřeno"/„vaše úprava", „Vaše cena", tlačítko „Nahlásit", gating zakládání pro anonyma —
web `store-picker`/`price-entry-page`, mobil `ui/common/StorePicker.kt`/
`ui/price/PriceEntryScreen.kt`) — **inline úprava existujícího obchodu v UI je hotová** (web
`features/store-detail`, mobil `ui/store/StoreDetailScreen.kt` + `StoreFormScreen.kt` v režimu
editace, oba volají `updateStore`) — obchod má i nepovinné pole `url` (odkaz na jeho stránku
u řetězce), stejnou cestou přes `core.store_user_edit` jako street/postalCode. **Výběr řetězce
při zakládání/editaci obchodu je od 2026-08-29 hotový** — fixní kurátorský číselník
`core.retail_chain` naplněný migrací (`2026-08-29/03-retail-chain-seed.yaml`, zatím jen CZ),
našeptávač `Query.chains` (`ChainCatalogService`/`RetailChainRepository.searchByText`,
`core.norm_text` jako u `searchStores`) a pole na obou klientech (web `shared/store-form.ts`,
mobil `ui/store/StoreFormViewModel.kt` + `SearchableDropdown.kt`) — výběr předvyplní název
obchodu jen když je pole ještě prázdné, `chainId`/`clearChain` v `CreateStoreInput`/
`UpdateStoreInput` se teď skutečně posílají. **Inline úprava zboží je teď taky hotová** — vstup
jen z detailu zboží (web `features/product-detail` + `app-product-form` v modalu, mobil
`ui/detail/ProductDetailScreen.kt` + `ui/product/ProductFormScreen.kt` v režimu editace), oba
volají `updateProduct`. Čárový kód je v editaci jen ke čtení (`UpdateProductInput` ho neumí
změnit) a fotky/návrhy podobných položek se v editaci skryjí (fotky se spravují v galerii na
detailu). Gramáž/objem se posílá vždy jako dvojice `netContentValue`/`netContentUom`, i když se
změnila jen základní jednotka nebo přepínač váhového zboží (`netContentForUpdateSubmit`/
`buildUpdateProductInput` na webu, `ProductFormValidation.kt` na mobilu) — stejná past jako
u `createProductFromOff` (`CLAUDE.md`, „Pasti, které z kódu nejsou vidět").

## Výpis „Moje příspěvky"

Čtecí vrstva nad výše popsanou uživatelskou vrstvou: `myProducts`/`myStores`/`myObservations`/
`myEdits` (`MyContributionsGraphQlController`, `MyContributionsService`, vyžadují přihlášení)
vrací vlastní založené zboží/obchody, vlastní zapsané ceny a vlastní úpravy cizích záznamů,
každý s `PublicationStatus` (`state` PUBLIC/AWAITING_CONFIRMATIONS/HIDDEN_AFTER_FLAGS/
PENDING_MERGE + konkrétní `confirmationsReceived`/`confirmationsRequired`, dopočítané dávkově
přes `PriceObservationRepository.countDistinctProductContributorsExcludingBatch`/
`countDistinctContributorsExcludingBatch`, které počítají jen registrované přispěvatele) — cíl
je, aby uživatel viděl „zatím 1 ze 3", ne jen štítek „čeká na potvrzení" bez kontextu.
`MyObservationItem.publication` dědí horší ze stavů blokujícího zboží a obchodu (cena sama
žádný práh nemá). Web má stránku `/my` (`features/my-contributions`, odkaz z Účtu, sdílená
`shared/publication-status.ts` + `publication-status-text.ts` s testem), mobil obrazovku
`ui/contributions/MyContributionsScreen.kt` + `MyContributionsViewModel.kt` (odkaz z
`AccountScreen.kt`, `GraphQlClient.myProducts`/`myStores`/`myObservations`/`myEdits`,
`PublicationStatusText.kt` s JUnit testem) — obojí čtyři záložky Zboží/Obchody/Ceny/Úpravy.

## Moderace

`docs/reputace.md`, „Moderace"; T4 v „Odstupňování přístupu": nástroj pro přezkum nahlášených
záznamů, chyběl přesně tam, kde appka i uživatelům slibovala „čeká na přezkum".
`ModerationService`/`ModerationGraphQlController` — role je sloupec
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

## Fotky zboží a provozoven

`docs/datovy-model.md`, „Fotky zboží a provozoven"; práh nahlášení v `docs/reputace.md`:
`core.media` nese metadata, binární obsah (originál i náhled) leží mimo databázi za rozhraním
`MediaStorage`/`LocalFileSystemMediaStorage`, zpracování (`ImageProcessingService`) fotku vždy
překreslí z pixelů do nového JPEGu — strhne tak veškerá metadata včetně EXIF GPS
(`docs/soukromi.md`), otočí podle EXIF `Orientation` a zmenší jen dolů. Upload jde přes REST
(`MediaController`, multipart — GraphQL to nepodporuje), metadata přes GraphQL (`Photo` typ,
`Product.photos`/`Store.photos` přes `@BatchMapping`, `updatePhoto`/`deletePhoto`). Nahlášení
fotky (`RecordType.PHOTO`) má mnohem nižší práh než katalog
(`app.moderation.photo-flags-to-hide = 1`, `docs/reputace.md`). Web (`shared/photo-gallery.ts`)
i Android (`ui/common/PhotoGallery.kt`/`PhotoPicker.kt`, Coil) mají galerii s náhledem, smazáním
vlastní fotky a nahlášením cizí, na detailu zboží i obchodu. Fotka nese i druh
(`photo_kind`: `ITEM`/`LABEL`/`OTHER`, `docs/datovy-model.md`) a formulář nového zboží nabízí
dva nepovinné sloty (fotka zboží, fotka etikety) — appka nahraje soubor až po založení produktu
(`shared/photo-slot.ts` na webu, `ui/common/PhotoSlot.kt` na mobilu).

## Profil uživatele a viditelnost

`docs/soukromi.md`, „Profil uživatele a viditelnost"; datový tvar v `docs/datovy-model.md` pod
stejným názvem: jméno, příjmení, přezdívka, telefon, kontaktní e-mail (všechno nepovinné,
`auth.user_profile`, šifrované stejným AES-256-GCM jako `email_enc` —
`EmailCipher.encryptValue`/`decryptValue`) a avatar (`core.media`, `RecordType.USER`, šifrovaný
NENÍ, vlastní REST `POST /api/media/user/avatar` — recordId se bere z přihlášení, ne z URL).
Výchozí viditelnost `ANONYMOUS`; u `PUBLIC`/`FRIENDS` rozhoduje matice
`auth.user_profile_field_visibility` po jednotlivých polích a publikách
(`UserProfileService.isFieldVisible`, jediné místo pravdy i pro viditelnost avataru v
`MediaController`) — řádky pro `FRIENDS` se zatím nikdy neuplatní, skupiny důvěry v MVP
neexistují. Přihlašovací e-mail se mění VÝHRADNĚ přes samostatný OTP tok
(`POST /api/auth/email/change/request`+`/confirm`, `EmailChangeService`) na NOVOU adresu, ne
polem v profilu — potvrzení inkrementuje `token_version` (odhlásí ostatní zařízení). GraphQL
`Viewer.profile`/`updateProfile`/`deleteAvatar`. Web (`features/profile`) i Android
(`ui/profile/ProfileScreen.kt`) mají formulář, tabulku viditelnosti a odkaz z Účtu; „Seznam
přátel"/„Hodnocení systémem"/„Důvěra od přátel"/„Moje statistiky" jsou zatím jen neaktivní
odkazy (`docs/reputace.md`).

## Adresa/mapa provozovny

`reverseGeocode` (souřadnice → adresa, `GeocodingService`, vždy ze serveru jako
`geocodeAddress`, souřadnice se před dotazem na Nominatim zaokrouhlí na 4 desetinná místa,
`docs/soukromi.md`) doplňuje adresu po „Použít mou polohu". Mapa nad OpenStreetMap (web
`shared/location-map.ts` + `shared/map-tiles.ts` — Leaflet, lazy `import()`; mobil
`ui/common/LocationMap.kt` + `MapConfig.kt` — osmdroid) umožňuje náhled i výběr bodu
klikem/přetažením značky — dlaždice se na rozdíl od geokódování stahují přímo z klienta,
vědomá výjimka zapsaná v `docs/soukromi.md`, zmírněná tím, že se mapa nenačte, dokud si o to
uživatel výslovně neřekne. Poskytovatel dlaždic je konfigurovatelný (web `map-tiles.ts`, mobil
`KVALITACENA_MAP_TILE_URL`), výchozí OpenStreetMap Mapnik. Mobil navíc má tlačítko „Na mou
polohu" přímo v mapě (jednorázový `getCurrentLocation`, `ui/common/OsmMapView.kt` —
`MyLocationButton`, sdílené `LocationMap`/`StoreMap`), atribuci poskytovatele přímo v mapě
(`CopyrightOverlay`) a mapu se značkami obchodů k výběru (`ui/common/StoreMap.kt`, zapojená do
`StorePicker` vedle napovídání a „Najít v okolí") — souřadnice uživatele se do ní na rozdíl od
`LocationMap` v editovatelném režimu vůbec nedostávají, appka jen ukáže obchody, které už zná.

## Hledání podle čárového kódu

`searchProducts` (backend `ProductSearchService.codeQuery`, `ProductSearchRepositoryImpl` —
jedna ze čtyř větví CTE `candidate`) rozpozná dotaz tvořený jen číslicemi délky 8–14 a zkusí ho
i jako GTIN (`core.product_code`, jen `code_type = GTIN`, nikdy `STORE_INTERNAL`) vedle
fulltextu podle názvu — funguje tak zadarmo na webu i mobilu, obojí volá stejný GraphQL dotaz.
Mobil navíc na `PriceEntryScreen` nabízí tlačítko „Hledat ceny tohoto zboží", které naskenovaný
kód (nebo název u vstupu z detailu) přehodí do záložky Hledat přes
`ui/common/NavigationResults.searchQuery` — výpis „Aktuální ceny po obchodech" pod naskenovaným
zbožím totiž není omezený na město, jen na zemi.

## Hledání podle kategorie

`searchProducts` matchuje dotaz zároveň v názvu zboží a v číselníku kategorií (celý podstrom,
`core.category.path`) — zboží „bio 3,5 % tuku" v kategorii Mléko se najde na „mléko", i když to
slovo v názvu nemá (`ProductSearchRepositoryImpl`, CTE `cat_hit`/`cat_scope`). Argument
`categoryId` navíc filtr zúží explicitně (AND nad textovou shodou, taky bere podstrom);
neplatné id vrací `CATEGORY_NOT_FOUND`. Diakritika je sjednocená přes `core.norm_text` na obou
stranách (`idx_product_name_norm_fts` nahradil starý `idx_product_name_fts`). Klienti pro výběr
kategorie znovupoužívají stejné komponenty jako ve formuláři zboží (web `nz-tree-select` +
`shared/category-tree.ts`, mobil `SearchableDropdown` + `ui/common/CategoryTree.kt`), mobil
navíc filtr přežívá přepnutí záložky (`SearchFilterStore`).

## Lokalizace: cs/sk/en/pl/de, multi-měna, strojově čitelný kontrakt chyb

`docs/lokalizace.md` je jeden zdroj pravdy — jazyky, mapa země→měna→locale, pravidla překladu,
přehled testů: backend má `ErrorCode`/`AppException` (`extensions.code` je závazný kontrakt,
`message` jen lokalizovaný fallback podle `Accept-Language`),
`MessageSource`/`UserAwareLocaleResolver`, měnu jako součást PK `agg.price_current`/
`agg.price_daily` (jinak by vážený medián mísil CZK/EUR/PLN), `core.category_i18n`,
strukturovaný `HandleGenerator` (rod přídavného jména), `CompanyIdValidator`/`CompanyRegistry`
per zemi (IČO CZ/SK, NIP PL). **Kurzovní lístek ČNB a zobrazovací měna**
(`docs/lokalizace.md`, „Kurzovní lístek a zobrazovací měna"): denní stahování do vlastního
schématu `fx.exchange_rate` (`ExchangeRateSyncService`, backfill od nejstarší ceny v DB),
přepočet vždy kurzem platným k datu CENY, nikdy dnešním (`FxRateService`), nic z toho nejde do
`agg.*` — přepočet je čistě čtecí vrstva (`ConvertedPrice`), přenášená hlavičkou
`X-Display-Currency`, ne argumentem dotazu; USD je jen zobrazovací (nejde v ní zapsat cenu).
Frontend má Transloco (runtime přepínání, `FormatService` nad `Intl.*` misto `CurrencyPipe`/
`DatePipe`/`DecimalPipe`, anglické routy s českými redirecty) se všemi stránkami přepsanými na
i18n klíče a přepínačem zobrazovací měny v Nastavení (`DisplayCurrencyService`). Mobil má
`values-{sk,en,pl,de}/` vedle `values/` (čeština, zdroj i fallback), `AppCompatDelegate
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
(`shared/store-label.ts`/`ui/common/StoreLabel.kt`).

**Neimplementováno**: klientský překlad chyb podle `code` na mobilu (appka ukáže
`serverMessage`, protože `network/Dto.kt` negeneruje typy ze schématu jako web); jazyky appky
nad `de` — plán expanze rozšířil na 16 zemí, ale jazyků appka umí zatím jen pět, viz
`docs/lokalizace.md`, „Co zbývá".

## Vizuální identita

Ikona appky (rámeček skeneru s pruhy čárového kódu, `#1677FF`) i favicon/PWA manifest/Android
launcher z ní odvozené — `docs/branding.md` je zdroj pravdy, geometrie sama žije
v `tools/icons/generate.py` (`python3 tools/icons/generate.py` po každé úpravě kresby).

## Stránka „O aplikaci"

Popis appky, odkud appka bere data (dřív karta „Zdroje dat" v Nastavení, přesunuto sem i s
řádkem verze appky), otevřený kód (GNU AGPL-3.0, odkaz na `github.com/petrf22/kvalita-cena`) a
kontakt — web `features/about` (`/about`, odkaz ze `features/settings`), mobil
`ui/about/AboutScreen.kt` (odkaz ze `SettingsScreen.kt`, otevírání externích odkazů přes
`ui/common/ExternalLinks.kt`). Na rozdíl od Podmínek užití/Zásad ochrany osobních údajů se text
plně překládá do všech pěti jazyků (není to právní text).

## Zpětná vazba

`docs/datovy-model.md`, „Zpětná vazba"; `docs/nasazeni.md`, „Než pozvat první lidi": jediný
first-party kanál pro uzavřenou betu — appka dřív neměla žádný způsob, jak od testerů dostat
hlášení, jen `mailto:` na dosud nezřízenou schránku. `core.feedback`/`FeedbackService`/
`FeedbackGraphQlController` (mutace `submitFeedback` funguje i BEZ přihlášení, na rozdíl od
`flagRecord`), fronta pro moderátora (`feedbackItems`/`setFeedbackHandled`) přidaná do
existujícího `ModerationGraphQlController` jako čtvrtá záložka na `/moderation`. Autor SE (na
rozdíl od `record_flag`) moderátorovi vrací i s dešifrovaným volitelným kontaktním e-mailem —
jinak není komu odpovědět (`docs/soukromi.md`, vědomá odchylka od nahlašování). Web
`features/feedback` (`/feedback`, odkaz z patičky i „O aplikaci"), mobil
`ui/feedback/FeedbackScreen.kt` (odkaz z Nastavení i „O aplikaci"), oba i s neregistrovaným
uživatelem. Android navíc zachytává pády bez třetí strany (`crash/CrashReporter.kt`,
`Thread.setDefaultUncaughtExceptionHandler`) — záznam zůstává jen v `filesDir`, appka ho
nabídne přiložit k hlášení až po výslovném zaškrtnutí (výchozí nezaškrtnuto), nikdy neodchází
sám od sebe. Verze appky na webu se generuje z `package.json` (`tools/version/write-version.mjs`
→ `src/app/version.ts`, `npm run prestart`/`prebuild`) místo dřívější natvrdo zapsané
konstanty; mobil odjakživa četl `BuildConfig.VERSION_NAME`. `dev/beta-report.sql` je provozní
přehled bez analytiky (kolik lidí zapsalo cenu, kolik čeká na potvrzení/moderaci) pro ruční
kontrolu při běžící betě. **Obrana proti spamu je hotová** (`docs/nasazeni.md`, „Zbývá",
2026-09-01): oprava obejitelného `X-Forwarded-For` (sdílený `ClientIpResolver`, Caddy
`header_up`), vrstvené limity (`FeedbackRateLimiter` — IP/podsíť/globální anonymní/uživatel),
proof-of-work výzva místo CAPTCHY (`FeedbackChallengeService`, web `shared/proof-of-work.ts` +
Web Worker, mobil `ui/feedback/ProofOfWork.kt`) a skórování s karanténou
(`FeedbackSpamDetector`, nová záložka „Podezřelé" na `/moderation`).

## Open Food Facts

Implementováno: Open Food Facts EAN lookup s cache, odděleným `off.product` snapshotem,
výchozími hodnotami při založení produktu (`createProductFromOff`, `OffProductCatalogService`)
a možností uživatelského přepsání. GraphQL API (`productLookupByCode`, `Product.externalImage`/
`catalogAttribution`) i mobilní/webový `Dto.kt`/`product-service.ts` jsou hotové a otestované;
`price-entry-page`/`PriceEntryScreen` na sken/zadání neznámého EANu volají `productLookupByCode`
a formulář nového zboží (`product-form.ts`/`ProductFormViewModel.kt`) OFF kandidáta předvyplní
(gramáž převedenou z G/ML na kg/l) a odešle přes `createProductFromOff` — jen pole, která
uživatel skutečně změnil oproti OFF defaultu, aby nevznikl zbytečný `core.product_user_edit`
patch. Detail zboží na obou klientech zobrazuje `catalogAttribution`/`externalImage`. Klientská
session cache nad `productLookupByCode` (`ProductService`/`GraphQlClient`) šetří opakované
dotazy na stejný kód. Zpětné publikování oprav do OFF zůstává mimo MVP.

## Aditiva (E-čka) z OFF

`off.product.additives_tags` (OFF pole `additives_tags`, `off-additives.yaml`) doplňuje ke kartě
„Další informace" odkaz `ExternalLinkKind.E_NUMBERS` na konkrétní aditivum produktu (kód velkými
písmeny jako label, max 5, `ProductGraphQlController.externalLinksFor`) — čistě čtecí rozšíření
beze změny klientů, karta odkazy renderuje generickým cyklem. Zbytek údajů z etikety (nutriční
tabulka, složení, alergeny, vlastní zadání) je rozvojový nápad v `docs/rozvoj.md`.

## Neimplementováno

Textové recenze (`core.product_review`, viditelnost `PUBLIC`/`GROUPS`/`PRIVATE`,
`ViewerContext` pro recenze), skupiny důvěry, plný reputační vzorec (jen složka `L`),
notifikace, lokální dodavatelé, OFF/OSM synchronizace mimo jednorázové geokódování adresy,
`agg.price_weekly_national`, offline fronta v mobilu, konsolidační job nad uživatelskou vrstvou
(jen datový model a fronta, vyhodnocovací pravidlo zatím není známé — viz „Uživatelská vrstva
nad globálními daty" výš), fotka jako důkaz ceny
(`core.price_observation`, `f_evid` v `docs/reputace.md` — fotky zatím váží jen na katalogový
záznam, ne na cenový zápis), další jazyky appky nad `de` (viz „Lokalizace" výš), lokální AI
(`docs/ai.md` — čtení čísel z fotek, kontrola textů, předfiltr moderace; zatím jen rozhodnutí
v docs, žádný kód — výjimkou je předfiltr fotek pro moderaci, který podle `docs/ai.md` patří
před spuštění veřejného provozu, ne až za MVP) — viz `docs/reputace.md` pro poznámku o
hodnocení kvality vs. dodavatelích.

Další rozvojové nápady mimo MVP (nezávazné, k realizaci až přijde řada, se stavem
NÁPAD/ROZHODNOUT/PLÁNOVÁNO/ČÁSTEČNĚ) jsou v `docs/rozvoj.md`: pojmenování slevové karty podle
obchodu, ceny předem z akčního letáku, načtení celé účtenky, nákup podle receptu nebo seznamu,
údaje z etikety (nutriční hodnoty, složení, alergeny — aditiva/E-čka jako odkazy z OFF už
hotová jsou, viz výš).
