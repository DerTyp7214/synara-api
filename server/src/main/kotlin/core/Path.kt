package dev.dertyp.core

import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.readSymbolicLink

fun Path.resolveRelativeAbsolute(other: String): Path = absolute().resolveSibling(other).normalize()
fun Path.resolveRelativeAbsolute(other: Path): Path = absolute().resolveSibling(other).normalize()
fun Path.resolveSymlinkAbsolute(): Path = absolute().let { p -> p.resolveSibling(p.readSymbolicLink()) }.normalize()