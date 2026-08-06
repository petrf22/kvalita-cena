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

## Identita bez osobních údajů

`auth.app_user` nemá pole pro jméno, adresu ani telefon — v API pro ně neexistuje místo.

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

Únik databázového dumpu bez pepperu a šifrovacího klíče z env tedy neodhalí jediný e-mail.

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

## Otevřená rizika / co hlídat

- **OpenStreetMap i Open Food Facts jsou ODbL** (share-alike) — viz `datovy-model.md`,
  oddělení schémat `off`/`osm` od `core`. Na OSM se snadno zapomíná, protože souřadnice
  nevypadají jako "databáze".
- Kapacita moderace jednoho člověka je reálný limit — proto co nejvíc automatiky
  (rate limity, detekce anomálií) a co nejméně věcí vyžadujících lidský zásah.
