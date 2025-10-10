package dev.dertyp.core

import io.github.smiley4.ktoropenapi.config.RequestConfig

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