package org.p23q.shoppinglist.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO shapes are checked against fully-expanded fixtures of the Wire Contract's Item/List
 * examples (docs/wire-contract.md) — the doc abbreviates
 * repeated updated_at/updated_by pairs with "...", these fixtures spell every field out in full.
 */
class DtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val itemJson = """
        {
          "id": "item-uuid", "list_id": "list-uuid", "created_at": 1751970000000,
          "fields": {
            "name":     {"value": "Milk", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "category": {"value": "groceries", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "stores":   {"value": ["Rewe", "Aldi"], "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "quantity": {"value": "2l", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "price":    {"value": {"amount": "1.99", "currency": "EUR"}, "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "note":     {"value": "the ripe ones", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "status":   {"value": "todo", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "deleted":  {"value": false, "updated_at": 1751970000000, "updated_by": "dev-uuid"}
          }
        }
    """.trimIndent()

    private val itemWithNullsJson = """
        {
          "id": "item-uuid-2", "list_id": "list-uuid", "created_at": 1751970000000,
          "fields": {
            "name":     {"value": "Bread", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "category": {"value": null, "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "stores":   {"value": [], "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "quantity": {"value": null, "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "price":    {"value": null, "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "note":     {"value": null, "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "status":   {"value": "backlog", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "deleted":  {"value": false, "updated_at": 1751970000000, "updated_by": "dev-uuid"}
          }
        }
    """.trimIndent()

    private val listJson = """
        {
          "id": "list-uuid", "created_at": 1751970000000,
          "fields": {
            "name":           {"value": "Groceries", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "category_order": {"value": ["groceries", "freezer"], "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "notes":          {"value": "Gate code: 4471", "updated_at": 1751970000000, "updated_by": "dev-uuid"},
            "deleted":        {"value": false, "updated_at": 1751970000000, "updated_by": "dev-uuid"}
          }
        }
    """.trimIndent()

    @Test
    fun `ItemDto decodes every field clock from the wire fixture`() {
        val item = json.decodeFromString<ItemDto>(itemJson)

        assertEquals("item-uuid", item.id)
        assertEquals("list-uuid", item.listId)
        assertEquals(1751970000000L, item.createdAt)
        assertEquals("Milk", item.fields.name.value)
        assertEquals(1751970000000L, item.fields.name.updatedAt)
        assertEquals("dev-uuid", item.fields.name.updatedBy)
        assertEquals("groceries", item.fields.category.value)
        assertEquals(listOf("Rewe", "Aldi"), item.fields.stores.value)
        assertEquals("2l", item.fields.quantity.value)
        assertEquals("1.99", item.fields.price.value?.amount)
        assertEquals("EUR", item.fields.price.value?.currency)
        assertEquals("the ripe ones", item.fields.note.value)
        assertEquals("todo", item.fields.status.value)
        assertEquals(false, item.fields.deleted.value)
    }

    @Test
    fun `ItemDto round-trips through encode-decode byte for byte as a JSON tree`() {
        val item = json.decodeFromString<ItemDto>(itemJson)

        val reEncoded = json.encodeToString(ItemDto.serializer(), item)

        assertEquals(parseToJsonElement(itemJson), parseToJsonElement(reEncoded))
    }

    @Test
    fun `ItemDto decodes null-or-empty optional fields`() {
        val item = json.decodeFromString<ItemDto>(itemWithNullsJson)

        assertEquals(null, item.fields.category.value)
        assertEquals(emptyList<String>(), item.fields.stores.value)
        assertEquals(null, item.fields.quantity.value)
        assertEquals(null, item.fields.price.value)
        assertEquals(null, item.fields.note.value)
        assertEquals("backlog", item.fields.status.value)
    }

    @Test
    fun `ListDto round-trips through encode-decode byte for byte as a JSON tree`() {
        val list = json.decodeFromString<ListDto>(listJson)

        assertEquals("list-uuid", list.id)
        assertEquals("Groceries", list.fields.name.value)
        assertEquals(listOf("groceries", "freezer"), list.fields.categoryOrder.value)
        assertEquals("Gate code: 4471", list.fields.notes.value)
        assertEquals(false, list.fields.deleted.value)

        val reEncoded = json.encodeToString(ListDto.serializer(), list)
        assertEquals(parseToJsonElement(listJson), parseToJsonElement(reEncoded))
    }

    @Test
    fun `SyncRequest and SyncResponse round-trip the wire shape`() {
        val request = SyncRequest(
            cursor = 123,
            deviceId = "dev-uuid",
            fullLists = listOf("list-uuid"),
            changes = SyncChanges(items = listOf(json.decodeFromString<ItemDto>(itemJson))),
        )
        val requestJson = json.encodeToString(SyncRequest.serializer(), request)
        val decodedRequest = json.decodeFromString<SyncRequest>(requestJson)
        assertEquals(request, decodedRequest)

        val response = SyncResponse(cursor = 456, changes = SyncChanges(lists = listOf(json.decodeFromString<ListDto>(listJson))))
        val responseJson = json.encodeToString(SyncResponse.serializer(), response)
        val decodedResponse = json.decodeFromString<SyncResponse>(responseJson)
        assertEquals(response, decodedResponse)
    }

    // T-97: PATCH /settings now treats an ABSENT initials key as "leave unchanged" (T-87), but a
    // *present* "" still overwrites a custom override. A currency-only save must be able to omit
    // the key entirely when the client doesn't yet know the account's initials (e.g. offline
    // start racing the best-effort preload) instead of resending a literal "". These tests use
    // the app's actual configured Json instance (JsonModule.provideJson(), same package) rather
    // than a fresh default Json() — the omission depends on that instance's encodeDefaults
    // setting, not just the DTO's declared default.
    @Test
    fun `UpdateSettingsRequest with null initials omits the key on the wire (T-97)`() {
        val request = UpdateSettingsRequest(defaultCurrency = "USD", initials = null)

        val encoded = JsonModule.provideJson().encodeToString(UpdateSettingsRequest.serializer(), request)

        assertEquals("""{"default_currency":"USD"}""", encoded)
        assertFalse(encoded.contains("initials"))
    }

    @Test
    fun `UpdateSettingsRequest with a real initials value includes it on the wire`() {
        val request = UpdateSettingsRequest(defaultCurrency = "USD", initials = "AB")

        val encoded = JsonModule.provideJson().encodeToString(UpdateSettingsRequest.serializer(), request)

        assertTrue(encoded.contains(""""initials":"AB""""))
    }

    @Test
    fun `UpdateSettingsRequest with a genuinely empty initials value still sends the empty string (T-97)`() {
        // Distinct from null: "" is a real, resolved value (e.g. the account has no override) and
        // must still be sent so the server can act on it — only "not known yet" should be omitted.
        val request = UpdateSettingsRequest(defaultCurrency = "USD", initials = "")

        val encoded = JsonModule.provideJson().encodeToString(UpdateSettingsRequest.serializer(), request)

        assertTrue(encoded.contains(""""initials":"""""))
    }

    // T-104: the server reads "platform" to pick this session's inactivity window (62 days for
    // android vs 7 for web). Uses the app's real configured Json for the same reason as the T-97
    // tests above: with encodeDefaults=false a defaulted property would be silently dropped, and
    // the server would then fall back to its own default window.
    @Test
    fun `LoginRequest declares its platform on the wire (T-104)`() {
        val request = LoginRequest("a@example.com", "pw", "Pixel 8", "android")

        val encoded = JsonModule.provideJson().encodeToString(LoginRequest.serializer(), request)

        assertTrue(encoded.contains(""""platform":"android""""))
        assertEquals("android", json.decodeFromString<LoginRequest>(encoded).platform)
    }

    @Test
    fun `ErrorEnvelope decodes error and message`() {
        val envelope = json.decodeFromString<ErrorEnvelope>(
            """{"error": "full_resync_required", "message": "cursor is stale"}""",
        )

        assertEquals("full_resync_required", envelope.error)
        assertEquals("cursor is stale", envelope.message)
    }
}
