plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// ---- Versioning ----
//
// Two numbers, two jobs, and conflating them is what made releases look like they skipped ahead.
//
//   versionCode  An integer Android uses ONLY to decide "is this newer than what's installed?".
//                Users never see it. It must never go backwards, so CI passes the workflow run
//                number: it increases by one on every run, which is exactly what this field wants.
//
//   versionName  The string people actually read ("1.0.0"). It is NOT tied to the run number any
//                more. It comes from MARKETING_VERSION below, or from -PversionName=... which the
//                workflow's optional "Version name" box supplies. Build ten times in a row and it
//                stays 1.0.0; change it only when you decide a release deserves a new number.
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

// The user-facing version. Edit this line (or type a value into the workflow box) to change it.
val MARKETING_VERSION = "1.0.0"
val ciVersionName = (project.findProperty("versionName") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: MARKETING_VERSION

android {
    namespace = "com.lucent.app"
    compileSdk = 36

    // Native code arrives with the local-model feature (llama.cpp via CMake) and the Rust
    // acceleration library. r27 is the LTS line AGP 8.12 targets; CI runners carry it and AGP
    // auto-installs it locally when missing.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.jiaying.yuan.lucentapp"
        // The Vulkan GPU backend calls Vulkan 1.1 core functions (e.g. vkGetPhysicalDeviceFeatures2),
        // which Android only exposes from API 28 (Android 9) — the NDK's API-26 libvulkan.so stub
        // doesn't export them, so linking fails below 28. This is also what upstream llama.cpp uses to
        // build Vulkan for Android (ANDROID_PLATFORM=android-28), and AGP derives ANDROID_PLATFORM
        // from minSdk. So the default (GPU) build needs minSdk 28; a -PcpuOnly build has no Vulkan and
        // keeps the wider Android 8.0 (API 26) reach.
        minSdk = if (project.hasProperty("cpuOnly")) 26 else 28
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName

        ndk {
            // Ship the two ABIs every real Android phone uses: arm64-v8a (all modern devices) and
            // armeabi-v7a (older 32-bit phones). x86_64 is intentionally dropped — no shipping phone
            // uses it, and building llama.cpp from source for a third ABI is the slowest, flakiest
            // part of CI. To also target the x86_64 emulator, re-add "x86_64" here AND in the
            // cargo-ndk "-t" list below (and add the aarch64/armv7/x86_64 Rust targets in CI).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                // The GPU (Vulkan) backend for the on-device model is compiled IN by default, so the
                // in-app CPU/GPU switch can really offload to the GPU. The switch itself still
                // defaults to CPU and warns before enabling GPU; and if a device's Vulkan driver
                // can't handle it, LocalLlm falls back to CPU instead of crashing (see
                // cpp/CMakeLists.txt and LocalLlm.kt). The shader compiler (glslc) is taken from the
                // NDK, so no extra tooling install is required.
                //
                // Build a smaller, CPU-only APK with -PcpuOnly (e.g. if the Vulkan build ever fails).
                arguments += "-DLUCENT_ENABLE_VULKAN=${if (project.hasProperty("cpuOnly")) "OFF" else "ON"}"

                // llama.cpp's Vulkan backend does find_package(SPIRV-Headers CONFIG REQUIRED). That
                // package isn't in the NDK, and cross-compiling normally restricts find_package to the
                // NDK sysroot — so CI installs SPIRV-Headers on the host and points this env var at its
                // config dir. Pass it through as SPIRV-Headers_DIR, and allow find_package to look
                // outside the sysroot (BOTH) so a host, header-only package resolves. Only Vulkan
                // builds set the env var; a -PcpuOnly build ignores all of this.
                System.getenv("LUCENT_SPIRV_HEADERS_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
                    arguments += "-DSPIRV-Headers_DIR=$dir"
                    arguments += "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH"
                }

                // ggml-vulkan.cpp includes the C++ Vulkan header <vulkan/vulkan.hpp>, which the NDK
                // does NOT ship (it carries only the C header vulkan.h). CI provides Vulkan-Headers
                // (which has vulkan.hpp) and points this env var at its include dir; pass it as
                // Vulkan_INCLUDE_DIR so find_package(Vulkan) uses those headers. The Android
                // libvulkan.so from the NDK is still used for linking — only the headers change.
                System.getenv("LUCENT_VULKAN_INCLUDE_DIR")?.takeIf { it.isNotBlank() }?.let { inc ->
                    arguments += "-DVulkan_INCLUDE_DIR=$inc"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            // Where the cargo-ndk hook below drops liblucent_native.so per ABI. When the Rust
            // toolchain isn't present this directory simply stays empty and the app runs on its
            // Kotlin fallbacks — building without Rust must always keep working.
            jniLibs.srcDir(layout.buildDirectory.dir("rustJniLibs"))
        }
    }

    // ---- Release signing, fed entirely by environment variables ----
    //
    // No keystore and no password live in this repository. The GitHub Actions workflow decodes the
    // keystore out of a repository *secret* into a temp file and exports these variables for the
    // one Gradle invocation; on a machine without them the release build still assembles — just
    // unsigned — so a plain checkout of this repo can always be built by anyone.
    //
    //   LUCENT_KEYSTORE_FILE      absolute path to the decoded keystore (PKCS12)
    //   LUCENT_KEYSTORE_PASSWORD  store password (the same password protects the key)
    //   LUCENT_KEY_ALIAS          key alias; defaults to "lucent" when unset
    val releaseStorePath = System.getenv("LUCENT_KEYSTORE_FILE")
    signingConfigs {
        if (releaseStorePath != null) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = System.getenv("LUCENT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LUCENT_KEY_ALIAS") ?: "lucent"
                keyPassword = System.getenv("LUCENT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // findByName returns null when the environment isn't set, which simply leaves the APK
            // unsigned rather than failing the build.
            signingConfig = signingConfigs.findByName("release")
        }
        // debug uses the SDK's own auto-generated debug key. The previously checked-in
        // lucent-debug.keystore (with its password in this file) is gone: a signing key committed
        // to a repository authenticates nobody.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// ---- Rust acceleration library (rust/ at the repo root) ----
//
// Built with cargo-ndk when — and only when — the toolchain is on the PATH. The library is a pure
// accelerator with Kotlin fallbacks everywhere it's consulted (see nativebridge/LucentNative), so
// a machine without Rust still produces a fully working APK; it just runs the JVM crypto and the
// Kotlin animation math instead. CI installs the toolchain (see .github/workflows/build.yml), so
// release builds always carry the fast path.
val rustProjectDir = rootProject.file("rust")
val rustOutDir = layout.buildDirectory.dir("rustJniLibs")

fun toolWorks(vararg cmd: String): Boolean = try {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    p.inputStream.readBytes()
    p.waitFor() == 0
} catch (t: Throwable) {
    false
}

val cargoNdkReady = rustProjectDir.exists() && toolWorks("cargo", "ndk", "--version")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Compile rust/ into liblucent_native.so for every packaged ABI"
    workingDir = rustProjectDir
    commandLine(
        "cargo", "ndk",
        // Must stay in lockstep with abiFilters above. To add the x86_64 emulator, add
        // "-t", "x86_64" here and re-add it to abiFilters.
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-o", rustOutDir.get().asFile.absolutePath,
        "build", "--release"
    )
}

if (cargoNdkReady) {
    tasks.named("preBuild") { dependsOn(cargoNdkBuild) }
} else {
    logger.lifecycle(
        "lucent: cargo-ndk not found — building WITHOUT liblucent_native.so " +
            "(the app falls back to its Kotlin implementations; install rustup + `cargo install cargo-ndk` " +
            "and the Android targets to enable the Rust fast paths)"
    )
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("dev.chrisbanes.haze:haze:1.7.2")
    implementation("dev.chrisbanes.haze:haze-materials:1.7.2")
}
