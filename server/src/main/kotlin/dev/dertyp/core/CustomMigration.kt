package dev.dertyp.core

import io.ktor.util.logging.KtorSimpleLogger
import org.koin.core.component.KoinComponent

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Migration(val version: String)

abstract class CustomMigration : KoinComponent {
    val logger = KtorSimpleLogger(this::class.simpleName!!)
    open val version: String by lazy {
        this::class.java.getAnnotation(Migration::class.java)?.version ?: "0"
    }
    open val id: String = this::class.simpleName!!
    abstract suspend fun migrate()
}
