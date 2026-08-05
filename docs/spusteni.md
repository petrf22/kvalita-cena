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
po vypnutí backendu DB dál běží na pozadí (nezastaví se s appkou).

## 2. Backend

```bash
cd backend
./gradlew bootRun
```

Naběhne na `http://localhost:8080`. Liquibase při prvním startu vytvoří schémata `core`, `auth`,
`agg`, `off`, `osm` a všechny tabulky — **ale databáze zůstává prázdná** (žádné obchody, produkty
ani řetězce; to je záměr, seed dat nepatří do migrací).

Ověření, že appka žije: `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`.

## 3. Ukázková data

Bez dat se v UI nedá nic vyzkoušet — hledání by bylo prázdné a detail produktu by neexistoval.
Proto je v repu `dev/seed.sql`: pár řetězců (Albert, Lidl a lokální „Farma Sedlák"), pět provozoven
v Brně a Praze se skutečnými souřadnicemi, tři kategorie a šest produktů se skutečně vypadajícími
EAN kódy (rohlík, chléb, máslo, mléko, jogurt, vejce).

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
- `/prihlaseni` — stejný OTP flow jako výše, jen přes formulář (kód se pořád čte z konzole backendu).

**Past, na kterou narazíš hned:** výběr provozovny v detailu produktu se plní **výhradně**
z geolokace prohlížeče (`findNearbyStores()` volá `navigator.geolocation`) — žádné ruční
vyhledání obchodu v UI zatím není. Pokud polohu zamítneš nebo nejsi poblíž Brna/Prahy (souřadnice
ze seedu), select zůstane prázdný a cenu nepůjde zapsat. Pro dev to nejsnazší obejít přes
DevTools → Sensors → Location a nastavit vlastní souřadnice (např. 49.1996, 16.6089 pro Albert
Brno-Střed ze seedu).

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
čerstvé) nebo si ho zobraz jako čárový kód na jiném displeji/vytiskni.

## 8. Testy

```bash
cd backend && ./gradlew test                    # všechny
./gradlew test --tests "*.PriceAggregationServiceTest"   # jeden

cd frontend && npm test -- --watch=false         # Vitest, zatím jen 2 základní testy v app.spec.ts
```

Mobil zatím nemá žádné testy (`mobile/app/src` obsahuje jen `main`).

## Časté potíže

| Příznak | Příčina | Řešení |
|---|---|---|
| `429 Too Many Requests` na `/api/auth/otp/request` | rate limit (1/60 s, 5/hod na e-mail) | počkej, nebo použij jiný e-mail |
| Hledání nic nevrací | FTS je bez lemmatizace (`simple` konfigurace) | hledej celé slovo v základním tvaru, viz `dev/seed.sql` |
| Nově zapsaná cena se v `prices` chvíli neobjeví | přepočet běží na pozadí každých 5 s | počkej a dotaz zopakuj |
| `nearbyStores`/select provozovny v detailu je prázdný | mimo souřadnice ze seedu (Brno/Praha) nebo zamítnutá poloha | přepiš polohu v DevTools, nebo přidej vlastní provozovnu do `dev/seed.sql` |
| Port `5437` obsazený | jiný projekt na stejném stroji na něm už běží | zastav ho, nebo změň port v `compose.yaml` i `application.yml` |
| Backend hlásí chybu schématu po ruční změně DB | `ddl-auto: validate` — schéma smí měnit jen Liquibase | vrať se k migracím, neuprav tabulku ručně |
