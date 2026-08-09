# kotlinx-serialization — @Serializable DTO třídy v network/Dto.kt se serializují/deserializují
# reflexí přes generovaný $serializer, R8 by je jinak jako "nepoužité" odstranil nebo přejmenoval.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class cz.kvalitacena.network.**$$serializer { *; }
-keepclassmembers class cz.kvalitacena.network.** {
    *** Companion;
}
-keepclasseswithmembers class cz.kvalitacena.network.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Coil 3 — načítání obrázků (PhotoGallery) přes reflexi/ServiceLoader hledá implementace
# ImageLoaderFactory a network fetcherů; oficiální doporučená pravidla projektu.
-keep,includedescriptorclasses class coil3.** { *; }
-keep class coil3.network.okhttp.** { *; }

# osmdroid — mapa (LocationMap.kt) čte konfiguraci a dlaždice přes reflexi/XML parsing.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
