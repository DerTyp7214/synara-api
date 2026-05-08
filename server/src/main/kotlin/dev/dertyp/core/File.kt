package dev.dertyp.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

fun File.deleteOnExitRecursive() {
    if (isDirectory) {
        listFiles()?.forEach {
            it.deleteOnExitRecursive()
        }
    }

    this.deleteOnExit()
}

fun File.getTotalSize(): Long {
    val path = this.toPath()
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return length()
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return 0

    return listFiles()?.sumOf { it.getTotalSize() } ?: 0
}
