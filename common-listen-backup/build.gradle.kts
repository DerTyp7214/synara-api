plugins {
    id("synara.kotlin-jvm")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

group = "dev.dertyp"
version = "0.0.1"

dependencies {
    api(libs.kotlinx.serialization.json)
}
