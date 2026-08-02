// AGP 9+ má podporu Kotlinu vestavěnou, samostatný org.jetbrains.kotlin.android plugin už
// není potřeba (a jeho přidání build rovnou shodí) — viz https://issuetracker.google.com/438678642.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
