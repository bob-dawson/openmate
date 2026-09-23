-keepattributes *Annotation*, InnerClasses, Signature

-keep class kotlinx.serialization.** { *; }
-keep class **_Serializer { *; }
-keep class * implements kotlinx.serialization.KSerializer { *; }
-keepclassmembers class * { *** Companion; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep,allowobfuscation @kotlinx.serialization.Serializable class *

-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }
-keepclassmembers class androidx.camera.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

-keep class ru.nsk.kstatemachine.** { *; }
-keep class com.openmate.app.connection.v2.ConnState { *; }
-keep class com.openmate.app.connection.v2.ConnState$* { *; }
-keep class com.openmate.app.connection.v2.ConnEvent { *; }
-keep class com.openmate.app.connection.v2.ConnEvent$* { *; }
-keep class com.openmate.app.connection.v2.Route { *; }
-keep class com.openmate.app.connection.v2.Route$* { *; }

-keep class coil.** { *; }
-dontwarn coil.**

-keep class dev.forst.markdown.** { *; }
-keep class org.commonmark.** { *; }
-keep class dev.jeziellago.compose.markdowntext.** { *; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *[] $VALUES;
}
