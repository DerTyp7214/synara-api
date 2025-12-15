@file:OptIn(OpenApiPreview::class)

import io.ktor.plugin.*

val exposedVersion: String by project
val h2Version: String by project
val kotlinVersion: String by project
val kotlinxHtmlVersion: String by project
val kotlinxRpcVersion: String by project
val logbackVersion: String by project
val postgresVersion: String by project
val jmdnsVersion: String by project

plugins {
    kotlin("jvm") version "2.2.20"
    id("io.ktor.plugin") version "3.3.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("org.jetbrains.kotlinx.rpc.plugin") version "0.10.0"
}

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

java {
    sourceCompatibility = JavaVersion.VERSION_24
    targetCompatibility = JavaVersion.VERSION_24
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
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
    implementation("org.flywaydb:flyway-core:11.19.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.19.0")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.ucasoft.ktor:ktor-simple-cache:0.55.3")
    implementation("com.ucasoft.ktor:ktor-simple-memory-cache:0.55.3")
    implementation("com.ucasoft.ktor:ktor-simple-redis-cache:0.55.3")
    implementation("redis.clients:jedis:7.0.0")
    implementation("io.github.smiley4:ktor-openapi:5.1.0")
    implementation("io.github.smiley4:ktor-swagger-ui:5.1.0")
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
    implementation("org.jetbrains.kotlinx:kotlinx-html:$kotlinxHtmlVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.h2database:h2:$h2Version")
    implementation("org.bytedeco:ffmpeg-platform:6.1.1-1.5.10")
    implementation("org.bytedeco:javacv-platform:1.5.10")
    implementation("net.coobird:thumbnailator:0.4.21")
    implementation("org.postgresql:postgresql:$postgresVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-server:$kotlinxRpcVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-rpc-krpc-ktor-client:$kotlinxRpcVersion")
    implementation("io.ktor:ktor-server-netty")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("io.ktor:ktor-server-config-yaml")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("io.ktor:ktor-server-compression:3.3.1")
    implementation("io.ktor:ktor-server-auto-head-response:3.3.1")
    implementation("io.ktor:ktor-server-sessions:3.3.1")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.2")
    implementation("io.ktor:ktor-server-partial-content:3.3.1")
    implementation ("org.jmdns:jmdns:$jmdnsVersion")
    implementation("com.sksamuel.scrimage:scrimage-core:4.3.5")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.3.5")
    implementation("io.insert-koin:koin-ktor:4.1.2-Beta1")
    implementation("io.insert-koin:koin-logger-slf4j:4.1.2-Beta1")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlinVersion")
}

val ktorBaseImageTag = "synara-api-base:latest"

ktor {
    docker {
        localImageName.set(ktorBaseImageTag.split(":").first())
        imageTag.set(ktorBaseImageTag.split(":").last())
        jreVersion.set(JavaVersion.VERSION_24)
    }
}