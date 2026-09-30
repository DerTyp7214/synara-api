plugins {
    id("synara.kotlin-jvm")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    api(libs.koin.core)
    api(libs.jaudiotagger)
    api(libs.cron.utils)
    api(libs.ktor.server.core)
    implementation(project(":common-rpc"))

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.mockk)
}

tasks.test {
    useJUnitPlatform()
}
