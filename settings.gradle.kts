rootProject.name = "synara-api"

pluginManagement {
    includeBuild("build-logic")
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
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

include(":server")
include(":common-rpc")
include(":common-rpc:compiler")
include(":common-rpc:doc-compiler")
include(":common-rpc:rest-compiler")
include(":proxy")
include(":common-proxy")
include(":listen-backup")
include(":common-listen-backup")
include(":common-credentials")
include(":credential-server")
include(":plugin-api")
include(":mock-server")
