# Soukromí

Cíl aplikace není sledovat, kdo kde nakupuje — cílem je, aby co nejvíc lidí mělo přehled
o cenách. To je v přímém napětí s tím, že reputační systém potřebuje vazbu příspěvek →
uživatel. Tento dokument shrnuje, jak se ten rozpor řeší v datovém modelu, ne až v UI.

## Poloha se nikdy neukládá jako GPS uživatele

Mobil zjistí polohu lokálně a pošle **dotaz** `nearbyStores(lat, lon, radius)` — GraphQL
query, ne mutace. Klient posílá syrovou hodnotu z geolokace; **zaokrouhlení na 3 desetinná
místa (~110 m) dělá server** (`StoreGraphQlController.nearbyStores`, `Coordinates.round`) —
při rádiusu 3–25 km je to pod rozlišovací schopnost výsledku. Server odpoví seznamem
provozoven a **souřadnice nikam nezapisuje** — ani do access logu (GraphQL má vše v POST
body, to se neloguje). S cenovým záznamem (`price_observation`) odchází jen `store_id`.

Souřadnice **provozovny** (`core.store.lat/lon`) jsou veřejný fakt, ne osobní údaj —
je to totéž, co je na ceduli u vchodu. Osobní údaj by vznikl teprve spojením souřadnic
s konkrétním uživatelem a časem, a to se v datovém modelu nikde neděje.

Stejné pravidlo platí pro **geokódování adresy při zakládání obchodu** (`geocodeAddress`,
backend `GeocodingService`): dotaz na OpenStreetMap Nominatim jde VŽDY ze serveru, nikdy
z mobilu nebo z prohlížeče — kdyby appka volala Nominatim přímo z klienta, šla by mu přímo
IP uživatele (a u mobilu i síťové metadata operátora), přesně to, čemu se `nearbyStores`
výše vyhýbá. Odpověď se do `core.store` nekopíruje celá — jen lat/lon a `osm_ref`
**zvoleného** kandidáta, s `geo_source = 'OSM'` jako značkou původu.

**Opačný směr (`reverseGeocode`, tlačítko „Použít mou polohu" při editaci obchodu) platí
stejně** — parametrem je tady rovnou poloha UŽIVATELE, ne adresa obchodu, takže je pravidlo
„jen ze serveru" o to důležitější. Dotaz jde výhradně z `GeocodingService`, souřadnice se
nikam nezapisují a jsou v POST body, které se neloguje. Server navíc souřadnice před dotazem
na Nominatim zaokrouhlí na 4 desetinná místa (~11 m, `Coordinates.round`) — hluboko pod
přesností mobilního GPS fixu, takže se nic reálného neztrácí, jen zmizí falešná sub-metrová
přesnost posílaná třetí straně. **Klienti souřadnici posílají syrovou** — tu samou hodnotu
totiž zároveň používají jako souřadnici PROVOZOVNY (`manualLat`/`manualLon` → `core.store`),
tu zaokrouhlit nesmí (viz výš). Výpadek Nominatimu vrací prázdná pole, nikdy chybu — editace
obchodu nesmí spadnout kvůli nedostupnému externímu serveru.

Zaokrouhlení samo o sobě soukromí moc nepřidá — na budovu se trefí 11 i 110 m a Nominatim
navíc vidí IP **serveru**, ne uživatele, což je ta skutečná ochrana. Přínos je jinde:
nepředstírat třetí straně přesnost, kterou appka ani nemá, a mít zaokrouhlenou hodnotu jako
konzistentní cache klíč.

### EXIF nahrané fotky se strhává na serveru, ne spoléhá na klienta

Fotka z mobilu v EXIF běžně nese přesnou GPS polohu, kde vznikla — přijetí souboru tak, jak
přišel, by tiše prolomilo záruku „poloha uživatele se nikdy neukládá" výš, jen jinou cestou
(souborem na disku místo databázového sloupce). `ImageProcessingService` (backend) to řeší
**překreslením z pixelů**: nahraná fotka se dekóduje do `BufferedImage` a znovu zapíše jako
JPEG — výstup tak nikdy neměl žádná metadata, natož EXIF GPS, protože vzniká z pixelů, ne
kopírováním/úpravou vstupního souboru. Je to vědomě jiný přístup než „smaž EXIF tagy" (selektivní
mazání by šlo zapomenout rozšířit o nově přidaný tag); tady výstup prostě nemá kudy metadata
dostat. EXIF `Orientation` se přečte a promítne do otočení PŘED tímhle překreslením, aby fotka
z mobilu nezůstala ležet na boku.

