import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Podpisový klíč pro release (docs/vydani.md) — hesla z Gradle properties
// (~/.gradle/gradle.properties nebo -P na příkazové řádce), nikdy natvrdo v buildu. Chybí-li
// kterákoli hodnota, signingConfig se vůbec nezaloží a assembleRelease vyrobí nepodepsané APK
// místo pádu — build musí projít i na cizím stroji a v CI, stejná logika jako jvmToolchain(17)
// místo org.gradle.java.home v gradle.properties.
val releaseStoreFile = findProperty("KVALITACENA_STORE_FILE") as String?
val releaseStorePassword = findProperty("KVALITACENA_STORE_PASSWORD") as String?
val releaseKeyAlias = findProperty("KVALITACENA_KEY_ALIAS") as String?
val releaseKeyPassword = findProperty("KVALITACENA_KEY_PASSWORD") as String?
val hasReleaseSigning = releaseStoreFile != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

// Poskytovatel mapových dlaždic (ui/common/OsmMapView.kt, MapConfig.kt) — konfigurovatelný ze
// stejného důvodu jako BASE_URL níž: property s fallbackem na dnešní hodnotu, aby šla výměna
// poskytovatele udělat bez zásahu do kódu. Stejné pro debug i release (na rozdíl od BASE_URL),
// proto v defaultConfig, ne per buildType. Web má obdobu v shared/map-tiles.ts (tam bez
// runtime konfigurace, viz komentář tam).
val mapTileUrl = (findProperty("KVALITACENA_MAP_TILE_URL") as String?)
    ?: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
val mapTileAttribution = (findProperty("KVALITACENA_MAP_TILE_ATTRIBUTION") as String?)
    ?: "© OpenStreetMap contributors"

android {
    namespace = "cz.kvalitacena"
    compileSdk = 37

    defaultConfig {
        applicationId = "cz.kvalitacena"
        minSdk = 26
        targetSdk = 36
        // Generováno tools/version/sync.mjs z kořenového VERSION — needituj ručně.
        versionCode = 603
        versionName = "0.6.3"

        buildConfigField("String", "MAP_TILE_URL", "\"$mapTileUrl\"")
        buildConfigField("String", "MAP_TILE_ATTRIBUTION", "\"$mapTileAttribution\"")
        buildConfigField("int", "MAP_TILE_MAX_ZOOM", "19")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Emulátor → hostitel; cleartext povolený jen tady
            // (src/debug/res/xml/network_security_config.xml).
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080\"")
        }
        release {
            buildConfigField("String", "BASE_URL", "\"https://api.kvalitacena.cz\"")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Připraveno pro budoucí vlastní nativní kód — u dnešních závislostí (ZXing-cpp,
            // CameraX) to Play Console upozornění "Nahrajte soubor se symboly" NEřeší: jejich
            // .so knihovny přicházejí z Maven artefaktů už stripnuté (ověřeno `readelf -S`,
            // žádná .debug_*/.symtab sekce), takže AGP nemá odkud symboly vytáhnout. Bezpečné
            // nechat zapnuté — jen čistě informativní upozornění appku nijak neblokuje.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME pro sekci "Zdroje dat" v nastavení
    }

    // AGP 9 nástupce resConfigs — appka zná jen cs/sk/en/pl/de (docs/lokalizace.md), balit
    // stringy dalších jazyků z knihoven (Compose, AndroidX) do APK nemá smysl. "de" tu chybělo
    // od chvíle, kdy appka přidala němčinu jako pátý jazyk (commit 58ec6a7) — values-de/ i
    // AppLang.DE existovaly, ale bez filtru se z APK tiše vyhazovaly.
    androidResources {
        localeFilters += listOf("cs", "sk", "en", "pl", "de")
    }

    // MissingTranslation/ExtraTranslation/MissingQuantity jako error — hlídá, že values-*/
    // nezaostanou za values/ (čeština, zdroj i fallback — docs/lokalizace.md) a že <plurals>
    // mají všechny tvary, které dané jazykové pravidlo (cs/sk/pl 4 kategorie, en 2) vyžaduje.
    lint {
        error += listOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
        abortOnError = true
    }
}

