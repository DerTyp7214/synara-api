package dev.dertyp.services.schedule

import dev.dertyp.plugins.IScheduleService
import dev.dertyp.services.HookSubscriber
import dev.dertyp.services.ScheduledTaskLogService
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.binds
import org.koin.dsl.module

val scheduleModule = module {
    singleOf(::ScheduleService) {
        bind<IScheduleService>()
        bind<HookSubscriber>()
    }
    singleOf(::ScheduledTaskConfigurationService)
    singleOf(::ScheduledTaskLogService)

    WorkerTasks.workerClasses.forEach { clazz ->
        single<Any> { clazz.getDeclaredConstructor().newInstance() } binds arrayOf(clazz.kotlin, Worker::class)
    }
}
