plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "cz.kvalitacena"
    compileSdk = 37

    defaultConfig {
        applicationId = "cz.kvalitacena"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

kotlin {
    // Přenositelná náhrada za dřívější org.gradle.java.home v gradle.properties — pinuje JDK
    // pro kompilaci na 17 bez ohledu na to, jaké JDK spouští Gradle daemon na daném stroji.
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    // CameraX + ZXing-C++ (Apache-2.0) — ML Kit je proprietární a váže appku na Google Play
    // Services, viz plán založení projektu. Skener je schovaný za scanner/BarcodeScanner.kt,
    // aby šla implementace vyměnit, kdyby čtení poškozených kódů dělalo problémy.
    implementation("androidx.camera:camera-core:1.6.1")
    implementation("androidx.camera:camera-camera2:1.6.1")
    implementation("androidx.camera:camera-lifecycle:1.6.1")
    implementation("androidx.camera:camera-view:1.6.1")
    implementation("io.github.zxing-cpp:android:3.1.1")

    // Refresh token v EncryptedSharedPreferences (docs/soukromi.md v backendu).
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Zobrazení fotek zboží/obchodů (core.media) — Apache-2.0, sdílí OkHttp s GraphQlClient/
    // MediaClient přes coil-network-okhttp, žádný druhý HTTP stack navíc.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    // Mapa nad OpenStreetMap (výběr/náhled souřadnic obchodu) — Apache-2.0, stejná licenční
    // politika jako ZXing/Coil výš (viz plán projektu). Dlaždice se stahují přímo z klienta,
    // vědomá výjimka z "geokódování jen ze serveru" (docs/soukromi.md) — mapa se proto načte
    // až po explicitním otevření (ui/common/LocationMap.kt), nikdy automaticky.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Jednotkové testy čisté logiky (PriceChartGeometry) bez Androidu/emulátoru.
    testImplementation("junit:junit:4.13.2")
}
