# R8 rules for the release build (T-49). Hilt, Room, Retrofit, OkHttp, WorkManager, and DataStore
# all ship consumer rules in their artifacts; what needs explicit care here is kotlinx-serialization:
# the Retrofit converter looks serializers up at runtime (serializer(type)), so the generated
# $$serializer classes and the Companion serializer() entry points for our DTOs must survive
# shrinking/obfuscation or every API call dies with SerializationException in release only.

-keepattributes *Annotation*, InnerClasses, Signature

# Generated serializers for this app's @Serializable classes (Dto.kt, Price, ...).
-keep,includedescriptorclasses class org.p23q.shoppinglist.**$$serializer { *; }
-keepclassmembers class org.p23q.shoppinglist.** {
    *** Companion;
}
-keepclasseswithmembers class org.p23q.shoppinglist.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx-serialization-json internals resolved reflectively.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink (via androidx.security-crypto) references Google ErrorProne annotations that are
# compile-time-only and absent at runtime by design — safe to ignore (Tink's own guidance).
-dontwarn com.google.errorprone.annotations.**
