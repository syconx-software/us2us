package com.syconx.us2us.core.vectors

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Verifies every vector the Swift implementation wrote to spec/test-vectors/swift/. A category whose
 * file does not exist yet is reported as skipped, not failed, while the Swift side catches up.
 */
class SwiftVectorsTest {
    private val directory = VectorFile.directory("swift")

    @TestFactory
    fun `swift vectors verify against the Kotlin implementation`(): List<DynamicTest> =
        VectorVerifier.categories.map { category ->
            DynamicTest.dynamicTest("swift/$category.json") {
                val file = File(directory, "$category.json")
                assumeTrue(file.isFile, "swift/$category.json has not been generated yet")
                val vectors = VectorFile.read(file)
                assertEquals("swift", vectors.generator)
                assertEquals(category, vectors.category)
                assertTrue(vectors.vectors.isNotEmpty(), "no vectors in swift/$category.json")
                vectors.vectors.forEachIndexed { index, vector ->
                    try {
                        VectorVerifier.verify(category, vector)
                    } catch (e: AssertionError) {
                        throw AssertionError("swift/$category.json vector #$index: ${e.message}", e)
                    }
                }
            }
        }

    @TestFactory
    fun `no unknown vector files`(): List<DynamicTest> {
        val files = directory.listFiles { f -> f.extension == "json" }.orEmpty()
        return files.map { file ->
            DynamicTest.dynamicTest("swift/${file.name} is a known category") {
                assertTrue(file.nameWithoutExtension in VectorVerifier.categories, "unknown category file ${file.name}")
            }
        }
    }
}
