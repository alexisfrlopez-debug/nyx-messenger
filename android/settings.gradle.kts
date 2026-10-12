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
        // Tor embarqué (ADR-007) : artefact org.torproject.jni:tor-android publié
        // par le Guardian Project. Si la résolution échoue, vérifier les versions
        // disponibles sur https://maven.guardianproject.net/maven2/org/torproject/jni/tor-android/
        maven("https://maven.guardianproject.net/maven2/")
    }
}

rootProject.name = "Nyx"
include(":app")
