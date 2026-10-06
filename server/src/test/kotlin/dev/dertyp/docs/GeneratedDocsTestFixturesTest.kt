package dev.dertyp.docs

import dev.dertyp.routing.rest.GeneratedRestRoutes
import io.github.classgraph.ClassGraph
import kotlinx.rpc.annotations.Rpc
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratedDocsTestFixturesTest {
    private val projectRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun testRpcInterfaces(): List<Class<*>> =
        ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .acceptPackages("dev.dertyp")
            .scan().use { scan ->
                val testClasses = scan.getClassInfo(GeneratedDocsTestFixturesTest::class.java.name).classpathElementFile
                scan.getClassesWithAnnotation(Rpc::class.java.name)
                    .filter { it.isInterface && it.classpathElementFile == testClasses }
                    .map { it.loadClass() }
            }

    @Test
    fun `rpc interfaces of the test sources are neither documented nor in the generated routes`() {
        val fixtures = testRpcInterfaces()
        assertTrue(fixtures.isNotEmpty())

        val generatedFor = GeneratedRestRoutes.manifest.map { it.interfaceName }.toSet()
        assertEquals(emptyList(), fixtures.map { it.name }.filter { it in generatedFor })

        val docs = File(projectRoot, "docs").listFiles { file -> file.extension == "md" }.orEmpty().sortedBy { it.name }
        assertTrue(docs.isNotEmpty())
        val mentions = docs.flatMap { doc ->
            val text = doc.readText()
            fixtures.filter { it.simpleName in text || it.name in text }.map { "${doc.name}: ${it.name}" }
        }
        assertEquals(emptyList(), mentions)
    }

    @Test
    fun `the test sources bring no generated routes of their own`() {
        val manifest = GeneratedRestRoutes::class.java
        val testClasses = File(GeneratedDocsTestFixturesTest::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(File(manifest.protectionDomain.codeSource.location.toURI()) != testClasses)
    }
}
