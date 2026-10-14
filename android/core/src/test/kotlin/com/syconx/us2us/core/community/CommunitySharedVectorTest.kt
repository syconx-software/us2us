package com.syconx.us2us.core.community

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The cross-platform name table and the negative signed-state cases in spec/test-vectors. */
class CommunitySharedVectorTest {
    private fun file(name: String) = File(System.getProperty("us2us.specDir"), "test-vectors/$name")

    private fun cases(file: File): List<JsonObject> = when (val root = Json.parseToJsonElement(file.readText())) {
        is JsonArray -> root.map { it.jsonObject }
        is JsonObject -> (root["vectors"] ?: root["cases"] ?: root["names"] ?: root["states"])!!.jsonArray.map { it.jsonObject }
        else -> fail("unexpected vector file ${file.name}")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.label(): String = string("description") ?: string("reason") ?: string("case") ?: string("name") ?: toString()

    @Test fun `shared community name vectors`() {
        val source = file("communities-names.json")
        assumeTrue(source.exists(), "spec/test-vectors/communities-names.json is not published yet")
        val vectors = cases(source)
        assertTrue(vectors.isNotEmpty())
        for (vector in vectors) {
            val name = vector.string("name")!!
            val valid = vector.flag("valid")!!
            val normalized = CommunityState.normalizedName(name)
            assertEquals(valid, normalized != null, "name ${vector.label()}")
            if (vector.containsKey("canonical")) assertEquals(vector.string("canonical"), normalized, "canonical ${vector.label()}")
            // A valid name survives an invitation unchanged; an invalid one is refused there too.
            val invite = CommunityInvite(
                com.syconx.us2us.core.AccountId(java.util.UUID(1, 2)),
                com.syconx.us2us.core.encoding.Base64Url.encode(ByteArray(32) { 3 }),
                com.syconx.us2us.core.encoding.Base64Url.encode(byteArrayOf(5) + ByteArray(32) { 4 }),
                normalized ?: name,
            )
            // An empty name is an absent one in an invitation; any other invalid name is refused there.
            if (valid) assertEquals(normalized, CommunityInvite.parse(invite.code()).name)
            else if (name.isNotEmpty()) assertThrows(IllegalArgumentException::class.java, { invite.code() }, vector.label())
        }
    }

    @Test fun `shared community state vectors decode exactly the valid ones`() {
        val source = file("communities-invalid.json")
        assumeTrue(source.exists(), "spec/test-vectors/communities-invalid.json is not published yet")
        val vectors = cases(source)
        assertTrue(vectors.any { it.flag("valid") == true } && vectors.any { it.flag("valid") == false })
        for (vector in vectors) {
            val bytes = com.syconx.us2us.core.encoding.Hex.decode(vector.string("stateHex")!!)
            if (vector.flag("valid")!!) {
                assertArrayEquals(bytes, CommunityState.decode(bytes).encode(), vector.label())
            } else {
                assertThrows(Exception::class.java, { CommunityState.decode(bytes) }, vector.label())
            }
        }
    }
}
