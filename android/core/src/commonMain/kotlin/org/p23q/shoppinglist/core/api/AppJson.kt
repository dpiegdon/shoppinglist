package org.p23q.shoppinglist.core.api

import kotlinx.serialization.json.Json

/**
 * The one Json this app decodes with. Lenient about unknown keys, so a server that grows a field
 * this build has never heard of keeps working instead of failing to decode (T-205) — which is
 * also why stored wire JSON (an item's expense) must go through this and never Json.Default.
 */
val AppJson = Json { ignoreUnknownKeys = true }