kotlin {
    // Přenositelná náhrada za dřívější org.gradle.java.home v gradle.properties — pinuje JDK
    // pro kompilaci na 17 bez ohledu na to, jaké JDK spouští Gradle daemon na daném stroji.
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Jen kvůli AppCompatDelegate.setApplicationLocales() (docs/lokalizace.md) — deleguje na
    // systémový LocaleManager od API 33, pod tím si volbu persistuje sama. Vyžaduje
    // AppCompatActivity (MainActivity.kt) — bez zaregistrované AppCompatDelegate instance je
    // setApplicationLocales() no-op na všech API úrovních.
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.navigation:navigation-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    // CameraX + ZXing-C++ (Apache-2.0) — ML Kit je proprietární a váže appku na Google Play
    // Services, což jde proti pravidlu jen svobodných knihoven (CLAUDE.md, "Konvence"). Skener
    // je schovaný za scanner/BarcodeScanner.kt, aby šla implementace vyměnit, kdyby čtení
    // poškozených kódů dělalo problémy.
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("io.github.zxing-cpp:android:3.1.1")

    // Refresh token v EncryptedSharedPreferences (docs/soukromi.md v backendu).
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Zobrazení fotek zboží/obchodů (core.media) — Apache-2.0, sdílí OkHttp s GraphQlClient/
    // MediaClient přes coil-network-okhttp, žádný druhý HTTP stack navíc.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    // Mapa nad OpenStreetMap (výběr/náhled souřadnic obchodu) — Apache-2.0, stejná licenční
    // politika jako ZXing/Coil výš. Dlaždice se stahují přímo z klienta od
    // poskytovatele nastaveného výš (KVALITACENA_MAP_TILE_URL, výchozí OpenStreetMap Mapnik),
    // vědomá výjimka z "geokódování jen ze serveru" (docs/soukromi.md) — mapa se proto načte
    // až po explicitním otevření (ui/common/LocationMap.kt), nikdy automaticky.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Jednotkové testy čisté logiky (PriceChartGeometry) bez Androidu/emulátoru.
    testImplementation("junit:junit:4.13.2")
}

// ---- Publikace: podepsaný a OVĚŘENÝ release (docs/vydani.md, "Podpisový klíč" a "Nahrávaný
// formát a App access") — na rozdíl od assembleRelease/bundleRelease, které musí projít i bez
// klíče (CI je staví nepodepsané, viz komentář u hasReleaseSigning výš), publishableBundle/Apk
// bez klíče SELŽE, a to dřív, než se spustí zdlouhavý R8 build. ----

/** Selže s vyjmenovanými chybějícími `KVALITACENA_*` properties nebo neexistujícím keystorem. */
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    group = "publishing"
    description = "Ověří, že jsou k dispozici všechny KVALITACENA_* podpisové property a keystore existuje."
    val missingProperties = listOfNotNull(
        "KVALITACENA_STORE_FILE".takeIf { releaseStoreFile == null },
        "KVALITACENA_STORE_PASSWORD".takeIf { releaseStorePassword == null },
        "KVALITACENA_KEY_ALIAS".takeIf { releaseKeyAlias == null },
        "KVALITACENA_KEY_PASSWORD".takeIf { releaseKeyPassword == null },
    )
    val storeFileExists = releaseStoreFile?.let { file(it).isFile } ?: false
    val storeFilePath = releaseStoreFile
    doLast {
        if (missingProperties.isNotEmpty()) {
            throw GradleException(
                "Chybí podpisové property: ${missingProperties.joinToString()} " +
                    "— viz docs/vydani.md, \"Podpisový klíč\"."
            )
        }
        if (!storeFileExists) {
            throw GradleException("KVALITACENA_STORE_FILE ('$storeFilePath') neukazuje na existující soubor.")
        }
    }
    // Vždy znovu vyhodnotit — je to levná kontrola, "up-to-date" by tu skrylo zaniklý klíč.
    outputs.upToDateWhen { false }
}

/**
 * Ověří podpis AAB. Bundle se podepisuje jarem (v2/v3 APK Signature Scheme se u něj nepoužívá)
 * — nástroj je `jarsigner`, ne `apksigner` (ten AAB neumí ověřit). `-strict` nejde použít: náš
 * keystore je self-signed, jarsigner by ho i tak nahlásil jako chybu (chainNotValidated) —
 * kontroluje se proto přítomnost "jar verified" ve výstupu.
 */
abstract class VerifyJarSignatureTask : DefaultTask() {
    @get:InputFile
    abstract val artifact: RegularFileProperty

