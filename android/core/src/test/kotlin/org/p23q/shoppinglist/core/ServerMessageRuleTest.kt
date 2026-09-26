package org.p23q.shoppinglist.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The server message rule (T-316), driven by the table the server's and the web's tests read too:
 * the admin console checks a message here before it sends it, so the three must agree.
 */
class ServerMessageRuleTest {

    private val cases: List<JsonObject> = run {
        // Unit tests run from android/core, so the repo root is two levels up (as NameOrderTest).
        val file = File("../../shared-test-cases/server-message.json")
        assertTrue("missing shared case table at ${file.absolutePath}", file.exists())
        Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    }

    @Test
    fun `every case of the shared table`() {
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case["name"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonPrimitive.content
            val result = case["result"]!!.jsonObject
            val expected = when {
                "error" in result -> {
                    assertEquals(name, "invalid_message", result["error"]!!.jsonPrimitive.content)
                    ServerMessageRule.Result.Invalid
                }
                else -> ServerMessageRule.Result.Valid(
                    result["message"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                )
            }
            assertEquals(name, expected, ServerMessageRule.check(input))
        }
    }
}
