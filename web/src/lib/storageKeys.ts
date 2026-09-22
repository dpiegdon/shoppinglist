/**
 * Device-local (never synced) localStorage keys shared across more than one module.
 *
 * Kept here rather than on the module that primarily owns each key (OverviewPage, historically) so
 * that AuthContext can clear them on logout (T-272) without importing from a page component —
 * which would also import that page's `useAuth()` call and create a cycle back to this module.
 */

/** The most recently opened list, resumed on next login/entry (Spec). */
export const LAST_LIST_STORAGE_KEY = "shoppinglist_last_list_id";

/** Invite ids this browser chose to ignore (T-233): a JSON array. A device-local choice, as on Android. */
export const IGNORED_INVITES_STORAGE_KEY = "shoppinglist_ignored_invites";
