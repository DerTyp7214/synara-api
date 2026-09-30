package dev.dertyp.services.intake

import dev.dertyp.services.jobs.JobService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val intakeModule = module {
    singleOf(::JobService)
    singleOf(::IntakeService)
    singleOf(::ImporterResolvers)
}
