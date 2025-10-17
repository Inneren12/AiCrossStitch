// Hash f0e54d3053e64b7440868b2694fa86e5
plugin  {id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }
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

rootProject.name = "AppForCrossAndroidModular"
include(":app", ":core")
