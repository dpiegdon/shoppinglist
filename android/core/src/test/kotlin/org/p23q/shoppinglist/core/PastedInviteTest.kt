package org.p23q.shoppinglist.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The invite in pasted text (T-300), driven by the table the web's inviteToken.test.ts reads too
 * (T-301): the point is that both clients agree, so the cases live in one place.
 */
class PastedInviteTest {

    @Test
    fun `pasted text matches the shared table`() {
        // Unit tests run from the module directory, so the repo root is two levels up (as NameOrderTest).
        val file = File("../../shared-test-cases/invite-paste.json")
        assertTrue("missing shared case table at ${file.absolutePath}", file.exists())
        val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            assertEquals(
                case["name"]!!.jsonPrimitive.content,
                case["expected"]!!.jsonPrimitive.content,
                pastedInvite(case["input"]!!.jsonPrimitive.content),
            )
        }
    }
}
