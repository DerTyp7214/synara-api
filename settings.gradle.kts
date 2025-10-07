rootProject.name = "ktor-sample"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

include(":server")
include(":core")
include(":client")
