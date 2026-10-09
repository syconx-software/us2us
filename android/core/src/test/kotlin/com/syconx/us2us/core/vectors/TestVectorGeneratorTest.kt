package com.syconx.us2us.core.vectors

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Writes spec/test-vectors/kotlin/<category>.json for the Swift implementation to verify. Every
 * vector is checked against this implementation before it is written.
 *
 * Deterministic categories are rewritten byte-identically on every run. Sealed envelopes cannot be
 * reproduced (fresh HPKE encapsulation), so an existing sealed.json is kept as long as it still
 * verifies; that keeps the checked-in file stable.
 */
class TestVectorGeneratorTest {
    private val directory = VectorFile.directory("kotlin")

    @Test
    fun `generate all categories`() {
        directory.mkdirs()
        for ((category, generate) in VectorGenerator.deterministic) {
            write(category, generate())
        }
        val sealedFile = File(directory, "sealed.json")
        if (!sealedFile.exists() || !verifies(sealedFile)) write("sealed", VectorGenerator.sealed())

        for (category in VectorVerifier.categories) {
            assertTrue(File(directory, "$category.json").isFile, "$category.json was not written")
        }
    }

    private fun write(category: String, vectors: List<JsonObject>) {
        vectors.forEach { VectorVerifier.verify(category, it) }
        File(directory, "$category.json").writeText(VectorFile("kotlin", category, vectors).toJson())
    }

    private fun verifies(file: File): Boolean = try {
        val existing = VectorFile.read(file)
        existing.category == "sealed" && existing.vectors.isNotEmpty() &&
            existing.vectors.all { VectorVerifier.verify("sealed", it); true }
    } catch (_: AssertionError) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
