package dev.dertyp.services.schedule

import dev.dertyp.data.TaskConfiguration
import io.github.classgraph.ClassGraph

object WorkerTasks {
    val workerClasses: List<Class<Worker>> by lazy {
        ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .acceptPackages("dev.dertyp.services.schedule")
            .scan().use { scanResult ->
                scanResult.getClassesWithAnnotation(WorkerTask::class.java.name)
                    .map { it.loadClass(Worker::class.java) }
                    .sortedBy { it.getAnnotation(WorkerTask::class.java).key }
            }
    }

    val defaults: List<TaskConfiguration> by lazy {
        workerClasses.map { it.getAnnotation(WorkerTask::class.java).defaultConfiguration() }
    }
}
