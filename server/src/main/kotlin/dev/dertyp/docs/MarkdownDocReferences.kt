package dev.dertyp.docs

private val DOC_REFERENCE = Regex("(?<![\\w`@.])@([A-Z][A-Za-z0-9_]*)(?:\\.([A-Za-z_][A-Za-z0-9_]*))?")
private val HEADING = Regex("^### (\\S+) <a name=\"([^\"]+)\"></a>$")
private val ROW = Regex("^\\| `([^`]+)`")

class DocEntry(val name: String, val anchor: String, val members: Set<String>)

fun parseDocEntries(markdown: String): List<DocEntry> {
    val sections = mutableListOf<Pair<MatchResult, MutableSet<String>>>()
    markdown.lineSequence().forEach { line ->
        val heading = HEADING.matchEntire(line)
        if (heading != null) {
            sections.add(heading to mutableSetOf())
        } else {
            ROW.find(line)?.let { row -> sections.lastOrNull()?.second?.add(row.groupValues[1]) }
        }
    }
    return sections.map { (heading, members) -> DocEntry(heading.groupValues[1], heading.groupValues[2], members) }
}

class MarkdownDocReferences(
    private val services: List<DocEntry>,
    private val models: List<DocEntry>,
    private val serviceDoc: String,
    private val modelDoc: String,
) {
    private val entries = services + models
    private val entriesByName = entries.groupBy { it.name }
    private val modelsByAnchor = models.associateBy { it.anchor }

    fun link(text: String, sourceName: String): String =
        DOC_REFERENCE.replace(text) { match ->
            val name = match.groupValues[1]
            val member = match.groupValues[2].ifEmpty { null }
            val label = match.value.removePrefix("@")
            when (val target = resolve(name, member)) {
                is Target.Linked -> "[$label](${target.href})"
                is Target.Unresolved -> error(
                    "[ApiConstantsDocs] $sourceName: unresolved doc reference ${match.value}: ${target.reason}"
                )
            }
        }

    private fun isTopLevel(entry: DocEntry): Boolean =
        entries.none { it !== entry && entry.anchor == it.anchor + entry.name.lowercase() }

    private fun nestedModel(outer: DocEntry, member: String): DocEntry? =
        modelsByAnchor[outer.anchor + member.lowercase()]?.takeIf { it.name == member }

    private fun resolve(name: String, member: String?): Target {
        val candidates = entriesByName[name].orEmpty()
        val target = candidates.singleOrNull() ?: candidates.filter { isTopLevel(it) }.singleOrNull()
        return when {
            candidates.isEmpty() -> Target.Unresolved("$name is neither a service in $serviceDoc nor a model in $modelDoc")
            target == null -> Target.Unresolved("$name names more than one documented service or model")
            services.any { it === target } -> resolveService(target, member)
            else -> resolveModel(target, member)
        }
    }

    private fun resolveService(service: DocEntry, member: String?): Target {
        if (member == null) return Target.Linked("$serviceDoc#${service.anchor}")
        if (member in service.members) return Target.Linked("$serviceDoc#${service.anchor}-${member.lowercase()}")
        nestedModel(service, member)?.let { return Target.Linked("$modelDoc#${it.anchor}") }
        return Target.Unresolved("${service.name} has no method or nested model $member")
    }

    private fun resolveModel(model: DocEntry, member: String?): Target {
        if (member == null) return Target.Linked("$modelDoc#${model.anchor}")
        nestedModel(model, member)?.let { return Target.Linked("$modelDoc#${it.anchor}") }
        if (member in model.members) return Target.Linked("$modelDoc#${model.anchor}")
        return Target.Unresolved("${model.name} has no nested model, field or entry $member")
    }

    private sealed interface Target {
        class Linked(val href: String) : Target
        class Unresolved(val reason: String) : Target
    }
}
