plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jetbrains.compose") version "1.6.11"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    application
}

group = "net.nyx"
version = "0.3.0"

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
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
    }
}
