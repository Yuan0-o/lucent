# ---- Lucent R8 / ProGuard configuration ----
#
# Deliberately small. Most of what the app depends on ships its own consumer rules (Compose, Room,
# OkHttp, DataStore, Haze), and getDefaultProguardFile("proguard-android-optimize.txt") already
# keeps native-method names and the usual Android entry points. What remains here is the JNI bridge,
# whose symbol names must match the Rust side exactly, plus a few belt-and-braces rules for the
# encrypted-database and networking stacks so a stripped optional dependency can't fail the build.
#
# Components declared in AndroidManifest.xml (MainActivity, the ShareTarget alias, the widget
# providers, and the services/receivers) are kept automatically by the manifest keep, so they are
# not listed here.

# --- JNI bridge to the Rust native library (liblucent_native) ---
# The native methods are resolved by name across the JNI boundary. Renaming the holder class or its
# native methods would break linkage at runtime (UnsatisfiedLinkError), so keep them verbatim. The
# generic native-method rule is also in the -optimize default file; repeating it here is harmless
# and makes the intent explicit.
-keep class com.lucent.app.nativebridge.LucentNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- JNI bridge to the on-device model engine (liblucent_llama, local/LocalLlm.kt) ---
# lucent_llama.cpp streams tokens back into Kotlin by looking up the callback method BY NAME:
#   GetMethodID(cbClass, "onPiece", "(Ljava/lang/String;)V")
# `onPiece` is never called from Java/Kotlin code (only from native), so without these rules R8
# strips or renames it in every minified release build. GetMethodID then fails and each local
# generation dies with a NoSuchMethodError the Kotlin side reports as error code -20 (or -2) —
# the model loads fine but never produces a single token. Keep the holder, the callback interface,
# and the onPiece implementation on every class that implements it (the anonymous object in
# LocalLlm.generate), all by their real names.
-keep class com.lucent.app.local.LocalLlm { *; }
-keep interface com.lucent.app.local.LocalLlm$PieceCallback { *; }
-keepclassmembers class * implements com.lucent.app.local.LocalLlm$PieceCallback {
    public void onPiece(java.lang.String);
}

# --- SQLCipher (net.zetetic:sqlcipher-android) ---
# JNI-backed encrypted SQLite. Keep its classes and silence warnings about its optional references.
-keep class net.sqlcipher.** { *; }
-keep interface net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# --- Room ---
# Room ships consumer rules and generates the *_Impl classes; this is a light safety net.
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- OkHttp / Okio ---
# Both ship rules; these only silence warnings for their compile-only platform integrations that
# aren't present on Android (they are never loaded at runtime).
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Kotlin coroutines ---
-dontwarn kotlinx.coroutines.**

# --- Haze (Compose blur library) ---
-dontwarn dev.chrisbanes.haze.**

# --- Enums looked up by name (MemoryTier.fromKey, LucentThemeMode.fromKey, ExportFormat, …) ---
# These use Kotlin `entries`, but keep the synthesized accessors so nothing surprising is stripped.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
