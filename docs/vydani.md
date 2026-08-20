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

1. `play.google.com/console` → **Settings → Developer account** — ověřit platnost identity.
2. **Ověření vývojáře pro Android → Názvy balíčků** — zkontrolovat, co je zaregistrované.
3. Založit aplikaci s `cz.kvalitacena` — tím se balíček **zaregistruje automaticky** a jméno se
   zabere; jde to i dřív, než je co nahrát, aplikace zůstane v konceptu.

Registrace balíčku aplikaci **nevydává** — jen rezervuje jméno a sváže ho s ověřenou identitou.

### Play App Signing

Doporučení: **vygenerovat klíč lokálně (viz výš) a nahrát ho do Play App Signing** („use your
own signing key"), místo aby Google generoval a držel klíč sám. Důvod: appka se má distribuovat
i mimo Play (přímé APK z `kvalitacena.cz`, F-Droid) — bez vlastního klíče by měla dvě různé
identity pod jedním balíčkem podle distribučního kanálu. Rozhodnutí platí jen do prvního uploadu,
pak se nedá vzít zpět.

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

- **Backend** — `backend/build.gradle`, `version = '0.1.0-SNAPSHOT'`.
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
