# Room generated implementations are looked up reflectively by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# Firestore serialises our model classes via reflection, so field names must survive.
-keepclassmembers class com.uhmk.pos.core.sync.dto.** { *; }
-keep class com.uhmk.pos.core.sync.dto.** { *; }
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# Firebase / Play Services
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# Kotlin coroutines internals
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
