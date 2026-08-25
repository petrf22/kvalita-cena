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
Zbývá store listing (ikona hotová, feature graphic a screenshoty ne), Data safety formulář,
URL zásad soukromí (čeká na živý `kvalitacena.cz`, `docs/nasazeni.md`), Play App Signing
(nahrání vlastního klíče — nevratné po prvním uploadu, viz níž) a upload AAB.

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

## Verze — kde se mění při vydání

Tři nezávislé zdroje, žádný sjednocující mechanismus (tag-based release neexistuje):

- **Backend** — `backend/build.gradle`, `version = '0.1.0'`.
- **Web** — `frontend/package.json`, `"version": "0.1.0"`. Appka ji sama nikde nečte natvrdo:
  `tools/version/write-version.mjs` z ní při `npm start`/`npm run build` (přes `pre*` npm
  hooky) vygeneruje `src/app/version.ts`, odkud čte `features/about/about-page.ts` i
  `services/feedback-service.ts` (`core.feedback.app_version` u webových hlášení).
- **Mobil** — `mobile/app/build.gradle.kts`, `versionCode`/`versionName` — appka je čte přes
  `BuildConfig.VERSION_NAME` (`ui/about/AboutScreen.kt`, `ui/feedback/FeedbackViewModel.kt`,
  `crash/CrashReporter.kt`) i `BuildConfig.VERSION_CODE` (`ClientVersionInterceptor`, hlavička
  `X-Client-Version` — `ClientVersionFilter` na serveru ji porovnává s
  `app.client.min-android-version` a starý klient zablokuje srozumitelnou obrazovkou, ne
  nesrozumitelnou chybou z každého jednotlivého volání).

## Co vydání pořád blokuje

Tenhle dokument řeší, jak appka **umí** vzniknout jako podepsané APK a jak se zaregistruje
balíček — samo vydání blokuje ještě:

- **Neexistuje produkční backend** na `api.kvalitacena.cz` — release build nemá kam mluvit.
  Hlavní blokátor, ne registrace balíčku.
- **Play požaduje** URL zásad ochrany soukromí (obsah je hotový v `docs/soukromi.md`, teď je
  konečně kam ho pověsit), formulář Data safety (kamera, poloha, fotky, pseudonymizace po
  180 dnech — viz `docs/soukromi.md`) a obsahový rating.
