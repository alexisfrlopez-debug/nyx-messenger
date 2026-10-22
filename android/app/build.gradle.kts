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

// Build « test » (v0.2.2) : credentials Drive pour l'export de logs par le gros
// bouton rouge — fichier GIT-IGNORÉ (jamais dans le dépôt), alimenté depuis le
// dossier « Tokens » (RESTART.md §6). En son absence : build fonctionnel mais
// le bouton affiche « credentials absents ».
val testUploadProps = Properties().apply {
    val f = rootProject.file("test-upload.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun testUpload(key: String): String = testUploadProps.getProperty(key) ?: ""

// Réplicas d'amorçage (v0.2.3 — pont ADR-009, DOUBLON du rendezvous signé v0.2.23 :
// conservé comme filet de sécurité si l'asset signé est absent/invalidé) :
// liste « onion[,onion…] » GIT-IGNORÉE, embarquée dans le build. En son absence
// l'app démarre sans réplica rattaché (l'onboarding l'indique explicitement).
val replicaProps = Properties().apply {
    val f = rootProject.file("replica-bootstrap.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val bootstrapReplicas = replicaProps.getProperty("replicas") ?: ""

// Rendezvous signé (ADR-009 §3, ADR-016 — v0.2.23) : clé publique ed25519 de
// release embarquée dans le build ; la liste signée vit dans assets/rendezvous.json.
val rendezvousPubkey = "M//9JU3ZIPI210FkqUMDL+oVRsnLuaRjn4H+2uRFy/Q="

android {
    namespace = "net.nyx.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.nyx.messenger"
        minSdk = 26
        targetSdk = 35
        versionCode = 43
        versionName = "0.2.39"
        // Credentials Drive du build test (vides si test-upload.properties absent)
        buildConfigField("String", "DRIVE_CLIENT_ID",     "\"${testUpload("drive.clientId")}\"")
        buildConfigField("String", "DRIVE_CLIENT_SECRET", "\"${testUpload("drive.clientSecret")}\"")
        buildConfigField("String", "DRIVE_REFRESH_TOKEN", "\"${testUpload("drive.refreshToken")}\"")
        buildConfigField("String", "DRIVE_FOLDER_ID",     "\"${testUpload("drive.folderId")}\"")
        // Réplicas d'amorçage embarqués (v0.2.3 — vide si replica-bootstrap.properties absent)
        buildConfigField("String", "BOOTSTRAP_REPLICAS", "\"$bootstrapReplicas\"")
        // Rendezvous signé (v0.2.23) : clé publique de release + asset assets/rendezvous.json
        buildConfigField("String", "RENDEZVOUS_PUBKEY", "\"$rendezvousPubkey\"")
        // v0.2.35 : réplica temporaire embarqué — INTÉGRÉ À TOUTES LES VERSIONS
        // (demande opérateur : fusion dans normale + test après validation
        // terrain v0.2.19→v0.2.34 ; l'ancien flavor « diag2 » est SUPPRIMÉ).
        buildConfigField("boolean", "EMBEDDED_REPLICA", "true")
    }

    // DEUX livrables par release depuis v0.2.35 (fusion demandée par
    // l'opérateur : le RÉPLICA temporaire embarqué, validé en « test 2 » de la
    // v0.2.19 à la v0.2.34, est désormais intégré AUX DEUX versions — la
    // version « test 2 » n'a plus de raison d'exister, le flavor diag2 est
    // supprimé) :
    //  - « standard » : l'app publique, AVEC réplica embarqué ;
    //  - « diag »     : + gros bouton rouge (écran conversations) qui collecte
    //    les logs et les dépose dans le dossier de travail Drive pour examen.
    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
            buildConfigField("boolean", "TEST_BUILD", "false")
        }
        create("diag") {
            dimension = "mode"
            applicationIdSuffix = ".test"          // cohabite avec l'app standard
            versionNameSuffix = "-test"
            resValue("string", "app_name", "Nyx TEST")
            buildConfigField("boolean", "TEST_BUILD", "true")
        }
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
        buildConfig = true
    }
    testOptions {
        // Tests JVM : android.util.Log etc. renvoient des valeurs par défaut au
        // lieu de lever « not mocked » (AppLockTest échouait à tort sur JVM).
        unitTests.isReturnDefaultValues = true
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
    // Verrou biométrique local opt-in (ADR-010 §3) — l'OS vérifie, l'app ne reçoit
    // qu'un succès/échec ; aucune donnée biométrique n'est lue, stockée, transmise.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
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

    // SHA-3 (FIPS 202) pour les checksums onion v3 — Conscrypt ne fournit
    // MessageDigest("SHA3-256") qu'à partir d'Android 14 (crash
    // « SHA3-256 MessageDigest not available » sur Android ≤ 13, cf. OnionCodec).
    implementation(libs.bouncycastle)

    // Réseau : OkHttp forcé via SOCKS5 Tor (voir RelayClient)
    implementation(libs.okhttp)

    // Tor embarqué (ADR-007) : démon tor (exécutable libTor.so par ABI, empaqueté
    // dans les jniLibs) — autonomie totale, sans Orbot. L'app génère son torrc,
    // exécute le binaire et publie son onion service.

    // Clés scellées (Keystore)
    implementation(libs.androidx.security.crypto)

    // Tests unitaires JVM (pas d'émulateur requis)
    testImplementation(libs.junit)
    // v0.2.21 : org.json RÉEL pour les tests (IdentityBackup v2 — le stub
    // android.jar est inutilisable en JVM ; artifact déjà en cache Gradle).
    testImplementation("org.json:json:20240303")
}
