package dev.dertyp.services.schedule

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class WorkerTask(
    val key: String,
    val name: String
)
