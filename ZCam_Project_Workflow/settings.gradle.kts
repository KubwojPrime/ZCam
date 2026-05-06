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

include(
    ":app",
    ":core",
    ":camera",
    ":server",
    ":audio",
    ":storage",
    ":watchdog",
    ":security",
    ":client",
    ":service",
    ":data",
    ":ui"
)

rootProject.name = "ZCam"
