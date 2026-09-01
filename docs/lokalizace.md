# Lokalizace: jazyky, měny, kontrakt chyb

Dvě zadání, která šla proti nejjednoduššímu řešení a určila architekturu: appka nemá jen jiný
jazyk, ale i jinou **zemi a měnu** (SK/EUR, PL/PLN vedle CZ/CZK), a lokalizace se dělala **hned
celá**, ne jako dokumentovaný záměr bez kódu. Tenhle dokument je jeden zdroj pravdy pro seznam
jazyků, mapu země→měna→locale a pravidla překladu — obdoba toho, čím je `docs/reputace.md` pro prahy.

## Jazyky a měny

| Jazyk | Kód | Země (výchozí) | Měna | Poznámka |
|---|---|---|---|---|
| Čeština | `cs` | CZ | CZK | **zdrojový jazyk a fallback** — ne angličtina |
| Slovenčina | `sk` | SK | EUR | |
| English | `en` | — | — | bez vlastní výchozí země/měny, appka se jím dá používat odkudkoli |
| Polski | `pl` | PL | PLN | |
| Deutsch | `de` | DE | EUR | přidáno etapou 2 plánu expanze (2026-08) — pokrývá i AT/CH (`country-locale`) |

Mapa `country → currency` a `country → locale` je na backendu `app.i18n.*`
(`application.yml`, `I18nProperties`) — jediné místo, které ji zná; frontend/mobil mají jen
odlehčenou kopii pro NÁPOVĚDU v UI (popisek pole ceny dřív, než zná server), viz níže.

**Země je nezávislá osa od jazyka** (plán expanze, 2026-08) — appka od srpna 2026 zná i dalších
13 zemí (Německo, Rakousko, Francie, Španělsko, Itálie, Chorvatsko, Slovinsko, Bulharsko,
Maďarsko, Rumunsko, Británie, Švýcarsko, Srbsko). Vlna expanze 1 je přidala BEZE ZMĚNY jazyků — nové
země bez vlastního jazyka míří na `en` v `country-locale` (`application.yml`). Rozšíření o zemi
je tak jen konfigurace + CHECK constraint na `currency`
(`db/changelog/2026-08-17/01-countries.yaml`), ne zásah do žádného klienta; rozšíření o JAZYK je
pořád plná práce popsaná v `## Testy a CI guardy` níže (~700 řetězců na jazyk) a dělá se
samostatně, podle poptávky. 9 z 13 nových zemí je EUR (nulová práce ve `fx.*`); zbylé čtyři
(HUF, RON, GBP, CHF) ČNB kótuje stejně jako EUR/PLN. Jedinou výjimkou je Srbsko — RSD na lístku
ČNB není (ověřeno živě), kurz se stahuje z Národní banky Srbska (`service/fx/NbsRateSource`,
`app.external.nbs`) jako druhý `ExchangeRateSource` vedle ČNB, viz „Kurzovní lístek a
zobrazovací měna" níže.

