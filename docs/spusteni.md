# Spuštění a používání

Praktický návod pro lokální vývoj — jak appku rozběhat a ručně si vyzkoušet celý tok: přihlášení,
vyhledání produktu a zápis ceny. Architektonické konvence jsou v [`CLAUDE.md`](../CLAUDE.md);
tady jde jen o „jak na to spustit a co s tím dělat".

## Předpoklady

- **Docker** — pro PostgreSQL.
- **JDK se řeší samo** — backend i mobil používají Gradle toolchain (`jvmToolchain(17)` v mobilu,
  Java 25 v backendu), takže Gradle si chybějící JDK stáhne přes `foojay-resolver-convention`.
  Nic se ručně neinstaluje.
- **Node ≥ 22.22.3** — systémový Node bývá starší (22.17.0) a Angular 22 na něm neběží. Aktivuj
  novější přes nvm: `source ~/.nvm/nvm.sh && nvm use 24`.
- **Android SDK** (jen pro mobil) — `mobile/local.properties` musí obsahovat `sdk.dir=<cesta k SDK>`.
  Soubor je v `.gitignore`, na čerstvém klonu chybí a je potřeba ho založit ručně (nebo mít
  nastavené `ANDROID_HOME`).

## 1. Databáze

```bash
docker compose up -d
```

Naběhne PostgreSQL 17 na `127.0.0.1:5437` (schválně ne na výchozím 5432, ať nekoliduje s jiným
projektem na stejném stroji). Data přežívají v pojmenovaném volume, takže druhé `up -d` je no-op.

Přístup do databáze (lokálně `psql` není nainstalované):

```bash
docker compose exec postgres psql -U postgres -d kvalitaacena
```

Tenhle krok je ve skutečnosti nepovinný — `./gradlew bootRun` má `spring-boot-docker-compose`
a spustí si Postgres sám. Rozdíl je jen v tom, že `lifecycle-management: start-only` znamená, že
po vypnutí backendu DB dál běží na pozadí (nezastaví se s appkou). Výjimka je spuštění z IDE
nainstalovaného jako Flatpak (typicky IntelliJ IDEA) — viz sekce **2. Backend** níže, tam je krok
povinný.

## 2. Backend

```bash
cd backend
./gradlew bootRun
```

Naběhne na `http://localhost:8080`. Liquibase při prvním startu vytvoří schémata `core`, `auth`,
`agg`, `off`, `osm` a všechny tabulky — **ale databáze zůstává prázdná** (žádné obchody, produkty
ani řetězce; to je záměr, seed dat nepatří do migrací).

Ověření, že appka žije: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.

### Testování s čerstvým účtem — práh důvěry (`app.trust.*`)

