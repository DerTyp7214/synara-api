package dev.dertyp.core

import java.io.File

fun File.deleteOnExitRecursive() {
    if (isDirectory) {
        listFiles()?.forEach {
            it.deleteOnExitRecursive()
        }
    }

    this.deleteOnExit()
}