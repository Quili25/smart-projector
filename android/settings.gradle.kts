pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "smart-projector"

// protocol is a plain JVM library so the wire format can be unit-tested on the
// desktop JVM, without an emulator or connected device.
include(":protocol")
// updates is shared: both apps are published side by side on the LAN page and read the
// same latest.json schema, so the check lives in one place.
include(":updates")
include(":receiver")
include(":sender")
