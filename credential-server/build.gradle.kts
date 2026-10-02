plugins {
    id("synara.kotlin-jvm")
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

application {
    mainClass = "dev.dertyp.credentials.server.ApplicationKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

ktor {
    fatJar {
        archiveFileName.set("credential-server-all.jar")
    }
}

dependencies {
    implementation(project(":common-credentials"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.sqlite.jdbc)
    implementation(libs.postgresql)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.ktor.server.testingHost)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

tasks.test {
    useJUnitPlatform()
}
