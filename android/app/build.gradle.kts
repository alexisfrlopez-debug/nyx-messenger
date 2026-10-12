import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Signature release (règle permanente : APK installable en 1 clic — RESTART.md §9).
// Le keystore vit dans android/keystore.properties (git-ignoré) — JAMAIS dans le dépôt.
// En son absence : fallback debug (build de CI), l'audit signale alors l'installation non garantie.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "net.nyx.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.nyx.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
            // TODO v1.0 : signature reproductible (F-Droid), build deterministe
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Tor embarqué (ADR-007) : le binaire libtor.so doit être EXTRAIT sur le disque
        // pour être exécutable depuis nativeLibraryDir (interdit de exec depuis l'APK).
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Compose (BOM)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Base
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Données : Room + SQLCipher (stockage local chiffré)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    // Crypto : libsodium (sealed box, conversions ed25519↔curve25519)
    // NB : JNA doit être l'artefact .aar (jniLibs Android) — pas le jar desktop.
    // On exclut le jna transitif (jar) de lazysodium-android pour ne garder
    // que notre dépendance @aar explicite, sinon checkDuplicateClasses échoue.
    implementation(libs.lazysodium.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna.android) { artifact { type = "aar" } }

    // Réseau : OkHttp forcé via SOCKS5 Tor (voir RelayClient)
    implementation(libs.okhttp)

    // Tor embarqué (ADR-007) : démon tor (exécutable libTor.so par ABI, empaqueté
    // dans les jniLibs) — autonomie totale, sans Orbot. L'app génère son torrc,
    // exécute le binaire et publie son onion service.
    implementation(libs.tor.binary)

    // Clés scellées (Keystore)
    implementation(libs.androidx.security.crypto)
}