Bez aktivního profilu `beta` platí ostré produkční prahy z `application.yml`
(`min-account-age-days: 7`, `min-observations: 5`, `draft-confirmations: 3`) — nový testovací
účet je pod nimi vždycky. Zboží i obchod, které takový účet založí, tak vzniknou se
`status: DRAFT` a jsou vidět **jen jemu** (`docs/reputace.md`), i když mají platný naskenovaný
EAN — v hledání i po opětovném naskenování kódu z jiného účtu/zařízení to pak vypadá, jako by se
záznam vůbec neuložil (ve skutečnosti v DB je, jen skrytý ostatním, a UI od teď ukazuje badge
„Čeká na potvrzení").

Pro lokální ruční testování je připravený profil `beta` (`application-beta.yml`, prahy `0/0/1` —
určeno pro uzavřenou betu s pozvanými lidmi, ne pro produkci) — aktivuje se přes proměnnou
prostředí:

```bash
SPRING_PROFILES_ACTIVE=beta ./gradlew bootRun
```

Z IntelliJ IDEA (viz run konfigurace níže) přidej `SPRING_PROFILES_ACTIVE=beta` do stejného
seznamu proměnných prostředí vedle `SPRING_DOCKER_COMPOSE_ENABLED`.

### Spuštění z IntelliJ IDEA

Pokud je IDEA nainstalovaná jako Flatpak, běží appka i Gradle daemon v sandboxu, který nevidí
hostovský `docker` — `spring-boot-docker-compose` pak selže na `Cannot run program "docker"`.
Řešení: databázi nastartuj ručně (`docker compose up -d`, viz výše) a appku spouštěj přes sdílenou
run konfiguraci **Backend (DB z terminálu)** z `backend/.run/backend.run.xml` (IDEA ji po otevření
projektu s `backend/` jako kořenem nabídne automaticky) — ta má `SPRING_DOCKER_COMPOSE_ENABLED=false`.
Musí se spustit **výběrem této konfigurace z rozbalovací nabídky v toolbaru**, ne přes zelenou
šipku u `main()` — ta by si vytvořila vlastní ad-hoc konfiguraci a proměnnou by ignorovala.
Konfigurace je typu obyčejná `Application` (ne Spring Boot) — plugin Spring Boot je jen
v IntelliJ IDEA Ultimate, v Community edici by ji IDE hlásilo jako nenačitatelnou. Ve vlastní
konfiguraci stačí tu samou proměnnou prostředí přidat ručně. Terminálový `./gradlew bootRun` tímhle
dotčený není, tam docker integrace funguje beze změny.

## 3. Ukázková data

Bez dat se v UI nedá nic vyzkoušet — hledání by bylo prázdné a detail produktu by neexistoval.
Proto je v repu `dev/seed.sql`: pár řetězců (Albert, Lidl a lokální „Farma Sedlák"), pět provozoven
v Brně a Praze se skutečnými souřadnicemi, kategorie, šest produktů se skutečně vypadajícími
EAN kódy (rohlík, chléb, máslo, mléko, jogurt, vejce) a pět bezkódových druhových položek
(chléb, rohlík, brambory, mléko, vejce — `core.product.is_generic`, viz `docs/reputace.md`,
„Zboží bez čárového kódu") pro vyzkoušení bezkódového zápisu ceny.

Spustit **až po prvním startu backendu** (musí existovat schémata):

```bash
docker compose exec -T postgres psql -U postgres -d kvalitaacena < dev/seed.sql
```

Skript je idempotentní (`ON CONFLICT DO NOTHING`), klidně ho pouštěj opakovaně. Ceny záměrně
nesedí — ty se zapisují přes appku, viz níže.

Ověření: `SELECT count(*) FROM core.product;` by mělo vrátit `6`.

## 4. Přihlášení (OTP) — jak se v etapě 1 dozvíš kód

Aplikace je passwordless: zadáš e-mail, dostaneš 6místný kód, kódem se přihlásíš. V etapě 1 je
`app.auth.otp.mail-enabled: false` — **žádný e-mail se neposílá**, kód se jen vypíše do konzole
backendu:

```
[DEV] OTP kód pro e-mail xxx@yyy (challenge <uuid>): 123456
```

Vyzkoušení přes curl:

```bash
curl -X POST localhost:8080/api/auth/otp/request \
  -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com"}'
# → {"challengeUid":"...", "expiresInSec":600, "resendAfterSec":60}
# teď se podívej do terminálu, kde běží ./gradlew bootRun, a opiš si "[DEV] OTP kód ..."

curl -X POST localhost:8080/api/auth/otp/verify \
  -H 'Content-Type: application/json' \
  -d '{"challengeUid":"<z předchozí odpovědi>","code":"<z logu>","email":"test@example.com"}'
# → {"accessToken":"...", "refreshToken":null, "newUser":true}
```

`refreshToken` je `null`, protože pro web se posílá jako httpOnly cookie (`refresh_token`,
path `/api/auth`). Chceš-li ho vidět v těle odpovědi (jako mobilní klient), přidej hlavičku
`-H 'X-Client-Kind: ANDROID'` k oběma voláním.

Rate limity (in-memory, mizí restartem backendu): 1 požadavek/60 s na e-mail, 5/hod na e-mail,
10/den na e-mail, 20/hod na IP. Při testování víc přihlášení za sebou střídej e-maily, jinak
dostaneš HTTP 429.

## 5. GraphQL (GraphiQL)

`http://localhost:8080/graphiql` — bez autentizace, `POST /graphql` je veřejný endpoint.

```graphql
query {
  searchProducts(query: "máslo") {
    id
    name
    unitBase
  }
}
```

Hledání je fulltextové bez lemmatizace (`to_tsvector('simple', ...)`) — funguje jen na celá slova
v základním tvaru, „másl" nic nenajde.

```graphql
query {
  productByCode(code: "8594001234585") {
    id
    name
  }
}
```

EAN se automaticky normalizuje na GTIN-14 (doplní se nulami zleva), stejný kód jako v `dev/seed.sql`.

```graphql
mutation {
  submitObservation(input: {
    productId: "3"
    storeId: "1"
    priceAmount: 42.90
    priceKind: REGULAR
  }) {
    id
    priceAmount
  }
}
```

Funguje i bez `Authorization` hlavičky (anonymní příspěvek má jen nižší váhu — 0,15 místo 1,00,
`PriceAggregationService.weightFor()`). Cena se ale v `product { prices { ... } } }` neobjeví
hned — `submitObservation` jen zapíše observaci a zařadí přepočet do `agg.recompute_queue`;
`PriceAggregationService.processQueue()` běží každých 5 s (`@Scheduled(fixedDelay = 5000)`).
Zkus dotaz zopakovat po pár vteřinách:

```graphql
query {
  product(id: "3") {
    name
    prices { store { name } priceKind unitPrice priceAmount nObs }
  }
}
```

## 6. Web (Angular)

```bash
source ~/.nvm/nvm.sh && nvm use 24
cd frontend
npm install
npm start
```

Dev server na `http://localhost:4200`, `proxy.conf.json` přeposílá `/api` a `/graphql` na
`localhost:8080` — backend musí běžet zároveň.

Routy:
- `/` — hledání produktů,
- `/produkt/:id` — detail, tabulka cen podle typu (běžná/akce/klubová/výprodej/množstevní), formulář na zápis ceny,
- `/zadat-cenu` — samostatná stránka: najdi/založ zboží (podle názvu i EANu) → najdi/založ obchod → zapiš cenu (i zpětně datovanou),
- `/prihlaseni` — stejný OTP flow jako výše, jen přes formulář (kód se pořád čte z konzole backendu),
- `/moje-prispevky` — vlastní založené zboží/obchody, vlastní zapsané ceny a vlastní úpravy cizích záznamů se stavem zveřejnění (konkrétní "zatím 1 ze 3", ne jen štítek), odkaz z karty Účet; vyžaduje přihlášení.

Výběr provozovny (`shared/store-picker.ts`, použitý v obou formulářích výše) umí tři cesty:
napsat název nebo město (`searchStores`), stisknout „Najít v okolí" (`nearbyStores`, potřebuje
geolokaci prohlížeče) nebo rovnou založit nový obchod (`shared/store-form.ts` — volitelně IČO
s předvyplněním z ARES, volitelně souřadnice přes geokódování adresy nad OpenStreetMap
Nominatim). Zakládání obchodu i zboží (`features/product-form`) vyžaduje přihlášení — vyzkoušej
si nejdřív krok 4 níže.

## 7. Mobil (Android)

```bash
cd mobile
./gradlew :app:compileDebugKotlin   # rychlá kontrola bez balení APK
./gradlew :app:assembleDebug        # sestaví app-debug.apk
```

Emulátor (ověřený AVD `Medium_Phone`):

```bash
~/Android/Sdk/emulator/emulator -avd Medium_Phone -no-snapshot -no-boot-anim -gpu swiftshader_indirect
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n cz.kvalitacena/.MainActivity
```

`-gpu swiftshader_indirect` (softwarové renderování) je záměrně — Mesa/X11 GPU passthrough na
tomto typu stroje občas spadne s X chybou.

Appka v emulátoru míří na `http://10.0.2.2:8080` — to je alias emulátoru na `localhost` hostitele,
takže backend musí běžet na hostiteli (ne v emulátoru). Cleartext HTTP je povolený jen pro tuhle
adresu (`network_security_config.xml`), appka jinak vyžaduje HTTPS. Pro test na fyzickém telefonu
by bylo potřeba změnit `ApiConfig.BASE_URL` na IP hostitele v lokální síti a přidat ji do
`network_security_config.xml`.

Flow v appce: sken (kamera + ZXing) → zadání ceny a typu → výběr provozovny podle polohy
(`LocationManager`, COARSE) → odeslání. Naskenuj EAN ze seedu (např. `8594001234585` = Máslo
čerstvé) nebo si ho zobraz jako čárový kód na jiném displeji/vytiskni. Záložka Účet (přihlášený
stav) má tlačítko "Moje příspěvky" (`ui/contributions/MyContributionsScreen.kt`) — čtyři
záložky Zboží/Obchody/Ceny/Úpravy se stejným stavem zveřejnění jako na webu.

## 8. Testy

```bash
cd backend && ./gradlew test                    # všechny
./gradlew test --tests "*.PriceAggregationServiceTest"   # jeden

cd frontend && npm test -- --watch=false         # Vitest

cd mobile && ./gradlew :app:testDebugUnitTest    # JUnit, jen čistá logika (geometrie grafu, popisek obchodu, validace formulářů)
```

## Časté potíže

| Příznak | Příčina | Řešení |
|---|---|---|
| `429 Too Many Requests` na `/api/auth/otp/request` | rate limit (1/60 s, 5/hod na e-mail) | počkej, nebo použij jiný e-mail |
| Hledání nic nevrací | FTS je bez lemmatizace (`simple` konfigurace) | hledej celé slovo v základním tvaru, viz `dev/seed.sql` |
| Nově zapsaná cena se v `prices` chvíli neobjeví | přepočet běží na pozadí každých 5 s | počkej a dotaz zopakuj |
| `nearbyStores`/select provozovny v detailu je prázdný | mimo souřadnice ze seedu (Brno/Praha) nebo zamítnutá poloha | přepiš polohu v DevTools, nebo přidej vlastní provozovnu do `dev/seed.sql` |
| Port `5437` obsazený | jiný projekt na stejném stroji na něm už běží | zastav ho, nebo změň port v `compose.yaml` i `application.yml` |
| Backend hlásí chybu schématu po ruční změně DB | `ddl-auto: validate` — schéma smí měnit jen Liquibase | vrať se k migracím, neuprav tabulku ručně |
| `Cannot run program "docker"` při spuštění z IDE | IDE (např. IntelliJ IDEA) běží jako Flatpak, sandbox nevidí hostovský docker | spusť `docker compose up -d` ručně a appku pouštěj přes run konfiguraci s `SPRING_DOCKER_COMPOSE_ENABLED=false` — viz sekce **2. Backend** |
| `geocodeAddress` vždy vrátí prázdné `candidates` | Nominatim často blokuje datacentrové/sdílené IP (`403 Access denied`, viz jeho usage policy) | obchod jde uložit i bez souřadnic — doplní se později; pro reálné testování geokódování je potřeba běžná domácí IP |
| Nově založené zboží/obchod „zmizí" — nejde najít ani přes hledání, ani opětovným skenem kódu | čerstvý účet je pod prahem důvěry (`app.trust.*`), záznam vznikl jako `status: DRAFT`, vidí ho jen autor | zkontroluj `/moje-prispevky` — ukáže přesný stav ("zatím X z Y potvrzení"); aktivuj lokálně profil `beta` (`SPRING_PROFILES_ACTIVE=beta`, viz sekce **2. Backend**), nebo počkej/navyš stáří účtu a `observation_count` |
