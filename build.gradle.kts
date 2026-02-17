plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlinx.rpc) apply false
    alias(libs.plugins.buildconfig) apply false
}

subprojects {
    group = "dev.dertyp"
    version = "0.0.1"
}

tasks.register<Exec>("checkUpdates") {
    group = "verification"
    description = "Checks for dependency updates using check_updates.py"
    commandLine("python3", "check_updates.py")
    standardOutput = File("updates.txt").outputStream()
}
