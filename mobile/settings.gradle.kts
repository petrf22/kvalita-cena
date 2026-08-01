pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Umožní Gradlu chybějící JDK pro toolchain (viz app/build.gradle.kts) sám dostáhnout,
    // místo aby build spadl na cizím stroji, kde požadovaná verze není nainstalovaná.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kvalita-a-cena-mobile"
include(":app")
