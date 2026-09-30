package dev.dertyp.core

import dev.dertyp.services.schedule.ScheduleService
import dev.dertyp.services.schedule.Worker
import dev.dertyp.services.schedule.WorkerTask
import io.ktor.server.application.Application
import io.ktor.server.application.log
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.reflect.full.findAnnotation

object ScheduledTasksRegistrar : KoinComponent {
    fun configureScheduledTasks(application: Application) {
        val scheduleService: ScheduleService = get()
        val workers = getKoin().getAll<Worker>()
        application.log.info("Found ${workers.size} workers for scheduled tasks")

        workers.forEach { worker ->
            val taskAnnotation = worker::class.findAnnotation<WorkerTask>() ?: return@forEach

            scheduleService.registerManagedWorker(
                key = taskAnnotation.key,
                name = taskAnnotation.name,
                worker = worker
            )
        }
    }
}

fun Application.configureScheduledTasks() {
    ScheduledTasksRegistrar.configureScheduledTasks(this)
}
