plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release-Signing-Properties werden aus ~/.gradle/gradle.properties gelesen.
// Fehlt der Keystore (z.B. in Debug-/CI-Builds), wird der Release-Build
// unsigniert erzeugt — Upload zum Play-Store erfordert dann manuelles Signieren
// oder einen vorhandenen Keystore. Erwartete Properties:
//   speakeasyKeystorePath=/absoluter/pfad/speakeasy-release.jks
//   speakeasyKeystorePassword=…
//   speakeasyKeyAlias=speakeasy
//   speakeasyKeyPassword=…
val keystorePath = (findProperty("speakeasyKeystorePath") as? String)?.takeIf { it.isNotBlank() }
val keystorePass = (findProperty("speakeasyKeystorePassword") as? String).orEmpty()
val ksKeyAlias = (findProperty("speakeasyKeyAlias") as? String).orEmpty()
val ksKeyPass = (findProperty("speakeasyKeyPassword") as? String).orEmpty()
val releaseSigningReady = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.speakeasy.intercom"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.speakeasy.intercom"
        minSdk = 26
        targetSdk = 36
        versionCode = 46
        versionName = "1.7-beta15"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cFlags += "-O2"
                cppFlags += "-O2"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = ksKeyAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // ABI-Filter aus defaultConfig greift — Debug bleibt unsigned/dev-keystore.
        }
    }

    // AAB-Splits für Play-Store-Upload. Play installiert pro Gerät nur die
    // passenden Anteile (eine ABI statt vier, eine Density statt sechs).
    // Sprache lassen wir gebündelt: die App wechselt Locale per
    // AppCompatDelegate.setApplicationLocales — ein Sprachwechsel auf eine
    // nicht installierte Locale würde sonst eine Play-Asset-Nachladung
    // erfordern (zusätzliche Komplexität, < 5 KB Ersparnis pro Locale).
    bundle {
        language { enableSplit = false }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
