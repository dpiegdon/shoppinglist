package org.p23q.shoppinglist.data.db

import androidx.annotation.StringRes
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.core.db.Status

// Status lives in :core, which cannot see Android resources; its user-facing label stays here.

/**
 * The user-facing string RESOURCE naming this status (T-40) — never surface the raw wire value.
 *
 * A resource id rather than a String since T-111: the enum has no Context to resolve one with,
 * and the label has to follow the chosen language like everything else.
 *
 * One canonical wording, shared by the Registry chips and the item dialog and matching the web
 * client word for word (T-124).
 */
@get:StringRes
val Status.label: Int
    get() = when (this) {
        Status.BACKLOG -> R.string.status_backlog
        Status.TODO -> R.string.status_todo
        Status.CHECKED -> R.string.status_checked
    }
