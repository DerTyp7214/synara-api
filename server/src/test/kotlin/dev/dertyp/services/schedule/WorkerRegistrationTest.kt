package dev.dertyp.services.schedule

import dev.dertyp.core.configureScheduledTasks
import io.github.classgraph.ClassGraph
import io.ktor.server.application.Application
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.test.KoinTest

class WorkerRegistrationTest : KoinTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `should register all annotated workers`() {
        val application = mockk<Application>(relaxed = true)
        val scheduleService = ScheduleService()
        
        startKoin {
            modules(module {
                single { application }
                single { scheduleService }

                ClassGraph()
                    .enableClassInfo()
                    .enableAnnotationInfo()
                    .acceptPackages("dev.dertyp.services.schedule")
                    .scan().use { scanResult ->
                        scanResult.getClassesWithAnnotation(WorkerTask::class.java.name).forEach { classInfo ->
                            val clazz = classInfo.loadClass()
                            single { clazz.getDeclaredConstructor().newInstance() } binds arrayOf(clazz.kotlin, Worker::class)
                        }
                    }
            })
        }

        application.configureScheduledTasks()

        val managedTasks = scheduleService.getManagedTasks()
        assertTrue(managedTasks.isNotEmpty(), "Should have registered some managed tasks")

        assertTrue(managedTasks.any { it.value.name == "MusicBrainz Worker" }, "MusicBrainz Worker should be registered")
    }
}
