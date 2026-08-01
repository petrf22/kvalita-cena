// AGP 9+ má podporu Kotlinu vestavěnou, samostatný org.jetbrains.kotlin.android plugin už
// není potřeba (a jeho přidání build rovnou shodí) — viz https://issuetracker.google.com/438678642.
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
}
