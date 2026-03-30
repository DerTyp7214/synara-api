plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.rpc) apply false
    alias(libs.plugins.buildconfig) apply false

    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kover) apply false
}

subprojects {
    group = "dev.dertyp"
    version = "0.0.1"
}
