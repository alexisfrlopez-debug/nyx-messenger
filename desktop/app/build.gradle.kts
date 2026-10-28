plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    application
}

group = "net.nyx"
version = "0.3.8"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Crypto : libsodium via LazySodium Java (natives win/linux/mac embarquées
    // dans l'artefact) + Bouncy Castle (SHA-3 onion v3 — même usage que l'app
    // Android, miroir catalogue libs.versions.toml).
    implementation("com.goterl:lazysodium-java:5.1.0")
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("com.goterl:resource-loader:2.0.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Réseau : OkHttp sur SOCKS5 Tor (miroir Android).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Sérialisation / coroutines (mêmes versions que le catalogue Android).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // org.json (rendezvous.json — même API que la version Android).
    implementation("org.json:json:20240303")

    // Correctif v0.3.1 (retour terrain Windows v0.3.0) :
    //  - natif Compose WINDOWS embarqué : compose.desktop.currentOs ne fournit
    //    que la plateforme de BUILD (linux ici) — la dll skiko Windows
    //    manquait donc dans le fat jar livré ;
    //  - NOP SLF4J : skiko-awt tire slf4j-api (sinon avertissement NOP au
    //    démarrage, cf. journal terrain).
    implementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.4")
    runtimeOnly("org.slf4j:slf4j-nop:1.7.30")

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.ui)

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("net.nyx.messenger.desktop.MainKt")
}

// v0.3.3 (session 73) — credentials Drive du BOUTON D'ENVOI DE LOGS (miroir
// android/test-upload.properties, même mécanique, même git-ignore) : le
// fichier `desktop/test-upload.properties` (racine du projet Gradle, hors git,
// alimenté depuis le dossier « Tokens » §6) est recopié DANS LE FAT JAR sous
// le nom drive-upload.properties → lu par diag/DriveUploader.kt. En son
// absence : build fonctionnel, le bouton affiche « credentials absents » et
// le recours « Enregistrer dans un fichier… » reste opérable (leçon v0.2.9).
// NB : injection au NIVEAU DU fatJar SEULEMENT (jamais dans sourceSets) —
// sinon la ressource fuite sur le CLASSPATH DE TEST et les tests « non
// configuré » deviennent faux-positifs quand le poste de build possède le
// fichier (constaté session 73).
val drivePropsFile = rootProject.file("test-upload.properties")

tasks.test {
    useJUnit()
    // Bornes mémoire du conteneur (miroir gradle.properties Android session 11).
    maxHeapSize = "1g"
}

kotlin {
    jvmToolchain(21)
}

// Fat jar autonome (payload du zip portable + installateur) — toutes les
// dépendances (Compose, OkHttp, LazySodium + natifs win/linux/mac, BC)
// embarquées : `runtime\bin\java.exe -jar nyx-messenger.jar` suffit.
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("nyx-messenger")
    archiveClassifier.set("desktop")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "net.nyx.messenger.desktop.MainKt" }
    from(sourceSets.main.get().output)
    // v0.3.3 — credentials Drive du bouton d'envoi de logs (build test) :
    // recopie du fichier git-ignoré SOUS le nom de ressource attendu, dans le
    // SEUL artefact livré (jamais sur le classpath de test — cf. commentaire
    // de drivePropsFile).
    if (drivePropsFile.isFile) {
        from(drivePropsFile) { rename { "drive-upload.properties" } }
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
    }
    // Correctif v0.3.1 : miroir des natifs libsodium aux CONVENTIONS DE
    // RESSOURCES JNA (win32-x86-64/sodium.dll…) — filet de sécurité : si le
    // repli système ne trouve pas le répertoire extrait par Natives.kt,
    // JNA sait extraire lui-même depuis ces chemins. (lazysodium groupe ses
    // natifs sous windows64/ linux64/ mac/… que JNA n'inspecte pas ; les
    // chemins d'origine RESTENT embarqués — Natives.kt les lit.)
    // NB : `into()` ne fait que PRÉFIXER — le re-path complet passe par
    // filesMatching + FileCopyDetails.path.
    val lazysodium = configurations.runtimeClasspath.get()
        .filter { it.isFile && it.name.startsWith("lazysodium-java") }
        .single()
    from(zipTree(lazysodium)) {
        include("windows64/libsodium.dll")
        filesMatching("windows64/libsodium.dll") { path = "win32-x86-64/sodium.dll" }
    }
    from(zipTree(lazysodium)) {
        include("linux64/libsodium.so")
        filesMatching("linux64/libsodium.so") { path = "linux-x86-64/libsodium.so" }
    }
    from(zipTree(lazysodium)) {
        include("mac/intel/libsodium.dylib")
        filesMatching("mac/intel/libsodium.dylib") { path = "darwin-x86-64/libsodium.dylib" }
    }
    from(zipTree(lazysodium)) {
        include("mac/aarch64/libsodium.dylib")
        filesMatching("mac/aarch64/libsodium.dylib") { path = "darwin-aarch64/libsodium.dylib" }
    }
}
