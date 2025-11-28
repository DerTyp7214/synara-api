package dev.dertyp.core

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink

fun Path.resolveRelativeAbsolute(other: String): Path = absolute().resolveSibling(other).normalize()
fun Path.resolveRelativeAbsolute(other: Path): Path = absolute().resolveSibling(other).normalize()
fun Path.resolveSymlinkAbsolute(): Path = absolute().let { p -> p.resolveSibling(p.readSymbolicLink()) }.normalize()

fun Path.isInside(parentDirectory: String): Boolean = isInside(Path(parentDirectory))
fun Path.isInside(parentDirectoryPath: Path): Boolean {
    val absoluteFile = this.toAbsolutePath().normalize()
    val absoluteParent = parentDirectoryPath.toAbsolutePath().normalize()

    return absoluteFile.startsWith(absoluteParent)
}

fun Path.getModifiedSince(
    timestampMs: Long,
): List<Path> {
    val dir = toFile()

    if (!dir.exists() || !dir.isDirectory) return emptyList()

    return dir.walkTopDown()
        .filter { it.isFile }
        .filter { it.lastModified() > timestampMs }
        .map { it.toPath() }
        .map {
            if (it.isSymbolicLink()) it.resolveSymlinkAbsolute()
            else it
        }
        .toList()
}