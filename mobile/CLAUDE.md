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
spolehlivější). Instalace/spuštění: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
+ `adb shell am start -n cz.kvalitacena/.MainActivity`. Emulátor vidí hostitelský backend na
`10.0.2.2:8080` (viz `network/ApiConfig.kt` a `network_security_config.xml`, který tam
cleartext HTTP výslovně povoluje jen pro dev). `../start-dev.sh` z kořenu repa udělá build,
spuštění emulátoru, instalaci i logcat automaticky (autodetekuje AVD, `--no-mobile` to vypne).

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
- Odsazení 4 mezery (na rozdíl od zbytku monorepa) — viz `.editorconfig`
