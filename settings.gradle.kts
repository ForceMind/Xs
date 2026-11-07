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
rootProject.name = "Xs"
include(":xs-runtime")
include(":dev-harness")

include(":app-android")

// Standalone recipient APK for cross-UID grant instrumentation only.
include(":share-receiver-fixture")
