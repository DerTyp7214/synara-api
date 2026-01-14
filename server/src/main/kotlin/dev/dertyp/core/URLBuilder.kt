package dev.dertyp.core

import io.ktor.http.*

fun URLBuilder.parameters(block: ParametersBuilder.() -> Unit) = parameters.apply(block)