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

            scheduleService.registerManagedTask(
                key = taskAnnotation.key,
                name = taskAnnotation.name,
                task = {
                    scheduleService.logTask(taskAnnotation.name) {
                        worker.run { p, l -> updateProgress(p, l) }
                    }
                }
            )
        }
    }
}

fun Application.configureScheduledTasks() {
    ScheduledTasksRegistrar.configureScheduledTasks(this)
}
