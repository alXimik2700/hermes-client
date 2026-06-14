# Hermes Messenger ProGuard Rules

# Keep Compose
-keep class androidx.compose.** { *; }
-keep class kotlin.coroutines.** { *; }

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Keep Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep ML Kit Barcode
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# Keep security-crypto (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }

# Keep data classes for JSON serialization
-keep class com.hermes.messenger.data.** { *; }

# Keep BuildConfig
-keep class com.hermes.messenger.BuildConfig { *; }

# Keep SecureConfig (accessed via reflection by EncryptedSharedPreferences)
-keep class com.hermes.messenger.SecureConfig { *; }
