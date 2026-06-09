package dev.dertyp

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.KoinTest
import org.koin.test.verify.verify

class KoinModuleTest : KoinTest {

    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `verify Koin modules`() {
        val application = mockk<Application>(relaxed = true)
        val environment = mockk<ApplicationEnvironment>(relaxed = true)
        val config = MapApplicationConfig(
            "client.id" to "test-client",
            "client.secret" to "test-secret",
            "storage.driverClassName" to "org.sqlite.JDBC",
            "storage.jdbcURL" to "jdbc:sqlite::memory:",
            "storage.user" to "sa",
            "storage.password" to "",
            "jwt.audience" to "test-audience",
            "jwt.issuer" to "test-issuer",
            "jwt.realm" to "test-realm",
            "jwt.secret" to "test-secret-key-must-be-long-enough-for-hmac-sha-256-verification",
            "audio.custom" to "test-audio-custom",
            "data.images" to "test-data-images",
            "imageCache.url" to "http://test-image-cache"
        )
        every { environment.config } returns config

        mainModule(application, environment).verify()
    }
}
