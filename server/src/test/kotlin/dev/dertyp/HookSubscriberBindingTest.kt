package dev.dertyp

import dev.dertyp.services.HookSubscriber
import io.github.classgraph.ClassGraph
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.annotation.KoinInternalApi
import org.koin.dsl.koinApplication

class HookSubscriberBindingTest {

    @OptIn(KoinInternalApi::class)
    @Test
    fun `every hook subscriber of the server is bound as one in the production modules`() {
        val application = mockk<Application>(relaxed = true)
        val environment = mockk<ApplicationEnvironment>(relaxed = true)
        every { environment.config } returns MapApplicationConfig()

        val subscribers = ClassGraph()
            .enableClassInfo()
            .acceptPackages("dev.dertyp")
            .scan().use { scan ->
                val production = scan.getClassInfo(HookSubscriber::class.java.name).classpathElementURI
                scan.getClassesImplementing(HookSubscriber::class.java.name)
                    .filter { !it.isAbstract && !it.isInterface && it.classpathElementURI == production }
                    .map { it.name }
                    .toSet()
            }
        val production = koinApplication { modules(mainModule(application, environment)) }
        val bound = production.koin.instanceRegistry.instances.values
            .map { it.beanDefinition }
            .filter { HookSubscriber::class in it.secondaryTypes }
            .map { it.primaryType.java.name }
            .toSet()
        production.close()

        assertTrue(subscribers.isNotEmpty())
        assertEquals(subscribers, bound)
    }
}
