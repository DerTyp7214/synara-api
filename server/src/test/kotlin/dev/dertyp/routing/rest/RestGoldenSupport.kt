package dev.dertyp.routing.rest

import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.TrailingSlashRouteSelector
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object RestGoldenSupport {
    private val goldenDir: Path = Paths.get("src/test/resources/rest")

    fun collectLeaves(root: RoutingNode): Set<Pair<String, String>> {
        val out = mutableSetOf<Pair<String, String>>()
        fun walk(node: RoutingNode, segments: List<String>, trailingSlash: Boolean, method: String?) {
            var currentSegments = segments
            var currentTrailing = trailingSlash
            var currentMethod = method
            when (val selector = node.selector) {
                is PathSegmentConstantRouteSelector -> currentSegments = segments + selector.value
                is PathSegmentParameterRouteSelector -> currentSegments = segments + "${selector.prefix ?: ""}{${selector.name}}${selector.suffix ?: ""}"
                is TrailingSlashRouteSelector -> currentTrailing = true
                is HttpMethodRouteSelector -> currentMethod = selector.method.value
                else -> {}
            }
            if (node.children.isEmpty()) {
                if (currentMethod != null) {
                    out += currentMethod to ("/" + currentSegments.joinToString("/") + if (currentTrailing) "/" else "")
                }
            } else {
                node.children.forEach { walk(it, currentSegments, currentTrailing, currentMethod) }
            }
        }
        walk(root, emptyList(), false, null)
        return out.filterNot { it.second.startsWith("/api.json") }.toSet()
    }

    fun checkOrUpdate(name: String, content: String) {
        assertTrue(
            Files.isDirectory(Paths.get("src/test/kotlin")),
            "Golden tests expect the server module as working directory, was ${Paths.get("").toAbsolutePath()}",
        )
        val file = goldenDir.resolve(name)
        val update = System.getProperty("updateRestGolden") == "true"
        if (update || !Files.exists(file)) {
            Files.createDirectories(goldenDir)
            Files.writeString(file, content)
            return
        }
        val expected = Files.readString(file)
        if (expected != content) {
            fail<Unit>(
                "Golden file $file differs from the current output; rerun with -PupdateRestGolden=true to accept.\n" +
                    unifiedDiff(name, expected.lines().dropLastWhile { it.isEmpty() }, content.lines().dropLastWhile { it.isEmpty() }),
            )
        }
    }

    private sealed class Op(val line: String) {
        class Keep(line: String) : Op(line)
        class Remove(line: String) : Op(line)
        class Add(line: String) : Op(line)
    }

    private fun editScript(old: List<String>, new: List<String>): List<Op> {
        val lcs = Array(old.size + 1) { IntArray(new.size + 1) }
        for (i in old.indices.reversed()) {
            for (j in new.indices.reversed()) {
                lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val ops = mutableListOf<Op>()
        var i = 0
        var j = 0
        while (i < old.size && j < new.size) {
            when {
                old[i] == new[j] -> {
                    ops += Op.Keep(old[i])
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> ops += Op.Remove(old[i++])
                else -> ops += Op.Add(new[j++])
            }
        }
        while (i < old.size) ops += Op.Remove(old[i++])
        while (j < new.size) ops += Op.Add(new[j++])
        return ops
    }

    fun unifiedDiff(name: String, old: List<String>, new: List<String>, context: Int = 3): String {
        val ops = editScript(old, new)
        val changed = ops.indices.filter { ops[it] !is Op.Keep }
        if (changed.isEmpty()) return ""
        val out = StringBuilder()
        out.append("--- $name (golden)\n+++ $name (current)\n")
        var index = 0
        while (index < changed.size) {
            val start = maxOf(0, changed[index] - context)
            var end = minOf(ops.size, changed[index] + context + 1)
            while (index + 1 < changed.size && changed[index + 1] - context <= end) {
                index++
                end = minOf(ops.size, changed[index] + context + 1)
            }
            index++
            var oldStart = 1
            var newStart = 1
            for (k in 0 until start) {
                when (ops[k]) {
                    is Op.Keep -> { oldStart++; newStart++ }
                    is Op.Remove -> oldStart++
                    is Op.Add -> newStart++
                }
            }
            val hunk = ops.subList(start, end)
            val oldCount = hunk.count { it !is Op.Add }
            val newCount = hunk.count { it !is Op.Remove }
            out.append("@@ -$oldStart,$oldCount +$newStart,$newCount @@\n")
            hunk.forEach {
                val prefix = when (it) {
                    is Op.Keep -> " "
                    is Op.Remove -> "-"
                    is Op.Add -> "+"
                }
                out.append(prefix).append(it.line).append('\n')
            }
        }
        return out.toString()
    }
}
