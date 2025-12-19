plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("multiplatform") version "2.2.21" apply false
    id("io.ktor.plugin") version "3.3.3" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.10.1" apply false
}

subprojects {
    group = "dev.dertyp"
    version = "0.0.1"
}