### Mapové dlaždice — vědomá výjimka z pravidla „jen ze serveru"

Mapa nad OpenStreetMap (`frontend/shared/map-tiles.ts` + `shared/location-map.ts` — Leaflet,
`mobile/ui/common/MapConfig.kt` + `OsmMapView.kt` — osmdroid) na rozdíl od geokódování stahuje
dlaždice **přímo z prohlížeče/appky** (výchozí poskytovatel `tile.openstreetmap.org`,
konfigurovatelný — na mobilu přes `KVALITACENA_MAP_TILE_URL`, na webu zatím jen editací
`map-tiles.ts`) — OSM (nebo jiný nakonfigurovaný poskytovatel) tak vidí IP uživatele, přesně
to, čemu se `geocodeAddress`/`reverseGeocode`/`nearbyStores` výš vyhýbají. Proxování dlaždic
přes backend by šlo — oficiální OSM tile usage policy proxy nedoporučuje, ale nezakazuje ji,
za podmínek správného cacheování a identifikace — appka se do toho zatím nepouští, protože by
to znamenalo provozovat vlastní cache vrstvu navíc. Zmírnění je proto jen v UI: mapa (a tedy
i stahování dlaždic) se vytvoří až po explicitním kliknutí na „Zobrazit mapu", nikdy
automaticky při načtení stránky/obrazovky — na rozdíl od zbytku dokumentu tahle výjimka není
beze zbytku vyřešená, jen vědomě přijatá a zapsaná, ať se na ni nezapomene při případné budoucí
revizi. Atribuce poskytovatele je v mapě samotné na obou klientech (web: roh Leaflet mapy;
mobil: `CopyrightOverlay`, čte se z aktivního tile source, takže se s výměnou poskytovatele
mění sama) — to je jiná věc než atribuce OSM/ODbL DAT (souřadnice provozoven, geokódování),
která zůstává v „O aplikaci"/podmínkách i po výměně tile serveru.

Tlačítko „Na mou polohu" (mobil, `ui/common/OsmMapView.kt` — `MyLocationButton`, jen
v editovatelném `LocationMap`) tuhle výjimku o kousek zostřuje: appka mapu vycentruje přímo na
uživatele, takže dlaždice, které si klient stáhne, prozradí OSM jeho okolí přesněji než dřív
(kdy appka poslala jen tolik dlaždic, kolik uživatel sám odscrolloval/oddoomoval od výchozího
středu ČR). Poloha samotná jde přes stejný jednorázový `getCurrentLocation` jako „Použít mou
polohu"/„Najít v okolí" jinde v appce (žádný průběžný odběr, nikam se neukládá) — mapa se
značkami obchodů (`StoreMap.kt`) souřadnice uživatele naopak vůbec nevidí, výřez je jen
z bounding boxu obchodů.

### Lokální AI (plánováno) je opačný případ než mapové dlaždice

U dlaždic výš appka vědomě připouští, že OSM uvidí IP uživatele. Plánovaná AI (`docs/ai.md`) — čtení
čísel z fotek, předfiltr moderace, kontrola textů — jde přesně opačným směrem: model běží u
provozovatele appky (lokální PC), takže fotky ani texty uživatelů neopouští appku vůbec, žádné
třetí straně. Verdikt je navíc vždy jen poradní, nikdy sám nerozhoduje — viz `docs/ai.md`, „AI nikdy
nerozhoduje".

## Retence vazby observace → uživatel: 180 dní

| Fáze | Doba | Co je uloženo |
|---|---|---|
| Aktivní | 0–180 dní | `price_observation.submitter_id` plně |
| Pseudonymizovaná | 180 dní+ | `submitter_id = NULL`, zůstane jen `submitter_cohort` (reputační pásmo) a `frozen_weight` |

