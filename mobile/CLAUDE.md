# CLAUDE.md — mobil

Konvence a příkazy specifické pro `mobile/` (Kotlin + Jetpack Compose, nativní Android).
Cross-cutting pravidla (jazyk komentářů, licence knihoven, architektura sdílená napříč
aplikacemi) jsou v kořenovém [`CLAUDE.md`](../CLAUDE.md).

## Příkazy

Vlastní Gradle wrapper (9.6.1, AGP vyžaduje ≥ 9.4.1 — stejná verze jako backend). Volba JDK je
přenositelná přes Gradle toolchain (`kotlin { jvmToolchain(17) }` v `app/build.gradle.kts`,
`foojay-resolver-convention` v `settings.gradle.kts` dotáhne chybějící JDK samo), ne přes
`org.gradle.java.home` — ten by na cizím stroji (i v CI) build hned na startu shodil. **AGP 9+
už nepotřebuje plugin `org.jetbrains.kotlin.android`** (Kotlin podpora je vestavěná) —
nepřidávej ho zpět, build by rovnou spadl. `compileSdk 37` (víc novějších knihoven —
activity-compose, core-ktx, okhttp-android — to vyžaduje), `minSdk 26`, `targetSdk 36` (Play
od 31. 8. 2026 odmítá nižší, viz `../docs/vydani.md`); AGP chybějící SDK komponenty (platformy,
build-tools) při buildu sám dostáhne.

```bash
./gradlew :app:assembleDebug
./gradlew :app:compileDebugKotlin     # rychlejší kontrola bez balení APK
./gradlew :app:publishableBundle      # podepsaný a ověřený AAB pro Play — na rozdíl od bundleRelease bez klíče selže (docs/vydani.md)
```

Emulátor (AVD `Medium_Phone`) je vyzkoušený a funkční — `~/Android/Sdk/emulator/emulator -avd
Medium_Phone -no-snapshot -no-boot-anim -gpu swiftshader_indirect` (Mesa/X11 GPU passthrough
v tomto stroji párkrát spadl s X errorem, `swiftshader_indirect` /software renderování/ je
spolehlivější). Založení AVD na novém stroji (aby byla na všech vývojových strojích stejná —
profil `medium_phone` zná až moderní `cmdline-tools`, ne staré `tools/`, jejichž `avdmanager`
na dnešním JDK ani nenaběhne):

```bash
sdkmanager "system-images;android-36;google_apis;x86_64"   # targetSdk projektu, BEZ Play Store
avdmanager create avd -n Medium_Phone -k "system-images;android-36;google_apis;x86_64" -d medium_phone
sed -i '/^disk\.dataPartition\.path=<temp>$/d;s/^disk\.dataPartition\.size=.*/disk.dataPartition.size=6G/;s/^hw\.ramSize=.*/hw.ramSize=4096M/' \
  ~/.android/avd/Medium_Phone.avd/config.ini
```

Obraz je záměrně `google_apis`, ne `google_apis_playstore` — appka má běžet i bez GMS (viz
Konvence níž), na Play obrazu by se to neověřilo. `sed` je nutný: výchozí datový oddíl je
`<temp>` (po vypnutí by zahodil nainstalované appky) a 800 MB s 1,5 GB RAM.

Instalace/spuštění: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
+ `adb shell am start -n cz.kvalitacena/.MainActivity`. Emulátor vidí hostitelský backend na
`10.0.2.2:8080` (viz `network/ApiConfig.kt` a `network_security_config.xml`, který tam
cleartext HTTP výslovně povoluje jen pro dev). `../start-dev.sh` z kořenu repa udělá build,
spuštění emulátoru, instalaci i logcat automaticky (autodetekuje AVD, `--no-mobile` to vypne).

## Ladění v emulátoru

**Stav UI ověřuj `python3 tools/mobile/ui.py dump`, ne screenshotem. Screenshot pořizuj
VÝHRADNĚ na výslovné vyžádání uživatele.** Filtrovaný výpis obrazovky má kolem 330 bajtů,
syrový `uiautomator dump` přes 16 kB a screenshot stojí přibližně tisíc a půl tokenů — obrázek
je tedy nejdražší a přitom nejméně přesný způsob, jak zjistit, co je na displeji. `ui.py
screenshot` proto vypisuje jen cestu k souboru: obrázek je pro oko uživatele, ne ke čtení.

