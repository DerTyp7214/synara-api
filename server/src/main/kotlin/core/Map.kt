package dev.dertyp.core

import dev.dertyp.data.InsertableAlbum

fun Map<InsertableAlbum, Any>.getAlbum(album: InsertableAlbum): InsertableAlbum? {
    for (key in keys) {
        if (key.contentEquals(album)) return key
    }

    return null
}

fun Map<InsertableAlbum, Any>.hasAlbum(album: InsertableAlbum): Boolean {
    return getAlbum(album) != null
}