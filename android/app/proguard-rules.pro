# kotlinx.serialization genera serializadores como miembros sintéticos de las clases
# @Serializable. R8 no ve quién los usa y los borraría, y el fallo aparece sólo en
# release, al deserializar la primera respuesta de la API.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.verificlean.app.data.remote.dto.**$$serializer { *; }
-keepclassmembers class com.verificlean.app.data.remote.dto.** {
    *** Companion;
}

# Retrofit conserva los tipos genéricos de las firmas suspend a través de las
# anotaciones de firma; sin esto, `Response<VisitaDto>` se borra a `Response`.
-keepattributes Signature,Exceptions
-keep,allowobfuscation interface com.verificlean.app.data.remote.VerifiCleanApi

# OkHttp referencia clases opcionales de Conscrypt/BouncyCastle que no se empaquetan.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
