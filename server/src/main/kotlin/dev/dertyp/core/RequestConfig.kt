package dev.dertyp.core

import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.descriptors.ValueExampleDescriptor
import io.ktor.http.*

fun RequestConfig.paging() {
    queryParameter<Int>("page") {
        description = "The page number. (starts at 0)"
        required = false
    }
    queryParameter<Int>("pageSize") {
        description = "The page size. (defaults to 150)"
        required = false
    }
}

fun RequestConfig.authHeader() {
    headerParameter<String>(HttpHeaders.Authorization) {
        description = "The auth token (JWT) \"Bearer\""
        example(ValueExampleDescriptor(
            name = "JWT",
            value = $$"Bearer ${[token]}",
        ))
    }
}