Není to automatizované testování (konvence „obrazovky se netestují" níž platí dál) — je to
způsob, jak ruční checklist odklikat textově.

| příkaz | k čemu |
| --- | --- |
| `ui.py setup` | jednorázově po startu emulátoru: vypne animace (bez toho `dump` občas selže na „could not get idle state") |
| `ui.py dump` | co je na obrazovce; `--all` bez slučování, `--raw` původní XML |
| `ui.py tap "Zadat cenu"` | klepne podle textu nebo `contentDescription`; víc shod → vypíše je a chce `--nth` |
| `ui.py tap-xy 540 2233` | klepnutí na souřadnice z dumpu |
| `ui.py text 8594001234585 [--clear]` | psaní do zaostřeného pole |
| `ui.py key back` | `back`/`enter`/`del`/`home`/… |
| `ui.py wait "Uložit" [--gone]` | čekání na obrazovku místo `sleep` |
| `ui.py open "product/<uuid>"` | skok rovnou na obrazovku |
| `ui.py log [--clear] [--level D]` | logcat filtrovaný na PID appky |

`ui.py open` posílá `MainActivity` intent extra `route` (`DebugRouteIntent` tamtéž), který se
čte **jen v debug buildu** — funguje pro libovolnou trasu z `ui/navigation/AppDestinations.kt`
včetně query parametrů (`price_entry?barcode=…`, `product_form?productId=…`). Ve vydané appce
je blok mrtvý a manifest se kvůli ladění nemění.

Na co se naráží:

- **`adb shell input text` neumí diakritiku** — testovací data volit v ASCII (e-maily, EANy
  a ceny jimi jsou beztak). `ui.py text` navíc po zápisu ověří, co v poli doopravdy skončilo:
  `input text` občas závodí se zaostřením pole a zdvojí první znak.
- **`resource-id` je u Compose skoro vždy prázdný**, prvky se proto hledají podle textu
  a `contentDescription`. Kdyby to přestalo stačit, další krok je `testTagsAsResourceId`
  a `testTag` na komponentách — zatím není potřeba.
- Emulátor a AVD viz „Příkazy" výš; appku nainstaluje a spustí `../start-dev.sh`.

## Konvence

- Gradle **Kotlin DSL** (Android konvence) — `applicationId`, package `cz.kvalitacena.*`
- Jeden Activity + Compose Navigation (`ui/<feature>/XxxScreen.kt` + `XxxViewModel.kt`), ruční
  DI přes `AppContainer` (bez Hiltu — appka je malá), skener schovaný za
  `scanner/BarcodeScanner.kt` rozhraní (implementace `ZxingBarcodeScanner`), refresh token jen
  v `EncryptedSharedPreferences` (`auth/TokenStore.kt`), poloha přes obyčejný `LocationManager`
  (`location/LocationHelper.kt`), ne Play Services Fused Location — appka má běžet i bez GMS
- **ViewModely a obrazovky se automatizovaně netestují** — appka se v nich testuje ručně,
  checklistem co odklikat. Výjimka je čistá logika bez závislosti na Androidu (validace
  formulářů, výpočty pro graf, i18n kontrakty) vytažená do vlastního souboru — ta JUnit testy
  má (`src/test/java/cz/kvalitacena/`, přes deset souborů dnes) a při přidání dalšího nápadu
  na testovatelnou logiku je žádoucí ji stejně vytáhnout a otestovat.
- **UI hodnoty** (rozestupy, hierarchie typografie, barvy) ber z `../docs/design.md`:
  rozestupy přes `Spacing.*` (`ui/theme/Spacing.kt`), písmo výhradně přes
  `MaterialTheme.typography.*` (hardcoded `.sp` appka nemá ani jednou), barvy přes
  `MaterialTheme.colorScheme`. Nová obrazovka musí obsloužit čtyři stavy: načítání,
  prázdný, chyba, offline — a ověřit se ve světlém i tmavém režimu
- Odsazení 4 mezery (na rozdíl od zbytku monorepa) — viz `.editorconfig`