**Vlna expanze 2 (2026-08) přidala `de` jako první skutečně nový JAZYK** — referenční postup pro
další jazyky. DE/AT/CH v `country-locale` teď míří na `de` (dřív na `en` jako každá nová země
bez vlastního jazyka). Objem překladu na jeden jazyk: backend `messages/*_de.properties`
(errors 67 + handles 49 + mail 8 + attribution 4 + countries 16 = 144 klíčů), web
`public/i18n/de.json` + 10 scope souborů (356 klíčů), mobil `values-de/strings.xml` (242
řetězců, `terms_*`/`privacy_*` string-array zůstávají `translatable="false"` jako u ostatních
jazyků — právní text jen česky). `core.category_i18n` v době psaní ještě beze změny — katalog
kategorií byl prázdný (`docs/datovy-model.md`), nebylo co překládat. Doplněno až
`2026-08-20/01-category-tree.yaml` (~106 kategorií × 4 jazyky, viz „Kategorie" níž).

**Proč čeština, ne angličtina, je fallback:** appka vznikla pro český trh, drtivá většina dat
(kategorie, handly, mail) existuje nejdřív česky. Anglický fallback by pro švédského turistu na
Slovensku fungoval stejně dobře, ale pro zapomenutý klíč u českého uživatele by byl matoucí
skok do cizího jazyka uprostřed jinak české appky.

## Kontrakt chyby: `extensions.code` je závazný, `message` je jen fallback

Každá doménová výjimka (`AppException` a potomci) nese `ErrorCode` — strojově čitelný enum,
stejné hodnoty na backendu (`exception/ErrorCode.java`) i ve schématu (`enum ErrorCode` v
`schema.graphqls`, jen pro codegen, nepoužívá se v žádném poli). GraphQL chyba dostane obojí:

```json
{
  "message": "Produkt s tímto id neexistuje",
  "extensions": { "code": "PRODUCT_NOT_FOUND", "params": [] }
}
```

- **Klient hledá vlastní překlad podle `code`** (web `shared/error-message.ts`, mobil
  `ui/common/ErrorMessages.kt` — zatím jen `Raw(serverMessage)`, viz "Mobil" níže).
- **`message` je fallback** — server ho už poslal lokalizovaný podle `Accept-Language`, takže
  klient nespadne na obecné "Něco se pokazilo" jen proto, že backend přidal `ErrorCode` dřív,
  než se vydala nová verze webu/appky.
- **Do `{0}`/`{{param}}` smí jen datová hodnota** (číslo, název, IČO), **nikdy přeložený kus
  věty** — v polštině a slovenštině se mění slovosled i vazba slovesa, takže sestavená věta z
  kousků by v části jazyků zněla rozbitě. `MediaService`/`NetContentCalculator` posílají do
  `args` symbolické jméno (`G`, `MASS`), ne popisek — klient si hezčí verzi složí sám podle
  vlastního klíče (`enum-labels.ts` `NET_CONTENT_UOM_KEYS` apod.).

REST (`GlobalExceptionHandler`) nese totéž v `ProblemDetail.properties.code`.

## Volba jazyka je na klientovi, server ji zná jen pro asynchronní výstup

`auth.app_user.locale`/`country` existují **výhradně proto, aby server uměl poslat OTP e-mail
ve správném jazyce v době, kdy žádný request neběží** — appka je nepoužívá k žádnému
rozhodování o tom, co klient uvidí. Volba na klientovi (`localStorage` na webu,
`AppCompatDelegate.setApplicationLocales()` na mobilu) je vždy autoritativní a jen se **posílá**
na server (`setLocale` mutace), nikdy se z něj nestahuje zpátky. Tím odpadá celá kategorie „co
vyhraje, když se klient a server neshodnou" — nikdy nemůžou, server o tom nerozhoduje.

`UserAwareLocaleResolver` (`extends AcceptHeaderLocaleResolver`) čte v tomhle pořadí:
`SecurityContextHolder` → uložený `locale` přihlášeného uživatele (Caffeine cache, 5 min TTL,
invalidace v `setLocale`) → `Accept-Language` hlavička → `cs`.

Totéž platí pro `country`, jen s jiným účelem: `searchProducts`/`searchFacets`/`geocodeAddress`/
`companyByIco` (`CountryResolver.resolve`, viz „Country selector v UI" níž) berou explicitní
argument klienta jako AUTORITATIVNÍ, `auth.app_user.country` je jen fallback pro klienty, kteří
argument nepošlou (starší build appky), nikdy zdroj pravdy — přesně stejné rozdělení
zodpovědnosti jako u `locale`/`setLocale` výš, žádná výjimka z pravidla „server o volbě klienta
nerozhoduje".

## Backend

`I18nConfig`: `ResourceBundleMessageSource` nad `messages/{errors,mail,handles,attribution}`,
`fallbackToSystemLocale=false` + `useCodeAsDefaultMessage=false` — chybějící klíč **spadne**,
neprojde tiše jako kód. Základní soubor bez přípony (`errors.properties`) je čeština; `_sk`/
`_en`/`_pl` jsou explicitní varianty. `service/Messages.java` je tenká fasáda nad
`MessageSource` + `LocaleContextHolder`.

**Co se nepřekládá, explicitně:**
- **Logy** — CLAUDE.md, zůstávají česky bez ohledu na tenhle dokument.
- **Technické výjimky**, které uživatel nikdy neuvidí jako lokalizovaný text: GraphQL coercion
  chyby (`GraphQlScalars`), hash/šifrování e-mailu (`EmailCipher`), I/O při ukládání fotky
  (`ImageProcessingService`, `LocalFileSystemMediaStorage`, `MediaController`), neočekávaný JDBC
  typ (`ProductSearchRepositoryImpl`) — regresní pojistka `HardcodedTextTest` je drží jako
  explicitní allowlist, ne že by na ně někdo zapomněl.
- **Slugy, `path`, `osmRef`, kódy enumů** — identifikátory, ne text pro člověka.
- **Atribuce zdroje/licence** (OFF, Nominatim, fotky): uvozující slovo („Zdroj:", „Foto:") se
  lokalizuje, **jméno licence a zdroje je právní text a nepřekládá se** (`messages/attribution*`).

### Multi-měna

Měna ceny se odvozuje **ze `store.country`**, ne posílá klientem — cena je vlastnost
provozovny (`CurrencyResolver.forStore`). Výjimka: `SubmitObservationInput.currency`
(volitelné, pro příhraniční prodejny cenící v jiné měně, než je země obchodu) — server hodnotu
validuje proti podporovaným měnám, neplatnou tiše ignoruje.

`currency` je **součástí primárního klíče** `agg.price_current`/`agg.price_daily`
(`(product_id, store_id, price_kind, currency[, day])`) — jinak by vážený medián mísil CZK/EUR/
PLN do jednoho čísla, tichá datová korupce bez jakékoli chyby při zápisu. Index má `currency`
**před** `unit_price`, jinak dotaz „nejlevnější v CZK" degraduje na range scan místo seeku.

`searchProducts`/`searchFacets` mají povinný `country` filtr (server default: viewerova země →
`Accept-Language` → `app.i18n.default-country`, nikdy „celý svět") — bez něj by řazení podle
ceny řadilo CZK vedle PLN v jednom sloupci. `Product.stats`/`ProductSearchItem` vybírají
**dominantní měnu** (nejvíc `n_obs`) mezi skupinami stejného produktu, ne naivní `.min()` napříč
měnami — to by dovolilo 15 PLN vypadat levněji než 20 CZK jen proto, že číslo je menší.

### Country selector v UI

Země obchodu byla dlouho appce známá (`store.country` → `CurrencyResolver.forStore`), ale
nikde vidět ani volitelná — formulář zakládání obchodu ji posílal natvrdo jako `CZ`, pokud
uživatel nezmáčkl „Použít mou polohu". Slovenský/polský obchod založený z domova se tak uložil
jako český, a všem jeho budoucím cenám se navěky dosadila CZK. Řeší:

- **`Query.countries`** (`CountryResolver.supportedCountries`) — číselník z `app.i18n.
  country-currency`/`country-locale`, jeden zdroj pravdy pro klienty, kteří dřív CZ/SK/PL
  hardcodovali na několika místech nezávisle. Pole `name` je lokalizovaný název země podle
  jazyka aktuálního requestu (`messages/countries*.properties`, stejný vzorec jako `errors`/
  `handles` — chybějící klíč pro novou zemi tvrdě spadne, ne že by appka nový kód zobrazila bez
  názvu). Web dnes pro popisek v Nastavení radši drží vlastní Transloco klíče
  (`settings.country.*`, `settings-page.ts#countryOptionLabel`, s fallbackem na kód země) než
  `CountryInfo.name` — obojí musí zůstat v souladu, dokud se to nesjednotí.
- 16 zemí (plán expanze, 2026-08): CZ/SK/PL a dalších 13 (DE/AT/FR/ES/IT/HR/SI/BG/HU/RO/GB/
  CH/RS) — viz „Jazyky a měny" výš pro rozdělení, které jsou nové kvůli zemi a které kvůli
  jazyku.
- **`CreateStoreInput.country` nemá literální default `"CZ"`** ve schématu — `StoreService.
  create` dosadí zemi vieweru přes `CountryResolver.resolve` (explicit → `app_user.country` →
  `app.i18n.default-country`), stejné pořadí jako `searchProducts` výš.
- **Oprava země existujícího obchodu obchází `store_user_edit`.** Na rozdíl od zbytku
  `updateStore` (patch nad soukromou vrstvou, vidí ho jen autor, dokud neproběhne konsolidační
  job — `docs/datovy-model.md`, „Uživatelská vrstva nad globálními daty") má `country` tvrdý
  dopad na měnu zápisu a validaci IČO/NIP pro VŠECHNY uživatele, ne jen na to, jak provozovnu
  vidí autor patche. `CatalogEditService.updateStore` ji proto zapisuje přímo do spravované
  entity `core.store`, gatováno `TrustLevelService.isTrusted` (`ErrorCode.
  STORE_COUNTRY_EDIT_REQUIRES_TRUST`) — nedůvěryhodný autor nemůže „přebarvit" obchod všem
  ostatním jedním klikem. `store_user_edit.country` byl proto zrušen (migrace
  `2026-08-16/01-store-country.yaml`), byl by navždy nepoužívaný sloupec.
- **`uq_store_identity` má `country` jako první sloupec indexu** (stejná migrace) — dřív dvě
  stejnojmenné provozovny ve stejnojmenném městě ve dvou zemích kolidovaly, protože zemi index
  vůbec nezohledňoval.
- **Inverze `currency → country` NEEXISTUJE a nesmí vzniknout.** Mapa je jednosměrná,
  `CurrencyResolver.isSupported`/`CountryResolver.isSupported` testují jen „je tahle hodnota
  v `country-currency`", nikdy nevrací zemi zpátky z měny. Tohle přestalo být teoretické plánem
  expanze (2026-08) — 9 z 13 nových zemí je EUR, takže `country-currency` dnes mapuje EUR na
  10 různých zemí (SK, DE, AT, FR, ES, IT, HR, SI, BG a další). Cokoli, co by se pokusilo měnu
  na zemi převést zpátky, by bylo nejednoznačné a tiše špatné — `agg.price_current`/
  `agg.price_daily` mají v PK jen `currency`, ne `country`, a `uq_store_identity` výš zemi řeší
  přes `core.store.country`, ne přes měnu.
- Web (`CountryService`, `services/country-service.ts`) i mobil (`CountryStore`,
  `ui/settings/CountryStore.kt`) drží preferenci lokálně (localStorage/SharedPreferences) a
  posílají ji `setLocale` mutací jen když je uživatel přihlášený — stejný vzor jako zbytek téhle
  kapitoly, appka se ze serveru nikdy nestahuje zpátky. Ovlivňuje výchozí zemi formuláře obchodu
  a `country` filtr hledání; `shared/store-label.ts`/`ui/common/StoreLabel.kt` přilepí kód země
  k názvu obchodu jen když se liší od zvolené domácí země.

### Kurzovní lístek a zobrazovací měna

Doplněk k multi-měně výš: appka umí cenu **zobrazit** přepočtenou do jiné měny, aniž by to
jakkoli měnilo, v čem se cena **hlásí** (pořád jen CZK/EUR/PLN podle `store.country`). Kurzy
stahuje `ExchangeRateSyncService` denně z veřejného API ČNB (`https://api.cnb.cz/cnbapi`) do
vlastního schématu `fx.exchange_rate` — vedle `core`/`agg`, ne v nich, protože je to externí,
kdykoli znovu stažitelná data (stejný důvod jako `off`/`osm`), ale na rozdíl od nich appka do
`fx.*` sama **píše** (plánovaná úloha uvnitř appky, ne read-only sync cizích dat).

- **CZK je pivot, ne řádek v tabulce.** ČNB kótuje kurzy vůči koruně, `fx.exchange_rate` proto
  CZK vůbec neobsahuje — křížový kurz (`FxRateService.convert`) jde vždy `from → CZK → to`.
- **USD je čistě referenční měna pro srovnání napříč zeměmi** — appka v ní cenu zapsat nedovolí.
  `app.fx.display-currencies` (CZK/EUR/PLN/USD) je proto úmyslně JINÝ seznam než
  `app.i18n.country-currency` (jen CZK/EUR/PLN), který zná `CurrencyResolver`/`isSupported` a
  na který je navázaný CHECK constraint `agg.*`/`core.price_observation`. Přidání USD do
  `country-currency` by appce dovolilo zapsat cenu v měně, kterou žádná provozovna nemá.
- **Přepočítává se vždy kurzem PLATNÝM K DATU CENY, nikdy dnešním.** `PricePoint` v grafu
  přepočítává KAŽDÝ bod svým vlastním dnem (`FxRateService.convert(amount, from, to, den)`) —
  jinak by graf vývoje ceny v USD mísil pohyb ceny s pohybem kurzu, ne ukazoval jen tu cenu.
  `PriceCurrent`/`ProductStats.bestPrice`/`MyPrice` používají `lastObservedAt`/`observedAt`.
  ČNB o víkendech a svátcích nepublikuje — `ExchangeRateRepository
  .findTopByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc` proto hledá poslední
  publikovaný lístek **k datu nebo dřívější**, nikdy přesnou rovnost na `rate_date`.
- **Nic z přepočtu se neukládá do `agg.*`.** Protože `currency` je už součástí PK
  `agg.price_current`/`agg.price_daily`, je každý řádek jednoměnový a medián je invariantní
  vůči lineární transformaci — vynásobení uloženého mediánu jedním kurzem je matematicky
  totéž jako přepočíst všechny observace a medián spočítat znovu. Přidání sloupce s převedenou
  částkou by se muselo přepočítávat při každém novém kurzovním lístku (denně, celá historie);
  přepočet proto patří výhradně do čtecí cesty (`GraphQL` typ `ConvertedPrice`, pole `converted`/
  `bestPriceConverted`/`convertedUnit`/`convertedUnitPrice` — `null`, když se nepřepočítalo).
- **Zobrazovací měna jde hlavičkou `X-Display-Currency`, ne argumentem dotazu.** Je to
  preference VIEWERA napříč celým dotazem, stejně jako `Accept-Language`
  (`UserAwareLocaleResolver`) — a `@BatchMapping` pole (`Product.prices`/`myPrices`) argumenty
  na poli stejně nepodporují. `DisplayCurrencyInterceptor` (`WebGraphQlInterceptor`) ji čte do
  `GraphQLContext` pod klíčem `displayCurrency`, odkud si ji resolvery berou přes
  `@ContextValue(required = false)`. Neplatná/nepodporovaná hodnota se tiše ignoruje — stejný
  vzorec jako `SubmitObservationInput.currency`. Web (`DisplayCurrencyService` + funkcionální
  interceptor) i mobil (`ui/settings/DisplayCurrencyStore` + `DisplayCurrencyInterceptor` na
  sdíleném `OkHttpClient`) drží volbu jen lokálně (localStorage/SharedPreferences) — server o ní
  neví nic mimo hlavičku jednotlivého requestu, `null`/chybějící hlavička = „měna obchodu"
  (výchozí), appka pak nic nepřepočítává.
- **Backfill při prázdné `fx.exchange_rate`** (`ExchangeRateSyncService`) se odvodí od
  `min(core.price_observation.observed_at)`, zarovná na začátek roku a omezí
  `app.fx.max-backfill-years` zpátky — appka tak nestahuje víc historie, než kolik má vlastních
  cen. Katalog s cenami zapsanými do minulosti (`observedAt` v `SubmitObservationInput`) proto
  spolehlivě dostane kurz i pro zpětně dopsaný den.
- **Víc zdrojů kurzu** (plán expanze o 13 zemí, 2026-08): `ExchangeRateSyncService` injektuje
  `List<ExchangeRateSource>`, ne jeden zdroj — `CnbRateSource` pro drtivou většinu měn a
  `NbsRateSource` (Národní banka Srbska, `app.external.nbs`) pro RSD, které ČNB na lístku nemá
  (ověřeno živě proti `api.cnb.cz` při plánování). Zdroje se dotazují nezávisle a jejich řádky
  se jen sloučí — výpadek jednoho (nebo chybějící `NBS_API_KEY`, který appka bez licence nemá)
  nezablokuje uložení řádků od ostatních, stejný vzor jako `CompanyIdValidators`/
  `CompanyRegistries` u registrů IČO. Každý řádek `fx.exchange_rate.source` nese, odkud přišel
  (`CNB`/`NBS`). **NBS na rozdíl od ČNB vyžaduje registraci a API klíč** a nemá veřejný sandbox —
  `NbsRateSource` proto NENÍ ověřený proti reálné odpovědi (na rozdíl od `CnbRateSource`,
  otestovaného živým `curl`), tvar požadavku je zdokumentovaný odhad, který je potřeba opravit
  po získání licence, než půjde appka s RSD do provozu.

### Kategorie: `core.category_i18n`, ne klíče v bundlech

Kategorie jsou **data, ne UI chrome** — rostou (od `2026-08-20/01-category-tree.yaml` ~106
položek, šest kořenů, max tři úrovně; startovní seed z `2026-08-19` měl jen 24). Klíče v
klientských bundlech by každou novou kategorii vázaly na koordinované vydání webu i mobilu;
tabulka `PK (category_id, locale)` řeší přidání jako jeden `INSERT`. `core.category.name`
zůstává zdrojová čeština a fallback (`COALESCE(i18n.name, category.name)`). **`slug`/`path` se
nepřekládají** — `path` slouží k filtrování podle větve stromu a musí být napříč jazyky totožná.

**Řazení je čtecí odpovědnost klienta, ne SQL.** `Query.categories` vrací plochý seznam řazený
podle `path` (`CategoryRepository.findAllByOrderByPathAsc`, abecedně podle slugu) — `ORDER BY`
podle přeloženého `name` v SQL nejde vůbec sestavit, protože lokalizace se skládá až za
dotazem (`ProductGraphQlController.categoryName`, `@BatchMapping`). Backend proto navíc posílá
`Category.sortOrder` (kurátorské pořadí SOUROZENCŮ v jedné větvi) a klient si strom poskládá a
seřadí sám přes `Intl.Collator`/`java.text.Collator` podle aktuálního jazyka appky — `č`/`ř`/`ł`
tak řadí správně nezávisle na tom, v jaké collation běží Postgres (web `shared/category-tree.ts`,
mobil `ui/common/CategoryTree.kt`).

**Hledání podle kategorie matchuje pod zobrazovaným názvem, ne pod syrovým `core.category.name`.**
`ProductSearchRepositoryImpl` (CTE `cat_hit`) skládá `COALESCE(i18n.name, category.name)` pro
`locale` requestu — týž výraz jako `@BatchMapping Category.name` výš — plus nepřekládaný `slug`,
aby „mleko" našlo kategorii Mléko i v anglicky přepnuté appce. Bez tohohle by hledání mohlo
trefit kategorii pod názvem, který uživatel v odpovědi vůbec nevidí. Diakritika je na obou
stranách (název zboží i kategorie) sjednocená přes `core.norm_text` — fulltext nad
`core.product.name` proto od `2026-08-26/03-product-name-norm-fts.yaml` běží nad
`to_tsvector('simple', core.norm_text(name))`, ne nad syrovým `name` jako dřív.

**Rozšíření číselníku** (další jazyk, další kategorie): oba CSV soubory pod
`backend/.../db/changelog/2026-08-20/` (`category.csv` — `path,slug,parent_slug,name,sort_order`;
`category-i18n.csv` — `slug,locale,name`, BEZ řádků pro `cs`) jsou čitelný zdroj pravdy celého
stromu — doplnit řádky a přidat další Liquibase changeset ve stejném vzoru (staging tabulky →
`INSERT ... ON CONFLICT DO UPDATE` po úrovních stromu → úklid). `CategorySeedIntegrationTest`
(Testcontainers) hlídá, že každá kategorie má `path` odpovídající řetězci rodičů a překlad pro
**každý** jazyk z `app.i18n.supported-locales` mimo `cs` — přidání jazyka bez odpovídajících
řádků v `category-i18n.csv` shodí testy, ne až tichý pád na český fallback za běhu.

### Handle: strukturovaně kvůli gramatickému rodu

„Modrý čáp" vs. „Modrá liška" — v cs/sk/pl (a od etapy 2 i `de`) se přídavné jméno ohýbá podle
rodu podstatného, takže appka neukládá hotový řetězec, ale `handle_adjective`/`handle_noun`/
`handle_number` + `Gender` (`HandleGenerator`). `public_handle` zůstává kanonický, jazykově
neutrální klíč (`blue-stork-4271`) pro unikátnost — ta se musí kontrolovat nad kanonickým
tvarem, jinak by se dva účty srazily v jednom jazyce a v jiném ne. Vykreslení podle jazyka
čtenáře je až na čtení (`ViewerGraphQlController`, `messages/handles*.properties`:
`handle.adjective.blue.M/F/N`). Němčina navíc potvrdila, že `handle.format={0} {1} #{2}`
(přídavné jméno před podstatným) sedí i mimo slovanské jazyky — všech dvanáct
zvířecích podstatných jmen v `HandleGenerator` vyšlo v němčině rodu mužského stejně jako v
češtině (der Storch, der Dachs, ...), takže žádný nový jazyk zatím nevyžadoval přepsat
`handle.format` (to by potřebovaly teprve románské jazyky s přídavným jménem za podstatným,
viz plán expanze).

### IČO/NIP per zemi

`CompanyIdValidator` (rozhraní) + `IcoValidator`/`SkIcoValidator` (mod-11, 8 číslic — společné
dědictví ČSFR) / `PlNipValidator` (NIP, 10 číslic, jiné váhy) — `CompanyIdValidators` vybere
podle `country`; země bez validátoru hodnotu **uloží bez kontroly** (lepší než ji odmítnout).
Pole `ico` v GraphQL **se kvůli historii nepřejmenovává**, i když nese IČO i NIP — popisek v UI
je per-country klíč (`store.companyId.label.{CZ,SK,PL}`), ne odvozený z názvu pole.
`CompanyRegistry`/`AresService` jsou analogicky jen pro CZ — klienti schovávají tlačítko „Načíst
z registru" tam, kde `CompanyRegistries.forCountry(country)` nic nevrátí.

## Frontend (Angular + Transloco)

Rozhodnutí (ne `@angular/localize`): runtime přepínání beze změny buildu, ICU plurály fungují
i v TS kódu, ne jen v šablonách, jeden build bez per-locale nasazení.

**`LOCALE_ID`/`provideNzI18n` se vyhodnocují jen JEDNOU při bootstrapu** — proto:
- `registerLocaleData` eagerně pro všech pět jazyků (data jsou jednotky kB, lazy `import()`
  by přineslo blikání) — nad ~6 jazyků zvážit přechod na lazy, viz plán expanze.
- **`CurrencyPipe`/`DatePipe`/`DecimalPipe` se v projektu nepoužívají.** Formátování jde přes
  `services/format-service.ts` nad `Intl.*`, který čte jazyk ze signálu `LanguageService.lang`
  a měnu z DAT (multi-měna výš) — `CurrencyPipe` s pevnou měnou by byl špatně i bez i18n.
  `shared/money.pipe.ts` je schválně `pure: false` (jazyk je signál, ne input).
- ng-zorro se přepíná za běhu přes `NzI18nService.setLocale()`.

`LanguageService.setLang()`: nejdřív `await transloco.load(lang)`, až pak přepnutí signálu (ať
UI na zlomek vteřiny nespadne do prázdna), pak `document.documentElement.lang`, `localStorage`,
a push na server (`ViewerService.setLocale`, chyba requestu appku neblokuje — volba klienta je
platná bez ohledu na to, jestli se stihla uložit). Počáteční jazyk: `localStorage` →
`navigator.languages` ∩ podporované → `cs`. `CountryService` (`services/country-service.ts`) je
stejný vzor pro zemi — nezávislá volba v Nastavení, viz „Country selector v UI" výš.

**Struktura bundlů**: `public/i18n/{cs,sk,en,pl,de}.json` (kořen: `common`/`nav`/`errors`/`enum`) +
`public/i18n/<scope>/{cs,sk,en,pl,de}.json` na stránku/komponentu (`provideTranslocoScope`,
staženo spolu s lazy chunkem route). Komponenta vložená do víc stránek (galerie fotek, mapa) má
vlastní scope přímo na sobě, ne závislý na tom, která stránka ji zrovna použije. Klíč v kódu
**vždy nese celý prefix scope**, i uvnitř `*transloco="let t; scope: '…'"` (`t('profile.title')`,
ne `t('title')`) — Transloco po sloučení do jazyka ukládá klíče scope bundlu pod tímhle
prefixem, ne holé. Pro scopy s pomlčkou (`price-entry`, `product-detail`, `product-form`) na tom
závisí i `scopes: { keepCasing: true }` v `app.config.ts` — bez něj by se alias scope
camelCasoval (`price-entry` → `priceEntry`) a klíč z kódu by se s bundlem minul, aniž by o tom
`i18n.spec.ts` věděl (hlídá jen obsah bundlů, ne to, jaký klíč si o ně kód řekne — na to je
`i18n-keys.spec.ts` níž).

`shared/relative-date.ts` jde přes `Intl.RelativeTimeFormat` (plurály i „včera" řeší platforma
sama), `@jsverse/transloco-messageformat` (ICU) je jen tam, kde platformní API nepomůže
(`{count, plural, one {...} few {...} many {...} other {...}}` pro počty dní/záznamů).

**Routy jsou anglické, české jsou jen redirecty** (`produkt/:id` → `product/:id` apod.) —
lokalizované routy per jazyk by znamenaly čtyři sady definic nebo vlastní `UrlSerializer`, a
zisk (SEO) je dnes nulový (appka nemá SSR). Až SSR přijde, lokalizované aliasy se přidají jako
**aditivum**, ne náhrada za tohle rozhodnutí.

## Mobil (Kotlin/Compose)

`values/` = **čeština**, zdroj i fallback — stejný důvod jako u backendu výš. `values-{sk,en,
pl}/` vedle ní. `android:localeConfig` (`res/xml/locales_config.xml`) + `androidx.appcompat`
**jen kvůli** `AppCompatDelegate.setApplicationLocales()` — per-app picker v systémovém
nastavení je až od API 33 (appka má `minSdk 26`), AppCompat pod tím drží volbu sama
(`AppLocalesMetadataHolderService` s `autoStoreLocales` v manifestu). `MainActivity` musí být
`AppCompatActivity` (ne `ComponentActivity`) — `setApplicationLocales()` shání cíl přes interní
seznam žijících `AppCompatDelegate` instancí, ty vznikají jen uvnitř `AppCompatActivity`; bez
toho je volání no-op na všech API úrovních, appka po přepnutí v Nastavení zůstane ve starém
jazyce. `themes.xml` proto musí mít parent odvozený od `Theme.AppCompat` (ne
`android:Theme.Material*`), jinak `AppCompatActivity` spadne při `setContentView`.

**`UiText`** (`ui/common/UiText.kt`, `Res`/`Plural`/`Raw`) odkládá `stringResource` do Compose
kontextu — ViewModel/síťová vrstva k němu nemá přístup a `context.getString()` přímo z
ViewModelu by po přepnutí jazyka zůstalo viset ve starém textu. `Throwable.toUiText()`
(`ui/common/ErrorMessages.kt`) mapuje `GraphQlAppException` na `Raw(serverMessage)` — appka
zatím netypuje `ErrorCode` ze schématu jako web (chybí codegen pro `network/Dto.kt`, ten se
píše ručně), takže vlastní klientský překlad podle `code` je až budoucí rozšíření; neznámý kód
dostane vždy aspoň lokalizovaný text ze serveru, nikdy ne anglický/český napevno.

`ui/common/Money.kt` (`rememberMoneyFormatter`) čte měnu z dat a jazyk z
`LocalConfiguration.current.locales[0]` — nahrazuje dřívější top-level `NumberFormat
.getCurrencyInstance(Locale("cs","CZ"))`, který by nereagoval na změnu jazyka ani měny.
`ui/common/CompanyId.kt` a `ui/common/CountryCurrency.kt` zrcadlí backendová pravidla pro
UI popisky (IČO vs. NIP, měna podle země obchodu) — server zůstává jediným zdrojem pravdy,
neshoda by způsobila jen dočasně špatný popisek, nikdy špatně uloženou hodnotu.

`AcceptLanguageInterceptor` (sdílený `OkHttpClient` v `AppContainer`) posílá
`Locale.getDefault().language` — `AppCompatDelegate` ho drží v souladu s volbou v Nastavení,
appka tak nemusí nikam tahat `Context` jen kvůli aktuálnímu jazyku.

`CountryStore` (`ui/settings/CountryStore.kt`) zrcadlí webový `CountryService` — nezávislá volba
země v Nastavení, viz „Country selector v UI" výš. Na rozdíl od jazyka appka zemi PO PŘIHLÁŠENÍ
posílá na server (`GraphQlClient.setLocale`) — `LocaleController.setLang()` sám o sobě
`setLocale` nevolá vůbec (viz jeho KDoc), takže push u přepínače země v `SettingsScreen`
posílá aktuální jazyk jen jako povinný doprovodný argument mutace, ne že by appka nově
synchronizovala i jazyk.

## Testy a CI guardy

„Druhá polovina triku": kompilátor hlídá, že klíč z `enum-labels.ts` (`Record<Enum, string>`)
nebo `@StringRes` reference existuje v kódu; testy hlídají, že existuje i v bundlech. Ani jedno
samo o sobě nestačí — kompilátor neví nic o obsahu JSON/XML, testy nevidí, jestli kód klíč
vůbec používá.

| Vrstva | Test | Co hlídá |
|---|---|---|
| Backend | `i18n.MessageBundleTest` | sk/en/pl/de mají přesně stejné klíče jako český základ, každý `ErrorCode` má klíč, počet `{0}`/`{1}` placeholderů sedí napříč jazyky |
| Backend | `i18n.HardcodedTextTest` | žádný nový český text natvrdo ve `throw new *Exception(...)` mimo allowlist technických výjimek |
| Backend | `i18n.GraphQlErrorLocalizationTest` | `extensions.code` je stejný napříč jazyky, `message` se mění podle `Accept-Language` (včetně `de`, `germanIsLocalized`), neznámý jazyk (francouzština — podporovaná ZEMĚ, ne JAZYK) padá na český základ |
| Backend | `service.fx.FxRateServiceTest` | křížový kurz přes CZK jako pivot, kurz k víkendu padá na poslední pátek (ne rovnost na datu), chybějící kurz vrací prázdný `Optional`, ne výjimku |
| Backend | `service.fx.ExchangeRateSyncServiceTest` | backfill se odvodí z `min(observed_at)` a je omezen `max-backfill-years`, druhý běh nezaloží duplicity, sledují se jen `app.fx.tracked-currencies`, víc zdrojů (ČNB + NBS) se sloučí a každý řádek si nese svůj `source` |
| Backend | `service.PriceHistoryServiceTest` | dva dny grafu s různými kurzy dají dva různě přepočtené body (ne jeden dnešní kurz pro celou řadu) |
| Frontend | `i18n.spec.ts` | každý scope bundle má ve sk/en/pl/de stejné klíče jako cs, interpolační parametry sedí, žádná hodnota není prázdná/rovná klíči, všech deset `*_KEYS` z `enum-labels.ts` existuje ve všech pěti jazycích |
| Frontend | `i18n-keys.spec.ts` | každý literálový klíč použitý v kódu (`t('…')`, `translate('…')`, `'…' \| transloco`) existuje v cs bundlu pod svým scope prefixem — chytá chybějící prefix v šabloně i rozjetý alias scope, ne jen neúplný bundle |
| Frontend | `no-hardcoded-text.spec.ts` | česká diakritika ve statickém textovém uzlu nebo statickém atributu (`placeholder`, `nzTitle`, `alt`, ...) v libovolné šabloně |
| Mobil | lint (`MissingTranslation`/`ExtraTranslation`/`MissingQuantity` jako error) | `values-*/` nezaostávají za `values/`, `<plurals>` mají všechny tvary daného jazyka |
| Mobil | `i18n.HardcodedTextTest` | žádný nový český string literál v `src/main/java` mimo allowlist (endonyma, technické `TransportException` zprávy) |

CI (`.github/workflows/ci.yml`) spouští všechno automaticky — backend přes `./gradlew build`,
frontend přes `npm test`, mobil přes `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
(dřív jen `assembleDebug`, testy a lint se v CI vůbec nespouštěly).

## Co zbývá (mimo rozsah téhle práce)

- **Skutečný per-kódu klientský překlad chyb na mobilu** — appka zatím vždy ukáže
  `serverMessage` (lokalizovaný, ale ne appkou doladěný), protože `network/Dto.kt` negeneruje
  typy ze schématu jako web (`ERROR_CODE_KEYS`). Vyžadovalo by to buď codegen pro Kotlin, nebo
  ruční `Map<String, Int>` udržovanou v synchronizaci s `ErrorCode` enumem.
- **Skutečné lidské revize strojových překladů** sk/en/pl/de — psané s péčí a gramaticky, ale
  bez rodilého mluvčího na kontrolu.
- **Vlna expanze 3+** — další jazyky nad `de` podle poptávky, stejným postupem. Pozor na
  slovinštinu (jediná ze 13 nových zemí, jejíž případný budoucí jazyk `sl` má v ICU pluralech
  4 tvary včetně duálu — MessageFormat/`@jsverse/transloco-messageformat` to zvládnou, ale žádný
  dosavadní jazyk appky tenhle tvar nevyžadoval, takže by šlo o první ověření naživo) a na
  románské jazyky (fr/es/it) — jejich přídavné jméno stojí za podstatným, takže `handle.format`
  by se pro ně muselo přepsat na `{1} {0} #{2}`, na rozdíl od `de`, které vystačilo se stejným
  pořadím jako čeština.
