pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
        // libsignal has been published here (not Maven Central) since v0.86.6.
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            name = "SignalBuildArtifacts"
            content { includeGroupByRegex("org\\.signal.*") }
        }
    }
}

rootProject.name = "us2us"

include(":core", ":cli")
