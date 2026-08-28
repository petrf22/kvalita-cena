# Vydání

Jeden zdroj pravdy pro to, jak `mobile/` dojde k publikovatelnému release APK — kde leží
podpisový klíč, jak funguje registrace balíčku v Play Console a co vydání pořád ještě blokuje.
Podrobnosti rozhodnutí a jejich odůvodnění jsou v plánu migrace repa (viz paměť
`repo_migration.md`); tenhle dokument je jen aktuální stav a postup, ne historie rozhodování.

## Podpisový klíč

`applicationId` (`cz.kvalitacena`) je po prvním vydání neměnný a Android developer verification
ho sváže s **otiskem podpisového klíče** — proto je klíč jednou vygenerovaný navždy důležitý.

```bash
mkdir -p ~/.keystores
keytool -genkeypair -v -keystore ~/.keystores/kvalitacena-release.jks \
  -alias kvalitacena -keyalg RSA -keysize 4096 -validity 12000
```

- **Klíč nikdy nejde do repa** (`.gitignore`: `*.jks`, `*.keystore`, `mobile/keystore.properties`).
  Hesla jsou v `~/.gradle/gradle.properties` jako `KVALITACENA_STORE_FILE`,
  `KVALITACENA_STORE_PASSWORD`, `KVALITACENA_KEY_ALIAS`, `KVALITACENA_KEY_PASSWORD` — `mobile/
  app/build.gradle.kts` je čte přes `findProperty(...)`. **Chybí-li kterákoli hodnota,
  `signingConfig` se vůbec nezaloží** a `assembleRelease` vyrobí nepodepsané APK místo pádu —
  stejná logika jako `jvmToolchain(17)` místo `org.gradle.java.home`: release build musí projít
  i na cizím stroji a v CI, kde klíč nikdy nebude.
- **Ztráta klíče = ztráta možnosti aktualizovat aplikaci** mimo Play App Signing (viz níž). Záloha
  patří mimo tenhle stroj (např. šifrovaný cloud/USB), ne jen do `~/.keystores`.
- Otisk se zjistí přes:
  ```bash
  ~/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs \
    mobile/app/build/outputs/apk/release/app-release.apk
  ```
  Klíč vznikl 2026-08-09, `apksigner verify` na podepsaném `assembleRelease` potvrzuje:
  ```
  Signer #1 certificate DN: CN=Petr Franta, OU=Petr Franta, O=Petr Franta,
    L=Hradek u Rokycan, ST=Plzensky kraj, C=CZ
  Signer #1 certificate SHA-256 digest: d6039c24d0e282d681a91d561493b30d85d5f75001d7454daf1db4b6d261770f
  ```
  Tenhle SHA-256 patří do Play Console při registraci balíčku (viz níž) — až se tam bude
  zapisovat, ověřit ho znovu ze stejného příkazu, ne opisovat odsud (kdyby se klíč někdy
  přegeneroval, tahle poznámka by zůstala zastaralá).

## Registrace balíčku (Play Console)

Google zavádí **Android developer verification** — u účtů, které už na Play publikují, bývá
ověření identity hotové automaticky; druhá část je registrace dvojice `applicationId` +
SHA-256 otisku klíče. Termíny: **30. 9. 2026** vynucování v Brazílii, Indonésii, Singapuru
a Thajsku; **2027+** globálně včetně přímých instalací APK. Na `adb install` a emulátor při
vývoji to nemá vliv.

Postup:

1. [x] `play.google.com/console` → **Settings → Developer account** — ověřit platnost identity.
   Účet `petrf` (ID 8022299090683233755) je ověřený (e-mail, telefon, identita Petr Franta).
2. [x] **Ověření vývojáře pro Android → Názvy balíčků** — `cz.kvalitacena` je zaregistrovaný,
   stav **Registrováno**, klíč u něj má stav **Ověřeno** a otisk (`D6:03:9C:24:...:77:0F`)
   souhlasí s tím, co je zapsané výš u „Podpisový klíč" — žádná kolize identity mezi stroji.
