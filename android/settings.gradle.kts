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
        // libsignal has been published here (not Maven Central) since v0.86.6. Exclusive both ways:
        // this repository serves only org.signal, and org.signal is never looked up anywhere else,
        // so a same-named artifact on another repository can never be picked instead.
        exclusiveContent {
            forRepository {
                maven("https://build-artifacts.signal.org/libraries/maven/") { name = "SignalBuildArtifacts" }
            }
            filter { includeGroupByRegex("org\\.signal.*") }
        }
    }
}

rootProject.name = "us2us"

include(
    ":core",
    ":cli",
    ":domain",
    ":fixtures",
    ":designsystem",
    ":screenshot-support",
    ":feature:onboarding",
    ":feature:lock",
    ":feature:chats",
    ":feature:conversation",
    ":feature:media",
    ":feature:verification",
    ":feature:settings",
    ":feature:report",
    ":scene3d",
    ":runtime",
    ":live",
    ":app",
)
