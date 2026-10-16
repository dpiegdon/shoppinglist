package org.p23q.shoppinglist.data.db

import org.jetbrains.compose.resources.StringResource
import org.p23q.shoppinglist.core.db.Status
import org.p23q.shoppinglist.resources.Res
import org.p23q.shoppinglist.resources.status_backlog
import org.p23q.shoppinglist.resources.status_checked
import org.p23q.shoppinglist.resources.status_todo

// Status lives in :core, which cannot see the string resources; its user-facing label stays here.

/**
 * The user-facing string RESOURCE naming this status (T-40) — never surface the raw wire value.
 *
 * A resource rather than a String since T-111: the label has to follow the chosen language like
 * everything else, and the enum does not know which language that is.
 *
 * One canonical wording, shared by the Registry chips and the item dialog and matching the web
 * client word for word (T-124).
 */
val Status.label: StringResource
    get() = when (this) {
        Status.BACKLOG -> Res.string.status_backlog
        Status.TODO -> Res.string.status_todo
        Status.CHECKED -> Res.string.status_checked
    }
