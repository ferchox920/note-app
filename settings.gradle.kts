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

rootProject.name = "NoteApp"

include(
    ":app",
    ":benchmark",
    ":core-audio",
    ":core-domain",
    ":core-storage",
    ":feature-recording",
    ":inference-asr",
    ":inference-vad",
    ":shared-testing",
)
