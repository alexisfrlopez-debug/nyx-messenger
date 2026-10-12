pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Tor embarqué (ADR-007) : io.matthewnelson.topl-android:tor-binary est publié
        // sur Maven Central — aucun dépôt tiers requis. (Alternative historique
        // org.torproject.jni:tor-android sur maven.guardianproject.net : écartée car
        // le dépôt Guardian est injoignable depuis certains réseaux/CI.)
    }
}

rootProject.name = "Nyx"
include(":app")
