# Soukromí

Cíl aplikace není sledovat, kdo kde nakupuje — cílem je, aby co nejvíc lidí mělo přehled
o cenách. To je v přímém napětí s tím, že reputační systém potřebuje vazbu příspěvek →
uživatel. Tento dokument shrnuje, jak se ten rozpor řeší v datovém modelu, ne až v UI.

## Poloha se nikdy neukládá jako GPS uživatele

Mobil zjistí polohu lokálně, zaokrouhlí ji na 3 desetinná místa (~110 m) a pošle **dotaz**
`nearbyStores(lat, lon, radius)` — GraphQL query, ne mutace. Server odpoví seznamem
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
nikam nezapisují a jsou v POST body, které se neloguje. Výpadek Nominatimu vrací prázdná
pole, nikdy chybu — editace obchodu nesmí spadnout kvůli nedostupnému externímu serveru.

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

Mapa nad OpenStreetMap (`frontend/shared/location-map.ts` — Leaflet, `mobile/ui/common/
LocationMap.kt` — osmdroid) na rozdíl od geokódování stahuje dlaždice **přímo z prohlížeče/
appky** (`tile.openstreetmap.org`) — OSM tak vidí IP uživatele, přesně to, čemu se
`geocodeAddress`/`reverseGeocode`/`nearbyStores` výš vyhýbají. Proxování dlaždic přes backend
by šlo, ale je to proti OSM tile usage policy (server-side proxy vyžaduje vlastní tile server
nebo komerční smlouvu). Zmírnění je proto jen v UI: mapa (a tedy i stahování dlaždic) se
vytvoří až po explicitním kliknutí na „Zobrazit mapu", nikdy automaticky při načtení stránky/
obrazovky — na rozdíl od zbytku dokumentu tahle výjimka není beze zbytku vyřešená, jen vědomě
přijatá a zapsaná, ať se na ni nezapomene při případné budoucí revizi.

### Lokální AI (plánováno) je opačný případ než mapové dlaždice

U dlaždic výš appka vědomě připouští, že OSM uvidí IP uživatele. Plánovaná AI (`ai.md`) — čtení
čísel z fotek, předfiltr moderace, kontrola textů — jde přesně opačným směrem: model běží u
provozovatele appky (lokální PC), takže fotky ani texty uživatelů neopouští appku vůbec, žádné
třetí straně. Verdikt je navíc vždy jen poradní, nikdy sám nerozhoduje — viz `ai.md`, „AI nikdy
nerozhoduje".

## Retence vazby observace → uživatel: 180 dní

| Fáze | Doba | Co je uloženo |
|---|---|---|
| Aktivní | 0–180 dní | `price_observation.submitter_id` plně |
| Pseudonymizovaná | 180 dní+ | `submitter_id = NULL`, zůstane jen `submitter_cohort` (reputační pásmo) a `frozen_weight` |

180 dní je nejdelší okno, které potřebuje detekce anomálií a řešení sporů o cenu. Reputace
tím netrpí, protože se počítá jako průběžně aktualizovaný čítač s exponenciálním útlumem
(viz `reputace.md`), ne z historie jednotlivých událostí — smazání vazby na starou observaci
tedy reputaci nijak nemění.

Pro uživatele to znamená: „moje příspěvky" ukazují jen posledních 180 dní. To je vlastnost,
ne omezení — starší nákupy už o něm nikdo nedohledá.

### Výjimka: hodnocení kvality zboží vazbu nepseudonymizuje

`core.product_quality_rating.user_id` (etapa 1, jen známka 1–5 — viz `datovy-model.md` a
`reputace.md`) je jediné místo v `core.*`, kde tohle pravidlo neplatí. Bez trvalé vazby by
nešlo vynutit „jedna známka na uživatele a produkt" (unikátní index `(product_id, user_id)`).
Je to vědomé zhoršení, ne přehlédnutí — zmírněné třemi věcmi:

- **Ven přes API jde jen agregát** (`ProductQuality.average`/`count`), nikdy seznam „kdo co
  ohodnotil" — `user_id` se z DB nedostane ven ani nepřímo.
