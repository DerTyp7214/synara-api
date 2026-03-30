import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.kover)
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks.shadowJar {
    mergeServiceFiles {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.core)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.hikari)
    implementation(libs.ktor.simple.cache)
    implementation(libs.ktor.simple.memory.cache)
    implementation(libs.ktor.simple.redis.cache)
    implementation(libs.jedis)
    implementation(libs.ktor.openapi)
    implementation(libs.ktor.swagger.ui)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.host.common)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.webjars)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.khealth)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.serialization.kotlinx.protobuf)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.html)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.migration.core)
    implementation(libs.exposed.migration.jdbc)
    implementation(libs.h2)
    implementation(libs.ffmpeg)
    implementation(libs.ffmpeg.platform)
    implementation(libs.javacv.platform)
    implementation(libs.thumbnailator)
    implementation(libs.postgresql)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.kotlinx.rpc.krpc.serialization.cbor)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.ktor.server.netty)
    implementation(libs.bcrypt)
    implementation(libs.logback.classic)
    implementation(libs.jul.to.slf4j)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.jaudiotagger)
    implementation(libs.sqlite.jdbc)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.caffeine)
    implementation(libs.jmdns)
    implementation(libs.cron.utils)
    implementation(libs.scrimage.core)
    implementation(libs.scrimage.webp)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.zstd.jni)
    implementation(libs.kotlinx.serialization.cbor)

    implementation(project(":common-rpc"))
    implementation(project(":common-proxy"))

    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("net.bytebuddy.experimental", "true")
}

val ktorBaseImageTag = "synara-api-base:latest"

ktor {
    docker {
        localImageName.set(ktorBaseImageTag.split(":").first())
        imageTag.set(ktorBaseImageTag.split(":").last())
        jreVersion.set(JavaVersion.VERSION_25)
    }
}

val gitHashProvider: Provider<String> = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val buildTimestamp: String = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
    .format(Instant.now())

buildConfig {
    packageName("dev.dertyp.server")
    buildConfigField("VERSION", project.version.toString())
    buildConfigField("APP_NAME", "Synara API")
    buildConfigField("BUILD_TIME", buildTimestamp)
    buildConfigField("GIT_HASH", gitHashProvider.get())
}