package dev.dertyp.core

import java.io.File
import java.nio.file.Files

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

    return walkTopDown().filter { it.isFile && Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }
}