- **`ON DELETE CASCADE`, ne `SET NULL`** — smazání účtu známky rovnou odstraní, na rozdíl od
  `price_observation.submitter_id`, kde observace zůstávají jako pseudonymizovaná statistika
  ve veřejném zájmu. Známka bez vlastníka nemá tenhle veřejný zájem, který by odůvodnil
  přežití záznamu po smazání účtu.
- **`pg_dump --schema=core` musí sloupec vynechat nebo hashovat** — jinak „čistý" export
  (`datovy-model.md`) tiše prolomí záruku z tohoto dokumentu. Až vznikne skutečný GDPR
  export/výmaz (`GET /api/me/export`, `POST /api/me/delete` níže), hodnocení kvality do
  něj patří stejně jako cenové záznamy.

### Druhá výjimka: uživatelská vrstva nad globálními daty vazbu nepseudonymizuje

`core.product_user_edit`/`core.store_user_edit.user_id` (etapa 1 — viz `datovy-model.md`,
"Uživatelská vrstva nad globálními daty") je druhé místo v `core.*`, kde 180denní pravidlo
neplatí. Bez trvalé vazby by uživateli po půl roce tiše zmizely jeho vlastní opravy (název,
gramáž, adresa) — patch by se přestal zobrazovat, protože ho backend neumí spárovat s
žádným viewerem. Zmírněné stejně jako u `product_quality_rating` výš:

- **Ven přes API jde jen efektivní hodnota** (globální nebo přepsaná patchem, podle toho, kdo
  se ptá) — seznam "kdo co upravil" v API neexistuje.
- **`ON DELETE CASCADE`** — smazání účtu patch rovnou odstraní, záznam se vrátí na globální
  hodnotu. Stejná úvaha jako u `product_quality_rating`: uživatelova pracovní data nemají po
  smazání účtu veřejný zájem, který by zdůvodnil přežití (na rozdíl od `price_observation`).
- **`pg_dump --schema=core` musí sloupec `user_id` vynechat nebo hashovat** stejně jako u
  `product_quality_rating.user_id` — jinak "čistý" export tiše prolomí tuhle záruku.
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
- **Skupiny důvěry (přátelé) v etapě 1 neexistují** (`docs/datovy-model.md`), takže řádky
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

## GDPR (plánováno, zatím ne v etapě 1)

- Export vlastních dat: `GET /api/me/export`, včetně čitelného výpisu vlastních
  systémových `user_flag` — skórovací systém, do kterého uživatel nevidí, je proti duchu
  projektu (viz `reputace.md`, "Proč žádné veřejné negativní hodnocení").
- Výmaz: `POST /api/me/delete`, dva režimy — anonymizovat účet (observace zůstávají
  pseudonymizované jako agregovaná statistika ve veřejném zájmu) nebo smazat i obsah.
- Žádná analytika třetích stran, žádné externí fonty ani CDN. Jediná cookie je `httpOnly`
  refresh token → není potřeba cookie lišta.
- Plánovaná AI (`ai.md`) běží lokálně u provozovatele, ne přes cloudové API — jinak by tahle
  věta neplatila.

## Otevřená rizika / co hlídat

- **OpenStreetMap i Open Food Facts jsou ODbL** (share-alike) — viz `datovy-model.md`,
  oddělení schémat `off`/`osm` od `core`. Na OSM se snadno zapomíná, protože souřadnice
  nevypadají jako "databáze".
- **Mapové dlaždice jdou přímo z klienta na OSM**, ne přes server jako zbytek geokódování —
  vědomá, zapsaná výjimka (viz „Mapové dlaždice" výš), ne přehlédnutí. Hlídat při případné
  budoucí revizi, jestli pořád stojí za to (proxy dlaždic vyžaduje vlastní tile server nebo
  komerční smlouvu s OSM Foundation).
- Kapacita moderace jednoho člověka je reálný limit — proto co nejvíc automatiky
  (rate limity, detekce anomálií) a co nejméně věcí vyžadujících lidský zásah. Fotky
  (`app.moderation.photo-flags-to-hide`) mají mnohem nižší práh nahlášení než katalog
  (`reputace.md`) přesně z tohohle důvodu.
