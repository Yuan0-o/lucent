import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// ============================================================================================
//  :desktop — the Windows build of Lucent (Compose for Desktop, plain JVM)
// ============================================================================================
//
// This module lives beside :app and never touches it. It reuses the shared Kotlin — data models,
// backup/crypto engine, i18n, business logic, and most UI — verbatim from com.lucent.app.*, and
// swaps only the genuinely platform-bound pieces for desktop implementations of the SAME package,
// name, and API. See the handover document for the architecture in full.
//
// Versions are pinned to match :app (Kotlin 2.4.0, JDK 17, haze 1.7.2, okhttp 4.12.0) and the
// desktop toolchain the root build declares (Compose Multiplatform 1.11.1). Keeping them in lockstep
// is what lets the shared source compile identically on both sides.

plugins {
    id("org.jetbrains.kotlin.jvm")
    // Provides the Compose compiler for Kotlin 2.x (the same plugin :app uses). Compose for Desktop
    // needs it exactly as the Android module does.
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The Compose for Desktop bundle for whatever OS the build runs on (Windows in CI). Pulls in the
    // runtime, ui, foundation, material and material3 for the desktop (Skiko) backend.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Extended Material icons. The shared UI uses a wide set of them (Icons.Default.*,
    // Icons.AutoMirrored.Filled.*). This accessor tracks the Compose plugin's aligned version; if CI
    // can't resolve it, pin the explicit coordinate instead (see the work report's first-run notes):
    //   implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation(compose.materialIconsExtended)

    // Coroutines: -core is the engine; -swing supplies Dispatchers.Main on the desktop (the Swing/AWT
    // event thread Compose for Desktop renders on). Same 1.8.1 line as :app's -android artifact.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    // Frosted-glass blur, identical to :app so Glass.kt compiles unchanged.
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    implementation("dev.chrisbanes.haze:haze-materials:1.7.2")

    // Networking for the cloud assistant — same version as :app.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Android ships org.json in the platform; the desktop JVM does not, so bring it in explicitly.
    // The shared code (ApiProfiles, AppLock, BackupManager, …) uses org.json.JSONObject throughout.
    implementation("org.json:json:20240303")

    // SQLite JDBC driver — io.github.willena:sqlite-jdbc, the drop-in Xerial build whose SQLite
    // core is SQLite3MultipleCiphers, i.e. it speaks the SQLCipher scheme Db.kt keys with. This is
    // what makes the desktop database encrypted at rest, restoring parity with Android; Db.kt also
    // migrates a database created by the earlier (unencrypted, org.xerial) builds in place on first
    // open. If THIS exact version ever fails to resolve, do not fall back to org.xerial — that
    // silently ships an unencrypted store; instead pick another release from
    // https://central.sonatype.com/artifact/io.github.willena/sqlite-jdbc (3.44.1.0, 3.46.1.3 and
    // 3.49.1.0 are other lines that have shipped) — the Db.kt code path is identical for all of them.
    implementation("io.github.willena:sqlite-jdbc:3.45.1.6")

    // PDF export and in-app PDF attachment preview (replaces Android's PdfRenderer with PDFBox).
    implementation("org.apache.pdfbox:pdfbox:3.0.3")
}

compose.desktop {
    application {
        mainClass = "com.lucent.desktop.MainKt"

        nativeDistributions {
            // A single "double-click to install" Windows installer, as the requirement asks. The
            // workflow uploads exactly the one .exe this produces.
            targetFormats(TargetFormat.Exe)
            packageName = "Lucent"
            packageVersion = "1.0.0"

            // The jlink runtime image jpackage builds only bundles the modules Compose declares, which
            // does NOT include java.sql — so the SQLite JDBC driver fails at runtime with
            // NoClassDefFoundError: java/sql/Driver. okhttp's HTTPS also needs jdk.crypto.ec, and some
            // libraries touch sun.misc.Unsafe (jdk.unsupported). Bundling all JDK modules is the
            // simplest guarantee that no runtime module is missing; it enlarges the installer somewhat.
            // (To slim it later, replace with e.g. modules("java.sql", "java.naming", "jdk.crypto.ec",
            // "jdk.unsupported").)
            includeAllModules = true

            windows {
                menu = true
                menuGroup = "Lucent"
                shortcut = true
                // Stable across releases so the installer upgrades in place rather than installing a
                // second copy. Must never change once shipped. (This is what makes "install the new
                // build over the old one" replace it — provided packageVersion above is bumped for the
                // new release, since the installer only upgrades to a *higher* version.)
                upgradeUuid = "8f4e2a10-1c3b-4d5e-9a7f-2b6c8d0e1f23"
                // Install into the user's profile rather than Program Files, so installing and
                // upgrading never needs administrator rights and the overwrite-upgrade is clean.
                perUserInstall = true
                // The .ico is bundled if present; the build still succeeds without it (jpackage falls
                // back to a default icon), so a missing icon never fails CI.
                val icoFile = project.file("src/main/resources/icons/lucent.ico")
                if (icoFile.exists()) iconFile.set(icoFile)
            }
        }
    }
}