    @get:Internal
    abstract val jarsigner: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(
                jarsigner.get().asFile.absolutePath, "-verify", "-verbose:summary", "-certs",
                artifact.get().asFile.absolutePath,
            )
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        val text = output.toString()
        if (result.exitValue != 0 || !text.contains("jar verified")) {
            throw GradleException("${artifact.get().asFile.name} není platně podepsaný:\n$text")
        }
        logger.lifecycle("Podpis ověřen: ${artifact.get().asFile.name}")
    }
}

/** Obdoba [VerifyJarSignatureTask] pro APK — tam `apksigner` funguje a je to správný nástroj. */
abstract class VerifyApkSignatureTask : DefaultTask() {
    @get:InputFile
    abstract val artifact: RegularFileProperty

    @get:Internal
    abstract val apksigner: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(apksigner.get().asFile.absolutePath, "verify", "--print-certs", artifact.get().asFile.absolutePath)
            standardOutput = output
            errorOutput = output
            isIgnoreExitValue = true
        }
        val text = output.toString()
        if (result.exitValue != 0) {
            throw GradleException("${artifact.get().asFile.name} není platně podepsaný:\n$text")
        }
        logger.lifecycle("Podpis ověřen: ${artifact.get().asFile.name}\n$text")
    }
}

// jarsigner z JDK toolchainu (kotlin { jvmToolchain(17) } výš), ne z JAVA_HOME — musí to být
// stejné JDK, se kterým appka fakticky staví.
val jarsignerPath = project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}.map { it.metadata.installationPath.file("bin/jarsigner") }

// apksigner ze sdk.dir v local.properties — stejný soubor, ze kterého ho čte i AGP. Verze podle
// docs/vydani.md, "Podpisový klíč"; fallback na nejvyšší nainstalovanou, kdyby se přestala
// instalovat právě tahle.
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { stream -> load(stream) }
}
val sdkDir = localProperties.getProperty("sdk.dir")
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
val preferredBuildToolsVersion = "36.0.0"
val apksignerPath = provider {
    val buildToolsRoot = sdkDir?.let { file("$it/build-tools") }
    val preferred = buildToolsRoot?.resolve(preferredBuildToolsVersion)?.takeIf { it.isDirectory }
    val newestInstalled = buildToolsRoot?.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
    (preferred ?: newestInstalled)?.resolve("apksigner")
        ?: throw GradleException(
            "apksigner nenalezen — chybí sdk.dir v local.properties, nebo build-tools v $buildToolsRoot " +
                "(doinstaluj přes Android SDK Manager)."
        )
}

val verifyBundleSignature = tasks.register<VerifyJarSignatureTask>("verifyBundleSignature") {
    group = "publishing"
    description = "Ověří jarsignerem podpis app/build/outputs/bundle/release/app-release.aab."
    artifact.set(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
    jarsigner.set(jarsignerPath)
    dependsOn("bundleRelease")
}

val verifyApkSignature = tasks.register<VerifyApkSignatureTask>("verifyApkSignature") {
    group = "publishing"
    description = "Ověří apksignerem podpis app/build/outputs/apk/release/app-release.apk."
    artifact.set(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    apksigner.fileProvider(apksignerPath)
    dependsOn("assembleRelease")
}

// checkReleaseSigning musí doběhnout PŘED zdlouhavým R8 buildem, ne až po něm. AGP registruje
// bundleRelease/assembleRelease až v afterEvaluate (variant API), tady v afterEvaluate už s
// jistotou existují — tasks.named() na neregistrovaný task by jinak selhal rovnou při
// konfiguraci, i kdyby se nikdy nespustil.
afterEvaluate {
    tasks.named("bundleRelease") { mustRunAfter(checkReleaseSigning) }
    tasks.named("assembleRelease") { mustRunAfter(checkReleaseSigning) }
}

tasks.register("publishableBundle") {
    group = "publishing"
    description = "Podepsaný a ověřený AAB pro Play — na rozdíl od bundleRelease BEZ klíče selže."
    dependsOn(checkReleaseSigning, "bundleRelease", verifyBundleSignature)
}

tasks.register("publishableApk") {
    group = "publishing"
    description = "Podepsané a ověřené APK pro přímou distribuci/F-Droid — na rozdíl od assembleRelease BEZ klíče selže."
    dependsOn(checkReleaseSigning, "assembleRelease", verifyApkSignature)
}
