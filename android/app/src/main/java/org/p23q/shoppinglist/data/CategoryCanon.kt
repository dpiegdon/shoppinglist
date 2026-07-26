package org.p23q.shoppinglist.data

/**
 * Case-insensitive category identity + canonical casing (T-108). Mirror of the web client's
 * `lib/categories.ts` and the rule in docs/archive/specs/client-ui-notes.md — the two clients
 * MUST key categories the same way or they'd show different groupings for the same synced data.
 */
object CategoryCanon {
    const val UNCATEGORIZED_LABEL = "—"

    /** The case-insensitive identity of a category. Trimmed + lowercased; "" means uncategorized. */
    fun key(raw: String): String = raw.trim().lowercase()

    /**
     * Canonical display casing per category key present in [rawCategories] or [categoryOrder].
     * A `category_order` entry's casing wins; otherwise the most-frequent casing among the items,
     * tie-broken by the smaller string (deterministic).
     */
    fun canonicalNames(rawCategories: List<String>, categoryOrder: List<String>): Map<String, String> {
        val votes = HashMap<String, HashMap<String, Int>>()
        for (raw in rawCategories) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val byCasing = votes.getOrPut(trimmed.lowercase()) { HashMap() }
            byCasing[trimmed] = (byCasing[trimmed] ?: 0) + 1
        }
        val names = HashMap<String, String>()
        for ((k, byCasing) in votes) {
            var best = ""
            var bestCount = -1
            for ((casing, count) in byCasing) {
                if (count > bestCount || (count == bestCount && casing < best)) {
                    best = casing
                    bestCount = count
                }
            }
            names[k] = best
        }
        // category_order casing is authoritative — it's what the user set in list settings.
        for (entry in categoryOrder) {
            val trimmed = entry.trim()
            if (trimmed.isNotEmpty()) names[trimmed.lowercase()] = trimmed
        }
        return names
    }

    /** Distinct categories in use (canonical casing), sorted — for the item-form autocomplete. */
    fun distinctCanonical(rawCategories: List<String>, categoryOrder: List<String>): List<String> =
        canonicalNames(rawCategories, categoryOrder).values.sorted()

    data class RenamePlan(
        val itemIds: List<String>,
        val nextCategoryOrder: List<String>,
        val orderChanged: Boolean,
    )

    /**
     * Plan the shared "canonicalize a category" write: rewrite every item whose category matches
     * [fromKey] case-insensitively to [toName], and update the matching `category_order` entry
     * (de-duplicating a case-insensitive collision — a merge). Used by both the item-dialog
     * recase-all and the list-settings rename.
     */
    fun planRename(
        itemsInList: List<Pair<String, String>>, // (id, raw category)
        categoryOrder: List<String>,
        fromKey: String,
        toName: String,
    ): RenamePlan {
        val to = toName.trim()
        val itemIds = itemsInList
            .filter { key(it.second) == fromKey && it.second.trim() != to }
            .map { it.first }

        var orderChanged = false
        val seen = HashSet<String>()
        val nextOrder = ArrayList<String>()
        for (entry in categoryOrder) {
            val replaced = if (key(entry) == fromKey) to else entry
            val replacedKey = key(replaced)
            if (replacedKey.isEmpty()) {
                orderChanged = true
                continue
            }
            if (!seen.add(replacedKey)) {
                orderChanged = true
                continue
            }
            if (replaced != entry) orderChanged = true
            nextOrder.add(replaced)
        }
        return RenamePlan(itemIds, nextOrder, orderChanged)
    }
}
