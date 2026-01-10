@file:OptIn(OpenApiPreview::class)

import io.ktor.plugin.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    kotlin("jvm") version "2.2.21"
    id("io.ktor.plugin") version "3.3.3"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21"
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.10.1"
    id("com.github.gmazzo.buildconfig")
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
    }
}

tasks.shadowJar {
    mergeServiceFiles {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}

dependencies {
    implementation("io.ktor:ktor-client-core")
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-server-cors")
    implementation("io.ktor:ktor-server-core")
    implementation("org.flywaydb:flyway-core:11.20.1")
    implementation("org.flywaydb:flyway-database-postgresql:11.20.1")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.ucasoft.ktor:ktor-simple-cache:0.55.3")
    implementation("com.ucasoft.ktor:ktor-simple-memory-cache:0.55.3")
    implementation("com.ucasoft.ktor:ktor-simple-redis-cache:0.55.3")
    implementation("redis.clients:jedis:7.2.0")
    implementation("io.github.smiley4:ktor-openapi:5.4.0")
    implementation("io.github.smiley4:ktor-swagger-ui:5.4.0")
    implementation("io.ktor:ktor-server-auth")
    implementation("io.ktor:ktor-server-auth-jwt")
    implementation("io.ktor:ktor-server-sse")
    implementation("io.ktor:ktor-server-host-common")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-webjars")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("dev.hayden:khealth:3.0.2")
    implementation("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-gson")
    implementation("io.ktor:ktor-serialization-kotlinx-protobuf")
    implementation("io.ktor:ktor-server-html-builder")
    implementation("org.jetbrains.kotlinx:kotlinx-html:0.12.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("org.jetbrains.exposed:exposed-dao:1.0.0-rc-3")
    implementation("org.jetbrains.exposed:exposed-core:1.0.0-rc-3")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.0.0-rc-3")
    implementation("org.jetbrains.exposed:exposed-migration-core:1.0.0-rc-3")
    implementation("org.jetbrains.exposed:exposed-migration-jdbc:1.0.0-rc-3")
    implementation("com.h2database:h2:2.3.232")
    implementation("org.bytedeco:ffmpeg-platform:7.1.1-1.5.12")
    implementation("org.bytedeco:javacv-platform:1.5.12")
    implementation("net.coobird:thumbnailator:0.4.21")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-serialization-json:0.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-server:0.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client:0.10.1")
    implementation("io.ktor:ktor-server-netty")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("ch.qos.logback:logback-classic:1.5.22")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("io.ktor:ktor-server-compression:3.3.3")
    implementation("io.ktor:ktor-server-auto-head-response:3.3.3")
    implementation("io.ktor:ktor-server-sessions:3.3.3")
    implementation("io.ktor:ktor-server-partial-content:3.3.3")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("org.jmdns:jmdns:3.6.1")
    implementation("com.sksamuel.scrimage:scrimage-core:4.3.5")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.3.5")
    implementation("io.insert-koin:koin-ktor:4.2.0-beta2")
    implementation("io.insert-koin:koin-logger-slf4j:4.2.0-beta2")

    implementation(project(":common-rpc"))
}

val ktorBaseImageTag = "synara-api-base:latest"

ktor {
    docker {
        localImageName.set(ktorBaseImageTag.split(":").first())
        imageTag.set(ktorBaseImageTag.split(":").last())
        jreVersion.set(JavaVersion.VERSION_24)
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