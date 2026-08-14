import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("dev.flutter.flutter-gradle-plugin")
}

/**
 * Local, untracked overrides. `local.properties` is git-ignored, so a developer
 * can put keys there without any risk of committing them.
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
 * CI materialises it from CAIRODRIVE_KEYSTORE_BASE64 before invoking Gradle.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.cairodrive.app"
    compileSdk = 35
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "com.cairodrive.app"
        // Magic Lane SDK requires API 23+; 24 keeps us clear of legacy quirks.
        minSdk = 24
        targetSdk = 35
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
        getByName("debug") {
            // Distinct from release so both can be installed side by side, and
            // so neither ever collides with any other navigation app.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        getByName("release") {
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // Falls back to the debug key so a keyless CI run still builds;
                // the workflow verifies the signer, so a mis-signed artifact
                // cannot pass unnoticed.
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

    packaging {
        // The engine ships large prebuilt native libraries.
        jniLibs.useLegacyPackaging = false
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
}
