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
        ivy {
            name = "SherpaOnnxGitHubReleases"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact]-[revision].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeModule("com.k2fsa", "sherpa-onnx")
            }
        }
    }
}

rootProject.name = "NoteApp"

include(
    ":app",
    ":benchmark",
    ":core-audio",
    ":core-domain",
    ":core-security",
    ":core-storage",
    ":feature-recording",
    ":inference-asr",
    ":inference-vad",
    ":shared-testing",
)
