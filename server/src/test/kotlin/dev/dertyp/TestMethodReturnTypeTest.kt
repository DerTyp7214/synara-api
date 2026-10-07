package dev.dertyp

import io.github.classgraph.ClassGraph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import kotlin.coroutines.Continuation

class TestMethodReturnTypeTest {
    private val testAnnotations = listOf(Test::class, ParameterizedTest::class, RepeatedTest::class).map { it.java.name }

    @Test
    fun `every test method of the test sources returns nothing, so JUnit runs it`() {
        val (testMethods, returningValue) = ClassGraph()
            .enableClassInfo()
            .enableMethodInfo()
            .enableAnnotationInfo()
            .ignoreClassVisibility()
            .ignoreMethodVisibility()
            .acceptPackages("dev.dertyp")
            .scan().use { scan ->
                val testClasses = scan.getClassInfo(TestMethodReturnTypeTest::class.java.name).classpathElementURI
                val methods = scan.allClasses
                    .filter { it.classpathElementURI == testClasses }
                    .flatMap { it.declaredMethodInfo }
                    .filter { method -> testAnnotations.any { method.hasAnnotation(it) } }
                methods.size to methods
                    .filter { it.typeDescriptor.resultType.toString() != "void" }
                    .filterNot { it.parameterInfo.lastOrNull()?.typeDescriptor?.toString() == Continuation::class.java.name }
                    .map { "${it.className}.${it.name}: ${it.typeDescriptor.resultType}" }
                    .sorted()
            }

        assertTrue(testMethods > 0)
        assertEquals(emptyList<String>(), returningValue)
    }
}