3. [x] Založit aplikaci s `cz.kvalitacena` — hotovo 2026-08-21, aplikace „Kvalita a cena"
   (ID 4974901661372614749) je v konzoli jako **Koncept**, čeština, zdarma.

Registrace balíčku aplikaci **nevydává** — jen rezervuje jméno a sváže ho s ověřenou identitou.
Store listing, Data safety, Content rating, App access a Play App Signing jsou hotové (viz
sekce níž) a AAB je nahraný — appka běží v **Internal testing**, ověřeno 2026-08-26 na reálném
zařízení proti produkci (OTP přihlášení, hledání, zápis ceny, fotka). Cesta k produkčnímu
vydání dál vede přes uzavřený test, viz „Cesta do produkce" níž.

### Ikona pro listing

Play vyžaduje 512×512 PNG ikonu appky u store listingu (jinou položku než APK ikony samotné —
ty jde nahrát ze `tools/icons/play-store-icon-512.png`, jde o stejný motiv jako Android launcher
ikona, viz `docs/branding.md`).

Feature graphic (1024×500) je hotový — `tools/icons/feature-graphic.py` (spustit
`python3 tools/icons/feature-graphic.py`), samostatný skript mimo `docs/branding.md` (banner
s textem, ne ikona), ale sdílí přes import geometrii i barvu (`#1677FF`) z `generate.py`, ať se
nikdy vizuálně nerozejdou. Výstup: `tools/icons/play-store-feature-graphic.png`.

Dva screenshoty jsou hotové — `tools/icons/final-screenshot-1-search.png` a
`final-screenshot-2-settings.png`, pořízené z debug buildu proti produkčnímu API. Krátký/dlouhý
popis appky viz „Texty pro store listing" níže.

### Texty pro store listing

Návrh, k doplnění/úpravě přímo ve formuláři Play Console — drží se jen toho, co appka v etapě 1
skutečně umí (`docs/stav-implementace.md`), nic nepřislibuje dopředu (recenze, lokální
dodavatelé apod. ještě nejsou).

**Krátký popis** (limit 80 znaků — ověřit počet přímo v Play Console, český text s diakritikou
se počítá znak za znak, ne byte):
```
Sleduj ceny v obchodech okolo tebe – komunitně, bez sledování uživatelů.
```

**Dlouhý popis** (limit 4000 znaků):
```
Kvalita a cena je komunitní appka pro sledování cen běžného zboží v obchodech.

Naskenuj čárový kód na cenovce, zapiš cenu a obchod — appka z toho postaví přehled aktuální
ceny, vývoj v čase a srovnání napříč obchody ve tvém okolí. Vidíš, jestli je zboží dnes levnější
nebo dražší než obvykle, a kde ho v okolí seženeš nejlevněji.

Data appky tvoří lidé, kteří appku používají — čím víc vás bude zapisovat ceny, tím přesnější
a aktuálnější přehled appka nabídne. Appka počítá s tím, že přispěvatelé dělají chyby i omylem,
proto ceny porovnává napříč víc zápisy, ne jen podle jednoho posledního.

Soukromí bereme vážně: appka uživatele mezi sebou nesleduje, polohu zpracovává jen jako
zaokrouhlenou obec/město, ne přesné souřadnice, a osobní údaje po čase pseudonymizuje.
Komunita je záměrně nastavená pozitivně — appka nenabízí veřejné negativní hodnocení lidí.

Appka je zdarma a bez reklam.
```

Kontaktní e-mail pro listing (Play vyžaduje veřejný kontakt, ne jen v `docs/podminky-uziti.md`):
`kontakt@kvalitacena.cz`.

### Nahrávaný formát a App access

Play přijímá k publikaci jen **AAB** (`./gradlew :app:bundleRelease`), ne APK — `apksigner
verify`/registrace otisku klíče výš pořád platí na APK (přímá distribuce, F-Droid), ale do Play
Console jde nahrát bundle. Podepisuje se stejným `signingConfig` jako `assembleRelease`.

