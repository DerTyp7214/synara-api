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
    if (isFile) return length()

    return walkTopDown().filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.sumOf { it.length() }
}