pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // Pinned to AGP 8.x on purpose. magiclane_maps_flutter 3.1.11 — the newest
    // release that exists — compiles its android/build.gradle.kts against the
    // AGP 8 DSL (com.android.build.gradle.LibraryExtension). AGP 9 marks that
    // class @Deprecated(level = ERROR), which is a hard compile failure for any
    // consuming .gradle.kts, so AGP 9.x cannot configure this project at all.
    // 8.13.2 specifically: >= 8.9.1 is required to accept compileSdk 36, it
    // clears Flutter 3.47's AGP floor of 8.11.1, and it bundles R8 8.13.19,
    // which is the R8 that supports Kotlin 2.3 (see the KGP pin below).
    id("com.android.application") version "8.13.2" apply false
    // Kotlin 2.3.21, not 2.4.x: Google's matrix requires R8 9.1.29 for Kotlin
    // 2.4, and no AGP 8.13.x ships it. Release builds here run R8 for real
    // (isMinifyEnabled), so that mismatch would be a live minification hazard.
    // Must stay in this top-level block with `apply false`: both the magiclane
    // script (its AGP-major < 9 branch does an imperative
    // apply(plugin = "org.jetbrains.kotlin.android")) and Flutter's own
    // FlutterPluginUtils.detectApplyingKotlinGradlePlugin resolve kotlin-android
    // off this shared classpath.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}

include(":app")
