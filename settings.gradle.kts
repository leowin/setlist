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
    }
}
rootProject.name = "Setlist"
// The server image (Dockerfile) builds only :server; the app modules need the Android SDK.
if (System.getenv("SETLIST_SERVER_ONLY") == null) {
    include(":shared")
    include(":androidApp")
}
include(":server")
