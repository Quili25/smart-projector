# The update check parses latest.json with kotlinx.serialization. R8 full mode
# otherwise strips the generated serializer, and a published build would never
# notice a newer one.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class mx.quili.smartprojector.updates.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class mx.quili.smartprojector.updates.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class mx.quili.smartprojector.updates.**$$serializer { *; }