Recenzent appky se přes OTP nedostane (žádný testovací účet předem neexistuje, kód chodí na
reálnou schránku) — ve formuláři **App access** je potřeba deklarovat, že podstatná část appky
funguje bez přihlášení: čtení GraphQL (hledání, detail produktu, ceny) je `permitAll` a
zobrazuje se i anonymně (`app.history.anonymous-max-days: 90`), přihlášení je potřeba jen pro
zápis ceny/založení záznamu.

### Play App Signing

**Zastaralé, viz níž — původní plán počítal s volbou, kterou Play Console u nových appek
(`cz.kvalitacena` založena 2026-08-21) už nenabízí.** Doporučení bylo vygenerovat klíč lokálně
a nahrát ho do Play App Signing přes PEPK („use your own signing key"), místo aby Google
generoval a držel klíč sám — důvod: appka se má distribuovat i mimo Play (přímé APK
z `kvalitacena.cz`, F-Droid), bez vlastního klíče má dvě různé identity pod jedním balíčkem
podle distribučního kanálu.

**Realita (zjištěno 2026-08-25 při skutečném nastavování):** stránka „App integrity" se
přesunula do **Ochrana Obchodu Play → Podepisování aplikací**, a Google tam už MÁ vlastní
**podpisový klíč aplikace** vygenerovaný a aktivní („Používá se") — bez jakékoli volby PEPK
uploadu předem. `docs/branding.md`/tenhle dokument sledovaný otisk `D6:03:9C:24:...:77:0F` je
otisk NAŠEHO klíče (`kvalitacena-release.jks`), ale ten, co Play u appky drží jako „Podpisový
klíč aplikace", je jiný (`EB:67:6C:74:...:2D:E5`) — Google-spravovaný, ne náš. Sekce
„Certifikát klíče pro nahrávání" je prázdná do prvního uploadu AAB — náš klíč se zaregistruje
jako **upload key** automaticky tím, čím podepíšeme první `bundleRelease`, žádný ruční PEPK
krok není potřeba ani dostupný.

**Důsledek:** appka distribuovaná přes Play ponese Googlem spravovaný podpis (app signing
key), zatímco přímé APK z `kvalitacena.cz` ponese náš `kvalitacena-release.jks` (upload key) —
dvě různé identity mezi kanály zůstávají, jen už ne z naší volby, ale protože Google Play
App Signing je u nově založených appek povinné. Otevřené riziko k hlídání, ne vyřešené —
viz i F-Droid níž, který přidává třetí identitu.

### Data safety a Content rating — zaznamenané odpovědi

Deklarace je závazná a při každé aktualizaci se v Play Console znovu potvrzuje — odpovědi tady
zapsané, ať se příště nemusí dohledávat znovu z `docs/soukromi.md`.

**Data safety** (`docs/soukromi.md`, oprávnění `mobile/app/src/main/AndroidManifest.xml`):
žádné sdílení s třetími stranami, šifrovaný přenos (HTTPS), appka nabízí smazání účtu (web,
`docs/soukromi.md`, „GDPR"), žádná analytika třetích stran.

| Datový typ | Sbírá | Povinné? | Účel | Poznámka |
|---|---|---|---|---|
| E-mail | Ano | Povinné | Funkce aplikace, Správa účtu | Bez dočasného zpracování — ukládá se trvale (hash + šifrovaná podoba) po dobu trvání účtu |
| Přibližná poloha | Ano | Volitelné | Funkce aplikace | Manifest má jen `ACCESS_COARSE_LOCATION`; souřadnice se **neukládají** — zaškrtnuto „zpracováno dočasně" |
| Fotky | Ano | Volitelné | Funkce aplikace | EXIF včetně GPS se na serveru odstraní překódováním |
| Jiný uživatelský obsah | Ano | Volitelné | Funkce aplikace | Text zpětné vazby, ceny, katalogové úpravy |
| Adresa URL ke smazání účtu | — | — | — | `https://kvalitacena.cz/profile` (self-service smazání účtu, web) |

Crash logy a fotoaparát se **nedeklarují** — crash log appka posílá jen ručně přiloženě ke
zpětné vazbě (`crash/CrashReporter.kt`, checkbox nezaškrtnutý), fotoaparát je jen oprávnění, ne
datový typ.

**Content rating** (IARC dotazník): kategorie Utility, žádné násilí/nahota/hazard, uživatelský
obsah + moderace potvrzeny Ano, cílová skupina 18+, appka není vládní/finanční/zdravotní.
Výsledek: nejnižší věková kategorie.

**App access**: appka nemá testovací účet předem — recenzent se přihlásí vlastním e-mailem
(passwordless OTP, účet vznikne automaticky), většina appky (hledání, detail, ceny) funguje
i bez přihlášení.

### Cesta do produkce — uzavřený test (12 testerů / 14 dní)

Osobní vývojářský účet (na rozdíl od organizačního) potřebuje před přístupem k produkčnímu
vydání **uzavřený test (Closed testing) s aspoň 12 testery, kteří jsou opt-in přihlášení
14 dní v kuse**. Interní testování (viz výš) se do téhle kvóty nepočítá — je to jen ověřovací
krok před uzavřeným testem, ne jeho náhrada.

**Stav k 2026-08-26:** appka je jen v Internal testing (1 tester, sám provozovatel), uzavřený
test zatím založený není — čeká se, až bude k dispozici 12 lidí ochotných appku 14 dní testovat.

**Nastavení tracku (Play Console):**

1. **Test and release → Testing → Closed testing → Manage track → Create track.**
2. **Testers** — buď e-mailový seznam (min. 12 adres, ručně nebo CSV), nebo Google Group.
3. **Feedback URL/e-mail** na opt-in stránce testerů — `kontakt@kvalitacena.cz`.
4. **Countries/Regions** — stačí ČR.
5. Nahrát AAB a rollout na track. Google track typicky schválí do pár hodin, max 24 h.
6. Po schválení se na kartě **Testers** objeví **opt-in URL** — ten se posílá testerům.
   **Testeři appku musí instalovat přes tenhle odkaz z Play Storu, ne jako sideloadovaný APK**
   (přímá distribuce popsaná v „Vyřešit, jak testeři dostanou instalační APK" v
   `docs/nasazeni.md`) — jinak se jejich používání do kvóty 12/14 nezapočítá.

**Past: „14 dní" je per tester, ne per track.** Pokud tester opt-in odklikne, appku pár dní
testuje a pak se odhlásí (nebo appku smaže), jeho dny se nepočítají — při novém opt-inu začíná
počítat znovu od nuly, musí to být 14 dní v kuse. Potřeba je tedy, aby všech 12 lidí zůstalo
opt-in nepřetržitě celých 14 dní, ne že se za tu dobu prostřídá víc než 12 lidí a dohromady to
dá 12×14 tester-dní. **Zvát víc než 12 lidí (14–15) jako polštář** pro toho, kdo se odhlásí nebo
appku po pár dnech smaže.

**Past: Google kontroluje genuinní zapojení, ne jen opt-in stav.** Při žádosti o produkci
(„Apply for production", dostupné na Dashboardu, jakmile podmínka běží) se ptá na tři okruhy
otázek (průběh testu, detaily appky, připravenost na produkci) a chce popsané, jak zapojení
testerů odpovídalo očekávanému chování produkčních uživatelů — sedí to přesně na „Protokol
bety" v `docs/nasazeni.md` (stejný scénář pro každého testera: OTP přihlášení, hledání, zápis
ceny z mobilu i webu, založení obchodu, fotka, `/feedback`) — mít tenhle scénář zdokumentovaný
a doložitelný pro žádost, ne jen proběhlý. Review žádosti pak trvá **do 7 dní**, občas déle.

Track a e-mailový seznam jde založit, ještě než je všech 12+ lidí potvrzených — čím dřív je
track schválený a lidé dostanou opt-in odkaz, tím dřív jim začne běžet 14denní okno. Souběh
s backend betou (`docs/nasazeni.md`, „Protokol bety") dává smysl — stejní lidé přes stejný
scénář vyřeší obě podmínky najednou, jen musí appku instalovat přes Play opt-in odkaz.

### F-Droid — otevřené riziko, ne hotový postup

F-Droid standardně staví ze zdrojáků a podepisuje **svým** klíčem, což by znamenalo třetí
identitu vedle Play App Signing a přímého APK. Cesta ven je reproducible build s vlastním
podpisem, ale F-Droid tuhle politiku (vynucenou Google registrací) veřejně napadá a věc není
uzavřená. Zapsáno jako riziko k hlídání, ne jako vyřešené.

## `targetSdk 36`

Play od **31. 8. 2026** odmítá nové aplikace i aktualizace s nižším cílovým API.
`compileSdk` je 37, `targetSdk` je od tohoto dokumentu nastavené na 36 v `mobile/app/
build.gradle.kts`. **16 KB stránky jsou ověřené jako v pořádku** — všechny nativní knihovny
v APK (`libzxingcpp_android.so`, CameraX, `androidx.graphics.path`) mají LOAD zarovnání
`0x4000`. `MainActivity.kt` už volá `enableEdgeToEdge()`; zbývá projít insety a predictive back
vizuálně na emulátoru s API 36 (viz Ověření v plánu migrace repa).

## Release konfigurace `mobile/`

- **`isMinifyEnabled = true`** pro release, s `mobile/app/proguard-rules.pro` — keep pravidla pro
  `kotlinx-serialization` (`@Serializable` třídy v `network/Dto.kt`), Coil 3 a osmdroid, protože
  R8 by jinak reflexí volané třídy odstranil nebo přejmenoval.
- **`ApiConfig.BASE_URL`** je `buildConfigField` per build type (`network/ApiConfig.kt` čte
  `BuildConfig.BASE_URL`): debug `http://10.0.2.2:8080`, release `https://api.kvalitacena.cz`.
- **Cleartext HTTP je jen v debug variantu** — `src/main/res/xml/network_security_config.xml`
  přesunuté do `src/debug/res/xml/`, atribut `android:networkSecurityConfig` je jen
  v `src/debug/AndroidManifest.xml` (manifest merger ho přidá k `<application>` jen pro debug
  build). `android:usesCleartextTraffic="false"` v `src/main/AndroidManifest.xml` platí vždy.

## Verzování a vydání

Server, web i mobil sdílejí **jednu verzi** (SemVer). Zdroj pravdy jsou dva kořenové soubory:

- **`VERSION`** — jeden řádek, aktuální verze (např. `0.2.0`).
- **`CHANGELOG.md`** — seznam změn podle vydání, formát [Keep a Changelog](https://keepachangelog.com/cs/),
  text česky (konvence repa). Každá položka na jednom řádku, nepovinně s dotčenými částmi na
  konci — `- Text změny (server, web, mobil)`. Sekce `## [Nezveřejněno]` se do appek negeneruje.

`node tools/version/sync.mjs` z obou přepíše pět commitovaných výstupů — needituj je ručně:

| Výstup | Co obsahuje |
|---|---|
| `backend/build.gradle` | `version = '...'` |
| `frontend/package.json` | `"version": "..."` |
| `mobile/app/build.gradle.kts` | `versionCode`, `versionName` |
| `frontend/src/app/changelog.generated.ts` | seznam změn pro `/changelog` na webu |
| `mobile/app/src/main/assets/changelog.json` | totéž pro „Novinky" v appce (`ui/about/ChangelogScreen.kt`) |

`versionCode` se odvozuje `major*10000 + minor*100 + patch` (0.2.0 → 200) — **druhý upload
stejné verze do Play vyžaduje bump patche**, protože `versionCode` samostatně zvednout nejde.
Skript končí chybou, pokud nejnovější vydání v `CHANGELOG.md` nesouhlasí s `VERSION`, nebo
pokud narazí na víceřádkovou položku (parser umí jen jeden řádek na položku). CI to hlídá
stejně jako `graphql-codegen` — job „Verze a changelog" spustí skript a `git diff --exit-code`.

Appka verzi čte takto — beze změny, jen doplněno o odkaz na seznam změn:
- **Web** — `frontend/tools/version/write-version.mjs` z `package.json` při `npm start`/`npm
  run build` (přes `pre*` npm hooky) vygeneruje `src/app/version.ts`, odkud čte
  `features/about/about-page.ts`, patička (`app.ts`, odkaz na `/changelog`) i
  `services/feedback-service.ts` (`core.feedback.app_version`).
- **Mobil** — appka čte `BuildConfig.VERSION_NAME` (`ui/about/AboutScreen.kt`,
  `ui/feedback/FeedbackViewModel.kt`, `crash/CrashReporter.kt`) i `BuildConfig.VERSION_CODE`
  (`ClientVersionInterceptor`, hlavička `X-Client-Version` — `ClientVersionFilter` na serveru
  ji porovnává s `app.client.min-android-version` a starý klient zablokuje srozumitelnou
  obrazovkou, ne nesrozumitelnou chybou z každého jednotlivého volání).
- **Server** — `springBoot { buildInfo() }` (`backend/build.gradle`) vystaví verzi (a commit,
  je-li build spuštěn s `GIT_SHA` v prostředí) na `/actuator/info` (`permitAll`).

### Model větvení

Trunk-based — `main` je vždy vydatelná, žádné dlouhé větve (dependabot PR se merguje rovnou do
`main`). Vydání = anotovaný git tag `vX.Y.Z` na `main`.

### Postup vydání

1. Doplnit položky do `## [Nezveřejněno]` v `CHANGELOG.md`, přejmenovat na
   `## [X.Y.Z] – <datum>`.
2. Zapsat `X.Y.Z` do `VERSION`.
3. `node tools/version/sync.mjs` — přepíše všech pět generovaných výstupů.
4. Commit „Vydat X.Y.Z" + `git tag -a vX.Y.Z -m 'Verze X.Y.Z'` + `git push --follow-tags`.

### Z čeho stavět

- **Server**: `./ops/deploy.sh X.Y.Z` (viz `ops/README.md`) — aktualizuje repo na tag, sekvenčně
  sestaví backend a web (`docs/nasazeni.md`, „Sekvenční build"), spustí a ověří
  `/actuator/health`/`/actuator/info` proti `version`/`commit`. Ruční ekvivalent, když je potřeba
  krok po kroku: `git checkout vX.Y.Z`, `export GIT_SHA=$(git rev-parse --short HEAD)`,
  `docker compose -f compose.prod.yaml build backend && docker compose -f compose.prod.yaml build
  web && docker compose -f compose.prod.yaml up -d`.
- **Mobil**: `git checkout vX.Y.Z`, `./gradlew :app:bundleRelease` na lokálním PC (podpisový
  klíč viz výš). Ověření: „O aplikaci" ukazuje `X.Y.Z (versionCode)`.

### Hotfix už vydané verze

Když `main` mezitím utekl dál a je potřeba opravit jen vydanou verzi:

```bash
git checkout -b release/X.Y.x vX.Y.Z
# oprava, VERSION → X.Y.(Z+1), node tools/version/sync.mjs
git tag -a vX.Y.(Z+1) -m 'Verze X.Y.(Z+1)'
```

Opravu pak cherry-pickovat zpět do `main`. Větve `release/X.Y.x` vznikají **jen v tomhle
případě**, ne u každého běžného vydání.

### Mobil smí zaostávat za serverem

Kvůli Play review nebo uzavřenému testu může appka v obchodě běžet na starší verzi, než jaká je
nasazená na serveru — to je žádoucí a je to hned vidět (appka i server ukazují svou verzi).
Kompatibilitu hlídá `X-Client-Version` + `app.client.min-android-version` (`ClientVersionFilter`),
což je teď čitelný `versionCode` (např. `200` = „aspoň 0.2.0").

## Co vydání pořád blokuje

Produkční backend běží od 2026-08-24, appka je nahraná a funkčně ověřená v Internal testing
(viz „Cesta do produkce" výš) — jediné, co teď brání produkčnímu vydání appky na Play, je
**uzavřený test s 12 testery po dobu 14 dní**, viz tamtéž. Než se sežene 12 lidí, appku jinak
nic nebrzdí.