180 dní je nejdelší okno, které potřebuje detekce anomálií a řešení sporů o cenu. Reputace
tím netrpí, protože se počítá jako průběžně aktualizovaný čítač s exponenciálním útlumem
(viz `docs/reputace.md`), ne z historie jednotlivých událostí — smazání vazby na starou observaci
tedy reputaci nijak nemění.

Pro uživatele to znamená: „moje příspěvky" ukazují jen posledních 180 dní. To je vlastnost,
ne omezení — starší nákupy už o něm nikdo nedohledá.

Stejné okno platí pro `core.product_alias_confirmation.user_id`: potvrzení varianty názvu
vzniká jen s cenovou observací registrovaného uživatele, denní job po 180 dnech vazbu v obou
tabulkách nuluje a `ON DELETE SET NULL` totéž zajistí při smazání účtu. Samotný alias a jeho
stav zůstávají jako sdílený katalogový údaj; po pseudonymizaci už z nich nejde určit, kdo jej
potvrdil. Poslední obchod, který formulář předvyplní, server jako historii vůbec neukládá — web
i mobil si drží jen jeho ID lokálně v zařízení a po 30 dnech je smažou.

### Výjimka: hodnocení kvality zboží vazbu nepseudonymizuje

`core.product_review.user_id` (hvězdičky 1–5, volitelně text — viz `docs/datovy-model.md` a
`docs/reputace.md`) je jediné místo v `core.*`, kde tohle pravidlo neplatí. Bez trvalé vazby by
nešlo vynutit „jedno hodnocení na uživatele a produkt" (unikátní index `(product_id, user_id)`).
Je to vědomé zhoršení, ne přehlédnutí — zmírněné třemi věcmi:

- **Ven přes API jde jen agregát hvězdiček** (`ProductQuality.average`/`count`), nikdy seznam
  „kdo co ohodnotil" — `user_id` se z DB nedostane ven ani nepřímo. **U TEXTU platí od podepsané
  recenze výjimka z výjimky, viz „Podepsaná recenze" níž** — `authorPublicUid`/`authorName` jde
  ven záměrně, `user_id` (databázové) stále ne.
- **`ON DELETE CASCADE`, ne `SET NULL`** — smazání účtu hodnocení (hvězdičky i text) rovnou
  odstraní, na rozdíl od `price_observation.submitter_id`, kde observace zůstávají jako
  pseudonymizovaná statistika ve veřejném zájmu. Hodnocení bez vlastníka nemá tenhle veřejný
  zájem, který by odůvodnil přežití záznamu po smazání účtu.
- **`pg_dump --schema=core` musí sloupec vynechat nebo hashovat** — jinak „čistý" export
  (`docs/datovy-model.md`) tiše prolomí záruku z tohoto dokumentu. GDPR export (`GET
  /api/me/export`, `AccountService.exportData`) hodnocení kvality včetně textu obsahuje,
  výmaz (`POST /api/me/delete` níže) ho kaskádou maže spolu s účtem.

### Podepsaná recenze — první veřejný typ s viditelným autorem

Recenze je **první místo v celé appce, kde autor vyleze z API na veřejném typu** — `Query
.productReviews`/`Query.myReviews` vrací `authorPublicUid`/`authorName`
(`PublicNameRenderer`) u KAŽDÉHO čtenáře, ne jen moderátorovi. Dosud platilo, že autor se
objevuje výhradně v moderátorském pohledu (`FlaggedRecordItem.authorHandle`), gatovaný rolí
(„Identita bez osobních údajů" výš) — nepodepsaná recenze by ale byla nedůvěryhodná (kdokoli by
mohl psát pod cizí jméno bez možnosti ověřit, kdo skutečně píše), takže tohle rozhodnutí je
vědomá, ne přehlédnutá výjimka. Zmírněná stejně jako zbytek identity v appce:

- **Nikdy databázové `id`, vždy `public_uid`** — stejné pravidlo jako všude jinde v API, autora
  recenze nejde počítat ani hádat podle sekvenčního čísla.
