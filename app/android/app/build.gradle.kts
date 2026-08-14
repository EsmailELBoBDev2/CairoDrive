import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android plugin.
    id("dev.flutter.flutter-gradle-plugin")
}

/**
 * Local, untracked overrides. `local.properties` is git-ignored, so a developer
 * can keep keys there with no risk of committing them.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}

/**
 * API keys reach Dart through `--dart-define`, which is what populates
 * `String.fromEnvironment` at compile time — Gradle cannot inject them on its
 * own. `tool/flutter-build.sh` assembles those flags from the environment (CI)
 * or from `local.properties` (developer machine).
 *
 * All Gradle does here is report presence, so a build missing a key is obvious
 * in the log without the value ever being printed.
 */
fun secretPresence(name: String): String =
    if ((System.getenv(name) ?: localProperties.getProperty(name) ?: "").isNotBlank()) {
        "present"
    } else {
        "ABSENT"
    }

logger.lifecycle(
    "CairoDrive keys — GOOGLE_PLACES_API_KEY: ${secretPresence("GOOGLE_PLACES_API_KEY")}, " +
        "MAGICLANE_API_KEY: ${secretPresence("MAGICLANE_API_KEY")}"
)

/**
 * Signing config, populated only when the keystore is actually present.
 * CI materialises key.properties from CAIRODRIVE_KEYSTORE_BASE64 and the three
 * password/alias secrets before invoking Gradle, and deletes it afterwards.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.cairodrive.app"
    // Pinned to 36: magiclane_maps_flutter 3.1.11 declares compileSdk = 36, and
    // AGP requires the app to compile against at least its libraries' level.
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.cairodrive.app"
        // The Magic Lane plugin declares minSdk 21; 24 keeps us clear of
        // legacy-multidex and Java-8-desugaring quirks.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Distinct id so debug and release can coexist on one device, and
            // so neither can ever collide with another navigation app.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // Lets a keyless run still produce an APK; the workflow prints
                // the signer certificate, so a debug-signed artifact is visible
                // rather than silently mistaken for a release-signed one.
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // Android Auto, via the public AndroidX Car App Library.
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
}