- **`authorName` je vykreslený handle nebo veřejná přezdívka, nikdy reálné jméno** — má-li autor
  vyplněnou přezdívku (`display_name`) A je viditelná podle profilové matice
  (`UserProfileService.isFieldVisible`, „Profil uživatele a viditelnost" níž), appka ukáže
  „{přezdívka} #{handle_number}" (číslo dolepené kvůli unikátnosti — dva uživatelé se stejnou
  přezdívkou jdou u recenzí odlišit); jinak lokalizovaný handle jako všude jinde.
- **Vykreslení je vždy server-side** (`PublicNameRenderer`) podle jazyka ČTENÁŘE, ne autora —
  klient handle skládat nesmí (`docs/lokalizace.md`, „Handle: strukturovaně kvůli gramatickému
  rodu").
- **Smazání účtu recenzi (hvězdičky i text) odstraní kaskádou** — na rozdíl od
  `FlaggedRecordItem.authorHandle`, kde smazaný autor prostě zmizí z pole (`authorPublicUid`
  je tam nullable), veřejná recenze bez majitele nedává smysl a zmizí celá.

### Druhá výjimka: uživatelská vrstva nad globálními daty vazbu nepseudonymizuje

`core.product_user_edit`/`core.store_user_edit.user_id` (MVP — viz `docs/datovy-model.md`,
"Uživatelská vrstva nad globálními daty") je druhé místo v `core.*`, kde 180denní pravidlo
neplatí. Bez trvalé vazby by uživateli po půl roce tiše zmizely jeho vlastní opravy (název,
gramáž, adresa) — patch by se přestal zobrazovat, protože ho backend neumí spárovat s
žádným viewerem. Zmírněné stejně jako u `product_review` výš:

- **Ven přes API jde jen efektivní hodnota** (globální nebo přepsaná patchem, podle toho, kdo
  se ptá) — seznam "kdo co upravil" v API neexistuje.
- **`ON DELETE CASCADE`** — smazání účtu patch rovnou odstraní, záznam se vrátí na globální
  hodnotu. Stejná úvaha jako u `product_review`: uživatelova pracovní data nemají po
  smazání účtu veřejný zájem, který by zdůvodnil přežití (na rozdíl od `price_observation`).
- **`pg_dump --schema=core` musí sloupec `user_id` vynechat nebo hashovat** stejně jako u
  `product_review.user_id` — jinak "čistý" export tiše prolomí tuhle záruku.
- Až vznikne skutečný GDPR export/výmaz (níže), vlastní patche do něj patří stejně jako
  cenové záznamy a hodnocení kvality.

## Identita bez osobních údajů

`auth.app_user` samo nemá pole pro jméno, adresu ani telefon — je to vždy "identita bez
osobních údajů" a smazání účtu (nebo jen profilu) je jeden `DELETE`. Osobní údaje, POKUD si
je uživatel dobrovolně vyplní, žijí ve vedlejší tabulce, viz „Profil uživatele a viditelnost"
níže — tohle je vědomá změna dřívějšího tvrzení, že appka pro ně nemá v API místo vůbec.

- `public_uid` (UUID) — používá se v API i jako JWT `sub`, nikdy databázové `id`, aby nešlo
  počítat ani hádat uživatele podle sekvenčního čísla.
- `email_hash` — `HMAC-SHA256(pepper, normalizovaný e-mail)`. Pepper je jen v proměnné
  prostředí, mimo databázi. Slouží výhradně k VYHLEDÁNÍ účtu při přihlášení; e-mail se
  z něj zpět nedá získat.
- `email_enc` — `AES-256-GCM(e-mail)`, klíč taky mimo databázi. Nutné, protože appka musí
  umět e-mail skutečně přečíst (odeslat OTP kód, pozdější notifikace).
- `public_handle` — generovaná výchozí veřejná identita ("Modrý čáp #4271"), aby si lidé
  ze setrvačnosti nedávali skutečné jméno. Volitelně přepsatelné na vlastní přezdívku
  (`display_name`), nikdy ne na reálné jméno.

Únik databázového dumpu bez pepperu a šifrovacího klíče z env tedy neodhalí jediný e-mail
ani žádnou jinou textovou PII v profilu (viz níže) — obojí sdílí stejný AES-256-GCM klíč
(`security/EmailCipher`).

**IČO provozovny (`core.store.ico`) není osobní údaj uživatele appky** — je to identifikátor
z veřejného rejstříku ekonomických subjektů (ARES), fakt o provozovateli obchodu, ne o
člověku, který obchod v appce založil (ten zůstává jen v `store.created_by_user_id`, stejná
pseudonymizační logika jako jinde v `core.*`). Volitelné potvrzení přes `companyByIco` čte
jen z veřejného ARES, nic z appky do ARES neposílá.

**Moderátor vidí autora, nikdy nahlašovatele — dvě různé věci se schválně jiným pravidlem**
(`docs/reputace.md`, „Moderace"):

- **Kdo záznam NAHLÁSIL** (`core.record_flag.user_id`) z API nejde ven ani moderátorovi —
  slouží výhradně k vynucení „jeden hlas na člověka a záznam" (`uq_record_flag_user`), stejně
  jako `product_review.user_id` výš. Nahlášení je vždy o FAKTU, ne o autorovi ani
  o nahlašovateli.
- **Kdo záznam ZALOŽIL/nahrál** (`created_by_user_id`/`uploaded_by_user_id`) moderátor VIDÍ
  jako `authorPublicUid`/`authorHandle` (`FlaggedRecordItem`, `ModerationObservationItem`) —
  bez toho by nešlo uplatnit „Ukončení a vyloučení" z `docs/podminky-uziti.md` (pozastavit účet
  za opakované porušování). Je to `public_uid`/vykreslený `public_handle`, stejná identita jako
  jinde v API, jen tady navíc gatovaná rolí — na veřejných typech (`Product`, `Store`, `Photo`)
  se autor nikde neobjevuje, jen v moderátorském pohledu. **Jediná výjimka je `ProductReview`**
  (viz „Podepsaná recenze" níž) — tam je autor vidět záměrně, každému čtenáři, ne jen
  moderátorovi.

## Profil uživatele a viditelnost

Přihlášený uživatel si smí volitelně vyplnit jméno, příjmení, telefon, kontaktní e-mail
a avatar (`auth.user_profile`, `UserProfileService`, GraphQL `Viewer.profile`/`updateProfile`)
— to vše je vědomá výjimka z předchozí sekce, ne její popření: údaje jsou **nepovinné**,
leží ve **vlastní tabulce** mimo `app_user` a výchozí viditelnost je **`ANONYMOUS`** (vidí je
jen vlastník), takže appka se dál nechová jako appka, co si říká o jméno.

- **Textová PII je šifrovaná stejným AES-256-GCM jako `email_enc`**
  (`EmailCipher.encryptValue`/`decryptValue`) — BEZ normalizace na malá písmena (na rozdíl
  od e-mailu), protože "Jan Novák" a "jan novák" nejsou zaměnitelné jako přihlašovací adresa.
- **Avatar (`core.media`, `RecordType.USER`) šifrovaný NENÍ** — je to binární soubor mimo
  databázi jako ostatní fotky (`ImageProcessingService` z něj i tak strhne EXIF včetně GPS),
  jen s vlastním REST endpointem `POST /api/media/user/avatar` (recordId se bere z
  `Authentication`, nikdy z URL — v API nikdy DB id) a vždy nejvýš jedním záznamem na
  uživatele (nahrazení staré fotky, ne přidání do fronty). Web i mobil to uživateli
  explicitně ukazují (ikona/tooltip u avatara), ať neplatí mlčky jiná záruka než u
  textových polí vedle.
- **`pg_dump --schema=core --schema=agg` musí avatary typu `USER` vynechat** stejně jako
  `user_id` jinde v `core.*` — nejsou to fotky zboží/obchodu s veřejným zájmem na přežití.
- **Viditelnost je dvouúrovňová**: globální `visibility` (`ANONYMOUS`/`PUBLIC`/`FRIENDS`) je
  jediný gate — `ANONYMOUS` blokuje úplně vše, i kdyby matice níž tvrdila jinak. Jinak
  rozhoduje `auth.user_profile_field_visibility` (existence řádku `(user_id, field,
  audience)` = pole je pro to publikum vidět) nezávisle na tom, jestli je celkový režim
  `PUBLIC`, nebo `FRIENDS` — `UserProfileService.isFieldVisible` je jediné místo pravdy.
  Vlastník vidí přes `me`/`Viewer.profile` vždy úplně vše, filtr se na něj neaplikuje.
- **Skupiny důvěry (přátelé) zatím neexistují** (`docs/datovy-model.md`), takže řádky
  s `audience = FRIENDS` se zatím nikdy neuplatní — to je očekávané, ne chyba. Odkazy na
  "seznam přátel", "hodnocení přáteli" apod. v UI jsou zatím jen `Připravujeme` placeholdery
  bez vlastní logiky.
- **Přihlašovací e-mail se v profilu NEMĚNÍ** — `updateProfile` na něj vůbec nesahá. Změna
  jde přes samostatný REST tok `POST /api/auth/email/change/request` + `/confirm`
  (`EmailChangeService`), který znovu použije OTP mechanismus `LoginChallenge`
  (`ChallengePurpose.EMAIL_CHANGE`), ale kód pošle vždy na **novou** adresu — přímé pole ve
  formuláři profilu by šlo překlepem zamknout účet. Odpověď na `request` je STEJNÁ bez
  ohledu na to, jestli je nová adresa volná nebo už patří jinému účtu (stejná pojistka proti
  enumeraci účtů jako u loginu) — liší se jen OBSAH e-mailu, který vidí jen majitel schránky.
  Skutečná pojistka proti převzetí cizí adresy je až v `confirmChange` (kontrola vlastnictví
  natvrdo, ne spoléhání na nerozlišitelnost response). Úspěšné potvrzení inkrementuje
  `token_version` — odhlásí ostatní zařízení, stejný mechanismus jako "podezření na krádež"
  o pár odstavců výš.
- **Smazání účtu smaže i profil** — `auth.user_profile`/`user_profile_field_visibility` mají
  `ON DELETE CASCADE` na `user_id`, žádná ruční čistící úloha (až GDPR výmaz z účtu vznikne,
  viz „GDPR" níže). Avatar (soubor v `MediaStorage`) se maže při nahrazení novým i při
  explicitním `deleteAvatar`, ne až při smazání účtu — sirotčí soubory po smazaném účtu jsou
  známý zbytkový dluh, stejný jako u ostatních fotek v `core.media`.

## Zpětná vazba

`core.feedback` (docs/datovy-model.md, „Zpětná vazba"; kanál sám: docs/nasazeni.md, „Než
pozvat první lidi") je jediné místo v appce, kde se **vědomě opouští** pravidlo „autor
z API nikdy nejde ven" z předchozích sekcí — `FeedbackItem.authorPublicUid`/`authorHandle`
i dešifrovaný `contactEmail` se moderátorovi vrací vždy, protože bez nich by na hlášení
nešlo odpovědět. Nejde o nedopatření, je to jiná věc s jiným pravidlem, přesně jako `record_flag`
schválně skrývá nahlašovatele (`docs/reputace.md`, „Moderace") — u nahlášení jde o hlas proti
faktu, u zpětné vazby o žádost o pomoc, kde je anonymita naopak na škodu. Volitelný kontaktní
e-mail je šifrovaný stejným AES-256-GCM jako zbytek textové PII profilu výš.

**`diagnostics` (volitelný stacktrace posledního pádu appky, jen Android, `crash/CrashReporter.kt`)
se do requestu vloží JEN po výslovné akci uživatele** — checkbox ve `ui/feedback/FeedbackScreen.kt`
má výchozí hodnotu nezaškrtnuto, appka žádný záznam o pádu neposílá sama od sebe ani ho
nikam mimo appku neodesílá bez tohohle kroku. Samotné zachytávání pádu je bez třetí strany
(Sentry/Crashlytics by porušily slib „žádná analytika ani sledovací nástroje třetích stran"
níž) — soubor zůstává jen v `filesDir` appky, dokud ho uživatel sám nepřiloží ke zprávě nebo
appku neodinstaluje.

**Obrana proti spamu (`docs/nasazeni.md`, „Zbývá") ukládá hash ZPRÁVY, nikdy IP.**
`FeedbackSpamDetector` potřebuje poznat opakovanou spamovou zprávu, ale appka nesmí trvale
ukládat IP odesílatele k obsahu — `core.feedback.message_hash` je SHA-256 normalizované zprávy
(dedup za 24 h), IP zůstává jen v paměti `FeedbackRateLimiter` (Caffeine, mizí restartem, stejně
jako u `OtpRateLimiter`). Proof-of-work výzva (`FeedbackChallengeService`, náhrada CAPTCHY,
kterou appka nesmí použít — žádné externí skripty třetí strany) appka vůbec nepersistuje, jen
krátkodobě pamatuje `salt` už vyřešené výzvy, aby nešlo jedno řešení přehrát tisíckrát.

## Passwordless auth (e-mail → OTP kód → token)

Implementace: `security/OtpService.java`, `security/RefreshTokenService.java`,
`security/JwtService.java`, `controller/AuthController.java`.

**Request krok** (`POST /api/auth/otp/request`) vrací vždy stejnou odpověď
(`{challengeUid, expiresInSec, resendAfterSec}`) bez ohledu na to, jestli e-mail patří
existujícímu účtu — účet vzniká JIT až při úspěšném ověření kódu (`OtpService.verifyOtp`).
Nejde tedy postupně zjišťovat, kdo je zaregistrovaný.

Dvě věci dělají 6místný kód bezpečným navzdory malému prostoru (10⁶ možností):

- **Na e-mail existuje vždy nejvýš jedna aktivní výzva** — nová žádost zneplatní
  předchozí (`LoginChallengeRepository.invalidateActiveChallenges`). Bez toho by šlo
  nafarmit stovky výzev × 5 pokusů a šance uhodnout kód by rostla lineárně s farmováním.
- **`challengeUid` je povinný i při ověření** — váže kód na konkrétní relaci, takže
  znalost cizího e-mailu sama o sobě k přihlášení nestačí.

Kód se hashuje Argon2id (`SecurityConfig.codeEncoder`), inkrement pokusů je atomický
(`LoginChallengeRepository.incrementAttempts`, `UPDATE ... WHERE attempts < max_attempts`),
takže souběžné požadavky nemůžou dohromady vyzkoušet víc než `max_attempts` (výchozí 5)
kombinací. Rate limity (`OtpRateLimiter`, Caffeine): 1/60 s, 5/hod, 10/den na e-mail;
20/hod na IP.

**Rotace refresh tokenů** (`RefreshTokenService.rotate`): každé použití vydá nový token
ve stejné "rodině" (`family_uid`), starému nastaví `used_at`. Token s `used_at` už
nastaveným, použitý MIMO grace window (30 s), znamená pravděpodobnou krádež — revokuje se
CELÁ rodina, ne jen ten jeden token. Uvnitř grace window (typicky ztracená odpověď na
mobilní síti) se opakování toleruje, aby appka neodhlašovala lidi při výpadku signálu.

| | Web | Android |
|---|---|---|
| Refresh token | `httpOnly; Secure; SameSite=Strict` cookie, jen `/api/auth` | tělo odpovědi → `EncryptedSharedPreferences` |
| Access token | v paměti (signál), nikdy `localStorage` | v paměti procesu |
| TTL refresh | 30 dní | 180 dní |

Access JWT (`JwtService`, HS256, 10 min): `sub = public_uid`, claim `tv = token_version`.
Krátká životnost → žádný revokační seznam; okamžité globální odhlášení (změna hesla se
nekoná, ale např. podezření na krádež) se řeší inkrementem `token_version`, který se do
`JwtAuthenticationFilter` promítne nejpozději za 60 s (Caffeine cache).

## GDPR

Export a výmaz (`AccountService`, `AccountController`) jsou hotové — REST tok jako
`EmailChangeService` výš, ne GraphQL mutace.

- **Export**: `GET /api/me/export` — čitelný výpis vlastní identity (handle, e-mail, datum
  založení), volitelného profilu, cenových záznamů stále navázaných na účet (stejné okno jako
  appka sama ukazuje, tzn. posledních 180 dní — starší jsou pseudonymizované, tudíž v exportu
  nejsou), hodnocení kvality a vlastních úprav zboží/obchodu. Vyžaduje jen platný access token,
  žádné zvláštní potvrzení (na rozdíl od výmazu níž) — čtení vlastních dat nic nevratného
  neriskuje. `user_flag` skórovací systém z původního plánu neexistuje (implementovaná je jen
  složka `L`, viz `docs/reputace.md`) — export ho tedy neobsahuje, není co exportovat.
- **Výmaz**: dvoukrokový OTP tok jako změna e-mailu (`POST /api/me/delete/request` +
  `/confirm`), kód jde vždy na už vlastněnou přihlašovací adresu. Uživatel si NEVYBÍRÁ, co se
  stane s jeho cenovými zápisy — appka je vždy jen anonymizuje, nikdy skutečně nemaže: jde
  o sdílená komunitní data (cena/produkt/obchod), ne o jeho osobní údaj, appka proto nedává
  jednotlivci možnost jednostranně o nich rozhodnout. `fk_price_observation_submitter` je
  `ON DELETE SET NULL`, takže smazání řádku `auth.app_user` observace samo anonymizuje, stejným
  mechanismem jako denní pseudonymizace po 180 dnech — appka pro to nemusí dělat nic navíc.
  Váhu při agregaci to nemění: `PriceAggregationService.weightFor` čte samostatný
  snapshotovaný sloupec `submitter_kind` (`REGISTERED`/`ANONYMOUS`, nastavený při zápisu ceny,
  viz `docs/reputace.md`), ne `submitter_id` — observace registrovaného uživatele tak zůstává
  vážená jako registrovaná i po smazání jeho účtu, ne jako anonymní.

  Appka nejdřív smaže SOUBORY fotek z disku (`MediaStorage`, včetně avataru), teprve pak řádek
  `app_user` — ten kaskádou smaže zbytek (`user_profile`, `product_review` — hvězdičky i text,
  `product_user_edit`/`store_user_edit`, `record_flag`, `media` řádky v DB, `refresh_token`).
  Katalogová data (zboží, obchod), na která uživatel jen přispěl cenou, samozřejmě zůstávají —
  mažou/anonymizují se jen věci navázané na *jeho* účet.
- Žádná analytika třetích stran, žádné externí fonty ani CDN. Jediná cookie je `httpOnly`
  refresh token → není potřeba cookie lišta.
- Plánovaná AI (`docs/ai.md`) běží lokálně u provozovatele, ne přes cloudové API — jinak by tahle
  věta neplatila.

Výmaz účtu appka v UI nabízí na obou klientech (web `features/profile`, mobil
`ui/profile/ProfileScreen.kt` — dvoukrokový OTP dialog stejným tokem jako web).
**Neimplementováno** zůstává export: `GET /api/me/export` nenabízí v UI žádný klient — jen na
kontaktní e-mail, ručně (viz `docs/zasady-ochrany-osobnich-udaju.md`, „Tvá práva").

## Otevřená rizika / co hlídat

- **OpenStreetMap i Open Food Facts jsou ODbL** (share-alike) — viz `docs/datovy-model.md`,
  oddělení schémat `off`/`osm` od `core`. Na OSM se snadno zapomíná, protože souřadnice
  nevypadají jako "databáze".
- **Mapové dlaždice jdou přímo z klienta na poskytovatele** (výchozí OSM), ne přes server jako
  zbytek geokódování — vědomá, zapsaná výjimka (viz „Mapové dlaždice" výš), ne přehlédnutí.
  Hlídat při případné budoucí revizi, jestli pořád stojí za to (proxy dlaždic je oficiální
  politikou nedoporučená, ne zakázaná — vyžadovala by ale vlastní cache vrstvu navíc).
- Kapacita moderace jednoho člověka je reálný limit — proto co nejvíc automatiky
  (rate limity, detekce anomálií) a co nejméně věcí vyžadujících lidský zásah. Fotky
  (`app.moderation.photo-flags-to-hide`) mají mnohem nižší práh nahlášení než katalog
  (`docs/reputace.md`) přesně z tohohle důvodu.
