# T-65: Collaborator-Change Notifications Implementation Plan

> **ARCHIVED — historical record, written 2026-07-15.** T-65 shipped; this plan is
> kept as a worked example of the plan format, not as instructions. Paths and host
> details below describe the machine it was written on. See the
> [archive index](../README.md).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a sync pulls item changes made by a *different account*, post one local Android notification per sync pass ("3 items changed in Groceries — tap to view"), with a global on/off toggle in app Settings and a per-list mute toggle in list properties.

**Architecture:** `SyncEngine` already pulls `last_touched_by` (account-scoped, T-64) per item; after merging, it computes per-list counts of items touched by another account and hands them to a `CollaboratorChangeNotifier` seam. The Android implementation (`CollaboratorChangeNotificationPoster`) applies four gates — global pref, per-list mute, app-foregrounded, notification permission — then posts a single batched notification whose tap opens the affected list (or the overview when several lists changed). Preferences live in a DataStore store (`NotificationPrefsStore`, same pattern as `ThemePreferenceStore` — client-only, survives logout). Detecting "another account" requires storing our own `account_id` at login, which the client currently discards.

**Tech Stack:** Kotlin, Hilt, WorkManager (already in place — no scheduling changes), DataStore Preferences, NotificationCompat, Robolectric + kotlinx-coroutines-test for tests.

## Global Constraints

- **Android only.** No server or web changes. The `/login` response already returns `account_id`; `ItemDto.last_touched_by` is already on the wire.
- **This host is a 4-core / 3.7 GB Raspberry Pi.** Run Gradle invocations one at a time, in the background with a generous timeout (≥ 400 s; a cold daemon + KSP can take 5 min). After the final verification round, stop daemons: `./gradlew --stop`.
- **Gradle caches test runs.** Any verification re-run of an unchanged build MUST use `--rerun`, or `:app:testDebugUnitTest` silently reports `UP-TO-DATE` having executed zero tests.
- **T-66 test conventions are mandatory** for every new/modified ViewModel test: `runTest(mainDispatcherRule.dispatcher) { … }` (never bare `runTest { }`), and Room built with `.setQueryCoroutineContext(mainDispatcherRule.dispatcher)` (never `Dispatchers.IO`) in any test class that has a `MainDispatcherRule`. `SyncEngineTest` has no rule and keeps `Dispatchers.IO`.
- ~~**Never stage** `android/app/src/main/res/drawable/ic_brand_logo.xml` or `ic_launcher_foreground.xml` — deliberately uncommitted from earlier work.~~ *(No longer true: both files were committed after this plan was written.)*
- Comparison basis is **account id, never device id** (ticket: two devices on one account must not self-notify).
- Detection counts only rows pulled in *this* sync pass (cursor delta), and stays fully silent when the pass started from cursor 0 (initial hydration / forced full resync would otherwise mass-notify).
- One notification per sync pass, fixed notification id (latest replaces prior) — never one per row.
- minSdk 26 (`NotificationChannel` required unconditionally), targetSdk 36 (`POST_NOTIFICATIONS` runtime permission required on API 33+).
- UI copy: Settings section "Notifications", toggle label "Collaborator changes"; list-properties section "Notifications", toggle label "Notify about changes to this list".
- gittoc CLI is at `<repo>/.agents/skills/gittoc/scripts/gittoc` (not on PATH).

## File Structure

```
android/app/src/main/java/org/p23q/shoppinglist/
  data/SessionStore.kt                        MODIFY  add accountId to SessionState + SessionStore
  data/AuthRepository.kt                      MODIFY  store accountId at login
  data/AppForegroundState.kt                  CREATE  @Singleton isForeground flag
  ShoppingListApp.kt                          MODIFY  drive AppForegroundState from existing observer
  data/notify/NotificationPrefsStore.kt       CREATE  DataStore: global enabled + muted list ids
  data/sync/CollaboratorChangeNotifier.kt     CREATE  seam interface + CollaboratorChange data class
  data/sync/SyncEngine.kt                     MODIFY  detect + report collaborator changes
  data/notify/CollaboratorChangeNotificationPoster.kt  CREATE  gates + channel + notification
  MainActivity.kt                             MODIFY  EXTRA_OPEN_LIST_ID tap routing
  ui/settings/SettingsViewModel.kt            MODIFY  global toggle state
  ui/settings/SettingsScreen.kt               MODIFY  toggle UI + POST_NOTIFICATIONS request
  ui/listprops/ListPropsViewModel.kt          MODIFY  per-list toggle state
  ui/listprops/ListPropsScreen.kt             MODIFY  toggle UI
android/app/src/main/AndroidManifest.xml      MODIFY  POST_NOTIFICATIONS permission
android/app/src/test/java/org/p23q/shoppinglist/
  data/FakeSessionState.kt                    MODIFY  accountId field
  data/AuthRepositoryTest.kt                  MODIFY  login-stores-accountId test
  data/notify/NotificationPrefsStoreTest.kt   CREATE
  data/notify/CollaboratorChangeNotificationPosterTest.kt  CREATE
  data/sync/SyncEngineTest.kt                 MODIFY  detection tests + ctor arg
  ui/redeem/RedeemViewModelTest.kt            MODIFY  SyncEngine ctor arg only
  ui/redeem/RedeemScreenTest.kt               MODIFY  SyncEngine ctor arg only (2 sites)
  ui/redeem/RedeemDialogTest.kt               MODIFY  SyncEngine ctor arg only
  ui/settings/SettingsViewModelTest.kt        MODIFY  toggle tests + ctor arg
  ui/settings/SettingsScreenTest.kt           MODIFY  ctor arg (3 sites)
  ui/listprops/ListPropsViewModelTest.kt      MODIFY  toggle tests + ctor arg
  ui/listprops/ListPropsScreenTest.kt         MODIFY  ctor arg (3 sites)
```

Per-task verification runs only the affected test classes; Task 8 runs the full suite. Each Gradle run:
`cd <repo>/android && timeout 500 ./gradlew :app:testDebugUnitTest --rerun --tests "<CLASS>" > <log> 2>&1` in the background.

---

### Task 1: Store the account id at login

The client discards `LoginResponse.accountId` today; collaborator detection needs it.

**Files:**
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/data/SessionStore.kt`
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/data/AuthRepository.kt` (`login()`)
- Modify: `android/app/src/test/java/org/p23q/shoppinglist/data/FakeSessionState.kt`
- Test: `android/app/src/test/java/org/p23q/shoppinglist/data/AuthRepositoryTest.kt`

**Interfaces:**
- Produces: `SessionState.accountId: String?` (read by Task 4's SyncEngine detection; `FakeSessionState().apply { accountId = "acc-me" }` in tests).

- [ ] **Step 1: Write the failing test** — in `AuthRepositoryTest.kt`, after the existing `login also writes the default currency through the in-memory mirror (T-55)` test:

```kotlin
@Test
fun `login stores the account id for collaborator-change detection (T-65)`() = runTest {
    pointAtServer()
    server.enqueue(
        MockResponse().setResponseCode(200)
            .setBody("""{"token": "tok-123", "account_id": "acc-1", "email": "milk@example.com"}"""),
    )
    server.enqueue(MockResponse().setResponseCode(200).setBody("""{"default_currency": "EUR", "initials": "MI"}"""))

    repository.login("milk@example.com", "hunter2")

    assertEquals("acc-1", sessionState.accountId)
}
```

This won't compile until `SessionState.accountId` exists — a compile failure is this step's expected "red".

- [ ] **Step 2: Add the field.** In `SessionStore.kt`:
  - interface `SessionState`: add `var accountId: String?` after `accountEmail`.
  - `SessionStore`: add

```kotlin
override var accountId: String?
    get() = prefs.getString(KEY_ACCOUNT_ID, null)
    set(value) = prefs.edit().putString(KEY_ACCOUNT_ID, value).apply()
```

  and `const val KEY_ACCOUNT_ID = "account_id"` in the companion. (`clear()` already wipes all prefs — no change.)
  - `FakeSessionState.kt`: add `override var accountId: String? = null` and `accountId = null` inside `clear()`.

- [ ] **Step 3: Store it at login.** In `AuthRepositoryImpl.login()`, after `sessionState.token = response.token`:

```kotlin
sessionState.accountId = response.accountId
```

- [ ] **Step 4: Run** `--tests "org.p23q.shoppinglist.data.AuthRepositoryTest"`. Expected: PASS (all, including the new test).

- [ ] **Step 5: Commit**

```bash
cd <repo>
git add android/app/src/main/java/org/p23q/shoppinglist/data/SessionStore.kt \
        android/app/src/main/java/org/p23q/shoppinglist/data/AuthRepository.kt \
        android/app/src/test/java/org/p23q/shoppinglist/data/FakeSessionState.kt \
        android/app/src/test/java/org/p23q/shoppinglist/data/AuthRepositoryTest.kt
git commit -m "feat(android): store the account id at login (T-65 groundwork)"
```

---

### Task 2: NotificationPrefsStore

**Files:**
- Create: `android/app/src/main/java/org/p23q/shoppinglist/data/notify/NotificationPrefsStore.kt`
- Test: `android/app/src/test/java/org/p23q/shoppinglist/data/notify/NotificationPrefsStoreTest.kt`

**Interfaces:**
- Produces: `NotificationPrefsStore` with `notificationsEnabled: Flow<Boolean>` (default `true`), `mutedListIds: Flow<Set<String>>` (default empty), `suspend setNotificationsEnabled(Boolean)`, `suspend setListMuted(listId: String, muted: Boolean)`. Tests construct it as `NotificationPrefsStore(PreferenceDataStoreFactory.create { tempFile })`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package org.p23q.shoppinglist.data.notify

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class NotificationPrefsStoreTest {

    private lateinit var store: NotificationPrefsStore

    @Before
    fun setUp() {
        val file = File.createTempFile("notification_prefs_test", ".preferences_pb")
        file.deleteOnExit()
        store = NotificationPrefsStore(PreferenceDataStoreFactory.create { file })
    }

    @Test
    fun `notifications are enabled by default with no lists muted`() = runTest {
        assertTrue(store.notificationsEnabled.first())
        assertTrue(store.mutedListIds.first().isEmpty())
    }

    @Test
    fun `setNotificationsEnabled false persists and can be re-enabled`() = runTest {
        store.setNotificationsEnabled(false)
        assertFalse(store.notificationsEnabled.first())

        store.setNotificationsEnabled(true)
        assertTrue(store.notificationsEnabled.first())
    }

    @Test
    fun `muting and unmuting a list only affects that list`() = runTest {
        store.setListMuted("list-a", muted = true)
        store.setListMuted("list-b", muted = true)
        assertEquals(setOf("list-a", "list-b"), store.mutedListIds.first())

        store.setListMuted("list-a", muted = false)
        assertEquals(setOf("list-b"), store.mutedListIds.first())
    }
}
```

- [ ] **Step 2: Run** `--tests "org.p23q.shoppinglist.data.notify.NotificationPrefsStoreTest"`. Expected: compile FAIL (class doesn't exist).

- [ ] **Step 3: Implement** (mirrors `ThemePreferenceStore.kt` structure exactly):

```kotlin
package org.p23q.shoppinglist.data.notify

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NotificationPrefsDataStore

@Module
@InstallIn(SingletonComponent::class)
object NotificationPrefsModule {
    @Provides
    @Singleton
    @NotificationPrefsDataStore
    fun provideNotificationPrefsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("notification_prefs") }
}

/**
 * Collaborator-change notification preferences (T-65): a global on/off plus per-list mutes.
 * Client-only, device-local (like [org.p23q.shoppinglist.data.ThemePreferenceStore]) — muting a
 * list on your phone shouldn't mute it on your tablet, so this is deliberately NOT server-synced,
 * and survives logout.
 */
@Singleton
class NotificationPrefsStore @Inject constructor(
    @param:NotificationPrefsDataStore private val dataStore: DataStore<Preferences>,
) {
    val notificationsEnabled: Flow<Boolean> = dataStore.data.map { it[ENABLED_KEY] ?: true }

    val mutedListIds: Flow<Set<String>> = dataStore.data.map { it[MUTED_LISTS_KEY] ?: emptySet() }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setListMuted(listId: String, muted: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[MUTED_LISTS_KEY] ?: emptySet()
            prefs[MUTED_LISTS_KEY] = if (muted) current + listId else current - listId
        }
    }

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("notifications_enabled")
        val MUTED_LISTS_KEY = stringSetPreferencesKey("muted_list_ids")
    }
}
```

- [ ] **Step 4: Run the test class again.** Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/org/p23q/shoppinglist/data/notify/NotificationPrefsStore.kt \
        android/app/src/test/java/org/p23q/shoppinglist/data/notify/NotificationPrefsStoreTest.kt
git commit -m "feat(android): notification preference store — global toggle + per-list mutes (T-65)"
```

---### Task 3: AppForegroundState

Notifications are suppressed while the app is on screen (the change is already visible). `ShoppingListApp` already registers a `ProcessLifecycleOwner` observer — extend it rather than adding a second lifecycle hook.

**Files:**
- Create: `android/app/src/main/java/org/p23q/shoppinglist/data/AppForegroundState.kt`
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/ShoppingListApp.kt`

**Interfaces:**
- Produces: `AppForegroundState` with `@Volatile var isForeground: Boolean = false` (read by Task 5's poster; tests set it directly).

- [ ] **Step 1: Create the class** (no dedicated test — it's a mutable flag; behavior is covered by Task 5's poster tests):

```kotlin
package org.p23q.shoppinglist.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app currently has a started (visible) activity — set by ShoppingListApp's
 * ProcessLifecycleOwner observer. Used to suppress collaborator-change notifications while the
 * user is already looking at live data (T-65). @Volatile: written on the main thread, read from
 * WorkManager's background thread.
 */
@Singleton
class AppForegroundState @Inject constructor() {
    @Volatile
    var isForeground: Boolean = false
}
```

- [ ] **Step 2: Wire it in `ShoppingListApp`** — inject and extend the existing observer:

```kotlin
@Inject lateinit var appForegroundState: AppForegroundState
```

and in `onCreate()` replace the existing observer object with:

```kotlin
ProcessLifecycleOwner.get().lifecycle.addObserver(
    object : DefaultLifecycleObserver {
        // Notes: sync on app foreground, in addition to the periodic/after-edit triggers.
        override fun onStart(owner: LifecycleOwner) {
            appForegroundState.isForeground = true
            syncScheduler.scheduleImmediate()
        }

        override fun onStop(owner: LifecycleOwner) {
            appForegroundState.isForeground = false
        }
    },
)
```

- [ ] **Step 3: Commit** (compilation is verified by Task 4's test run — the next Gradle invocation compiles everything):

```bash
git add android/app/src/main/java/org/p23q/shoppinglist/data/AppForegroundState.kt \
        android/app/src/main/java/org/p23q/shoppinglist/ShoppingListApp.kt
git commit -m "feat(android): track app foreground state for notification suppression (T-65)"
```

---

### Task 4: Collaborator-change detection in SyncEngine

**Files:**
- Create: `android/app/src/main/java/org/p23q/shoppinglist/data/sync/CollaboratorChangeNotifier.kt`
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/data/sync/SyncEngine.kt`
- Test: `android/app/src/test/java/org/p23q/shoppinglist/data/sync/SyncEngineTest.kt`
- Modify (ctor arg only): `ui/redeem/RedeemViewModelTest.kt` (1 site), `ui/redeem/RedeemScreenTest.kt` (2 sites), `ui/redeem/RedeemDialogTest.kt` (1 site)

**Interfaces:**
- Produces: `CollaboratorChange(listId: String, listName: String, changedItemCount: Int)`; `CollaboratorChangeNotifier { suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>) }`. `SyncEngine` gains an 8th constructor param `notifier: CollaboratorChangeNotifier` (appended last). Task 5 implements the interface.

- [ ] **Step 1: Create the seam** (`CollaboratorChangeNotifier.kt`):

```kotlin
package org.p23q.shoppinglist.data.sync

/** One list's worth of collaborator-authored changes pulled in a single sync pass (T-65). */
data class CollaboratorChange(val listId: String, val listName: String, val changedItemCount: Int)

/**
 * Seam between [SyncEngine]'s detection and the Android notification machinery, so the engine
 * (and its JVM tests) never touch NotificationManager. The engine reports RAW detections —
 * preference filtering (global toggle, per-list mutes, foreground) is the implementation's job.
 */
fun interface CollaboratorChangeNotifier {
    suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>)
}
```

- [ ] **Step 2: Write the failing tests.** In `SyncEngineTest.kt`, add to the test class a recording fake + tests. First add the fake near the top of the class:

```kotlin
private class RecordingNotifier : CollaboratorChangeNotifier {
    val calls = mutableListOf<List<CollaboratorChange>>()
    override suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>) {
        calls.add(changes)
    }
}

private val notifier = RecordingNotifier()
```

Update the existing construction (line ~70) to append it:

```kotlin
syncEngine = SyncEngine(db.itemDao(), db.listDao(), apiProvider, sessionState, serverConfig, db, syncStatus, notifier)
```

Then the tests. **Adapt the JSON bodies to this file's existing response-fixture helpers** — SyncEngineTest already has helpers/fixtures for sync responses (read the file first and reuse its idioms; the shapes below show the required content, with `last_touched_by` at the item's top level as `_item_to_wire` sends it):

```kotlin
@Test
fun `items pulled with another account's last_touched_by are reported to the notifier, grouped per list (T-65)`() = runTest {
    pointAtServer()
    sessionState.accountId = "acc-me"
    sessionState.syncCursor = 5
    // Response: 2 items in list-1 touched by acc-other, 1 item in list-1 touched by acc-me,
    // plus list-1 itself (so the engine can resolve its name). Cursor advances to 6.
    server.enqueue(MockResponse().setResponseCode(200).setBody(
        syncResponseJson(
            cursor = 6,
            lists = listOf(listJson(id = "list-1", name = "Groceries")),
            items = listOf(
                itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-other"),
                itemJson(id = "i2", listId = "list-1", name = "Eggs", lastTouchedBy = "acc-other"),
                itemJson(id = "i3", listId = "list-1", name = "Bread", lastTouchedBy = "acc-me"),
            ),
        ),
    ))

    syncEngine.syncNow()

    assertEquals(1, notifier.calls.size)
    assertEquals(listOf(CollaboratorChange("list-1", "Groceries", 2)), notifier.calls.single())
}

@Test
fun `a pull containing only own-account and null-account rows stays silent (T-65)`() = runTest {
    pointAtServer()
    sessionState.accountId = "acc-me"
    sessionState.syncCursor = 5
    server.enqueue(MockResponse().setResponseCode(200).setBody(
        syncResponseJson(
            cursor = 6,
            lists = listOf(listJson(id = "list-1", name = "Groceries")),
            items = listOf(
                itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-me"),
                itemJson(id = "i2", listId = "list-1", name = "Eggs", lastTouchedBy = null),
            ),
        ),
    ))

    syncEngine.syncNow()

    assertTrue(notifier.calls.isEmpty())
}

@Test
fun `a cursor-zero pull (initial hydration or full resync) never notifies (T-65)`() = runTest {
    pointAtServer()
    sessionState.accountId = "acc-me"
    sessionState.syncCursor = 0
    server.enqueue(MockResponse().setResponseCode(200).setBody(
        syncResponseJson(
            cursor = 6,
            lists = listOf(listJson(id = "list-1", name = "Groceries")),
            items = listOf(itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-other")),
        ),
    ))

    syncEngine.syncNow()

    assertTrue(notifier.calls.isEmpty())
}

@Test
fun `an unknown own account id stays silent rather than guessing (T-65)`() = runTest {
    pointAtServer()
    sessionState.accountId = null // session predates accountId storage
    sessionState.syncCursor = 5
    server.enqueue(MockResponse().setResponseCode(200).setBody(
        syncResponseJson(
            cursor = 6,
            lists = listOf(listJson(id = "list-1", name = "Groceries")),
            items = listOf(itemJson(id = "i1", listId = "list-1", name = "Milk", lastTouchedBy = "acc-other")),
        ),
    ))

    syncEngine.syncNow()

    assertTrue(notifier.calls.isEmpty())
}
```

If the file lacks reusable `syncResponseJson`/`listJson`/`itemJson` helpers, write them as private functions in the test class emitting the wire shapes the existing tests already use (full field-clock JSON per field; `"last_touched_by"` as a top-level item key).

- [ ] **Step 3: Run** `--tests "org.p23q.shoppinglist.data.sync.SyncEngineTest"`. Expected: compile FAIL (SyncEngine has no 8th param).

- [ ] **Step 4: Implement detection.** In `SyncEngine.kt`:
  - constructor: append `private val notifier: CollaboratorChangeNotifier,`
  - after the two merge loops and `sessionState.syncCursor = response.cursor`, before `syncStatus.succeeded(...)`:

```kotlin
reportCollaboratorChanges(requestCursor = request.cursor, pulledItems = response.changes.items)
```

  - and the private method:

```kotlin
/**
 * Detects rows in this pull that were last touched by a DIFFERENT account and reports them
 * (T-65). Account-scoped, never device-scoped: a user's own second device must not
 * self-notify. Deliberately silent when: this pass started from cursor 0 (initial hydration /
 * full resync — everything would look "new"), our own account id is unknown (pre-T-65
 * session — can't distinguish, so don't guess), or a row's last_touched_by is null (pre-T-64
 * row never re-touched). Reports RAW detections; pref filtering lives in the notifier impl.
 */
private suspend fun reportCollaboratorChanges(requestCursor: Long, pulledItems: List<ItemDto>) {
    if (requestCursor == 0L) return
    val myAccountId = sessionState.accountId ?: return
    val foreign = pulledItems.filter { it.lastTouchedBy != null && it.lastTouchedBy != myAccountId }
    if (foreign.isEmpty()) return
    val changes = foreign.groupBy { it.listId }.map { (listId, items) ->
        CollaboratorChange(
            listId = listId,
            listName = listDao.getById(listId)?.name?.value ?: "a shared list",
            changedItemCount = items.size,
        )
    }
    notifier.notifyCollaboratorChanges(changes)
}
```

  (List names resolve from the DAO *after* the merge loops, so a list first seen in this same pull is found.)

- [ ] **Step 5: Fix the 4 other construction sites.** In `RedeemViewModelTest.kt`, `RedeemScreenTest.kt` (×2), `RedeemDialogTest.kt`, append `, { }` — wait, `CollaboratorChangeNotifier` is a `fun interface`, so the literal is:

```kotlin
SyncEngine(db.itemDao(), db.listDao(), apiProvider, sessionState, serverConfig, db, org.p23q.shoppinglist.data.sync.SyncStatus(), CollaboratorChangeNotifier { })
```

with import `org.p23q.shoppinglist.data.sync.CollaboratorChangeNotifier` added to each file. (SyncEngineTest's own site was already updated in Step 2.)

- [ ] **Step 6: Run** `--tests "org.p23q.shoppinglist.data.sync.SyncEngineTest" --tests "org.p23q.shoppinglist.ui.redeem.RedeemViewModelTest" --tests "org.p23q.shoppinglist.ui.redeem.RedeemScreenTest" --tests "org.p23q.shoppinglist.ui.redeem.RedeemDialogTest"`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/org/p23q/shoppinglist/data/sync/CollaboratorChangeNotifier.kt \
        android/app/src/main/java/org/p23q/shoppinglist/data/sync/SyncEngine.kt \
        android/app/src/test/java/org/p23q/shoppinglist/data/sync/SyncEngineTest.kt \
        android/app/src/test/java/org/p23q/shoppinglist/ui/redeem/
git commit -m "feat(android): detect collaborator-authored changes during sync (T-65)"
```

---

### Task 5: Notification poster, channel, permission, tap routing

**Files:**
- Create: `android/app/src/main/java/org/p23q/shoppinglist/data/notify/CollaboratorChangeNotificationPoster.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (permission)
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/MainActivity.kt` (tap routing)
- Test: `android/app/src/test/java/org/p23q/shoppinglist/data/notify/CollaboratorChangeNotificationPosterTest.kt`

**Interfaces:**
- Consumes: `CollaboratorChangeNotifier`/`CollaboratorChange` (Task 4), `NotificationPrefsStore` (Task 2), `AppForegroundState` (Task 3).
- Produces: Hilt binding `CollaboratorChangeNotifier -> CollaboratorChangeNotificationPoster`; `MainActivity.EXTRA_OPEN_LIST_ID` (`"open_list_id"`).

- [ ] **Step 1: Manifest permission.** In `AndroidManifest.xml`, after the existing `<uses-permission>` lines:

```xml
<!-- Collaborator-change notifications (T-65); runtime-requested on API 33+ from Settings. -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 2: Write the failing tests** (Robolectric grants manifest permissions by default, so `areNotificationsEnabled()` is true in tests):

```kotlin
package org.p23q.shoppinglist.data.notify

import android.app.NotificationManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.p23q.shoppinglist.MainActivity
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.sync.CollaboratorChange
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class CollaboratorChangeNotificationPosterTest {

    private lateinit var context: Context
    private lateinit var prefs: NotificationPrefsStore
    private lateinit var foreground: AppForegroundState
    private lateinit var poster: CollaboratorChangeNotificationPoster
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val file = File.createTempFile("poster_prefs_test", ".preferences_pb")
        file.deleteOnExit()
        prefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { file })
        foreground = AppForegroundState()
        poster = CollaboratorChangeNotificationPoster(context, prefs, foreground)
        notificationManager = context.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun `a single changed list posts one notification naming the list, tapping opens it`() = runTest {
        poster.notifyCollaboratorChanges(listOf(CollaboratorChange("list-1", "Groceries", 3)))

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        val n = posted.single()
        assertEquals("Groceries", shadowOf(n).contentTitle)
        assertEquals("3 items changed", shadowOf(n).contentText)
        val tapIntent = shadowOf(n.contentIntent).savedIntent
        assertEquals("list-1", tapIntent.getStringExtra(MainActivity.EXTRA_OPEN_LIST_ID))
    }

    @Test
    fun `multiple changed lists collapse to one summary notification opening the overview`() = runTest {
        poster.notifyCollaboratorChanges(
            listOf(CollaboratorChange("list-1", "Groceries", 2), CollaboratorChange("list-2", "Hardware", 1)),
        )

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        val n = posted.single()
        assertEquals("3 items changed across 2 lists", shadowOf(n).contentText)
        assertEquals(null, shadowOf(n.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_OPEN_LIST_ID))
    }

    @Test
    fun `the global toggle off suppresses everything`() = runTest {
        prefs.setNotificationsEnabled(false)

        poster.notifyCollaboratorChanges(listOf(CollaboratorChange("list-1", "Groceries", 3)))

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `a muted list is dropped while other lists still notify`() = runTest {
        prefs.setListMuted("list-1", muted = true)

        poster.notifyCollaboratorChanges(
            listOf(CollaboratorChange("list-1", "Groceries", 2), CollaboratorChange("list-2", "Hardware", 1)),
        )

        val posted = shadowOf(notificationManager).allNotifications
        assertEquals(1, posted.size)
        assertEquals("Hardware", shadowOf(posted.single()).contentTitle)
    }

    @Test
    fun `every audible list muted means nothing is posted`() = runTest {
        prefs.setListMuted("list-1", muted = true)

        poster.notifyCollaboratorChanges(listOf(CollaboratorChange("list-1", "Groceries", 2)))

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `a foregrounded app suppresses notifications — the change is already on screen`() = runTest {
        foreground.isForeground = true

        poster.notifyCollaboratorChanges(listOf(CollaboratorChange("list-1", "Groceries", 2)))

        assertTrue(shadowOf(notificationManager).allNotifications.isEmpty())
    }

    @Test
    fun `singular item count reads naturally`() = runTest {
        poster.notifyCollaboratorChanges(listOf(CollaboratorChange("list-1", "Groceries", 1)))

        assertEquals("1 item changed", shadowOf(shadowOf(notificationManager).allNotifications.single()).contentText)
    }
}
```

If `shadowOf(n).contentTitle` doesn't exist on this Robolectric version, use `n.extras.getString(Notification.EXTRA_TITLE)` / `Notification.EXTRA_TEXT` instead — check compilation and adapt; the assertions' meaning stays identical.

- [ ] **Step 3: Run** `--tests "org.p23q.shoppinglist.data.notify.CollaboratorChangeNotificationPosterTest"`. Expected: compile FAIL.

- [ ] **Step 4: Implement the poster:**

```kotlin
package org.p23q.shoppinglist.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import org.p23q.shoppinglist.MainActivity
import org.p23q.shoppinglist.R
import org.p23q.shoppinglist.data.AppForegroundState
import org.p23q.shoppinglist.data.sync.CollaboratorChange
import org.p23q.shoppinglist.data.sync.CollaboratorChangeNotifier
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CollaboratorChangeNotifierModule {
    @Binds
    abstract fun bindNotifier(poster: CollaboratorChangeNotificationPoster): CollaboratorChangeNotifier
}

/**
 * Turns SyncEngine's raw collaborator-change detections into at most ONE notification per sync
 * pass (T-65). Gates, in order: app foregrounded (change already visible on screen — stay
 * silent), global toggle (Settings), per-list mutes (list properties), notification permission
 * (API 33+ runtime; also covers the user disabling notifications in system settings). A fixed
 * notification id means a newer sync's notification replaces a stale unread one.
 */
@Singleton
class CollaboratorChangeNotificationPoster @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val prefs: NotificationPrefsStore,
    private val foregroundState: AppForegroundState,
) : CollaboratorChangeNotifier {

    override suspend fun notifyCollaboratorChanges(changes: List<CollaboratorChange>) {
        if (changes.isEmpty() || foregroundState.isForeground) return
        if (!prefs.notificationsEnabled.first()) return
        val muted = prefs.mutedListIds.first()
        val audible = changes.filter { it.listId !in muted }
        if (audible.isEmpty()) return
        if (!canPost()) return

        ensureChannel()
        val totalItems = audible.sumOf { it.changedItemCount }
        val singleList = audible.singleOrNull()
        val title = singleList?.listName ?: "Shopping lists"
        val text = if (singleList != null) {
            itemsChangedText(singleList.changedItemCount)
        } else {
            "${itemsChangedText(totalItems)} across ${audible.size} lists"
        }
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            singleList?.let { putExtra(MainActivity.EXTRA_OPEN_LIST_ID, it.listId) }
        }
        val pending = PendingIntent.getActivity(
            context, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Explicit permission check on 33+ (satisfies lint's MissingPermission); system-toggle check below it. */
    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Collaborator changes", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun itemsChangedText(count: Int): String =
        if (count == 1) "1 item changed" else "$count items changed"

    private companion object {
        const val CHANNEL_ID = "collaborator_changes"
        const val NOTIFICATION_ID = 1001
    }
}
```

- [ ] **Step 5: Tap routing in `MainActivity`.** Add to the class:

```kotlin
companion object {
    /** A notification tap's target list (T-65); absent = open normally. */
    const val EXTRA_OPEN_LIST_ID = "open_list_id"
}
```

and replace the `startDestination` computation:

```kotlin
val notifiedListId = intent.getStringExtra(EXTRA_OPEN_LIST_ID)
val startDestination = when {
    session.token == null -> Routes.LOGIN
    // A collaborator-change notification tap deep-links straight to the affected list (T-65).
    notifiedListId != null -> Routes.list(notifiedListId)
    else -> authedStartDestination(session.lastOpenedListId)
}
```

- [ ] **Step 6: Run** `--tests "org.p23q.shoppinglist.data.notify.CollaboratorChangeNotificationPosterTest"`. Expected: PASS (7 tests). If shadow accessors mismatch the Robolectric version, adapt per Step 2's note and re-run.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/org/p23q/shoppinglist/data/notify/CollaboratorChangeNotificationPoster.kt \
        android/app/src/main/AndroidManifest.xml \
        android/app/src/main/java/org/p23q/shoppinglist/MainActivity.kt \
        android/app/src/test/java/org/p23q/shoppinglist/data/notify/CollaboratorChangeNotificationPosterTest.kt
git commit -m "feat(android): post collaborator-change notifications with tap-to-open (T-65)"
```

---

### Task 6: Global toggle in Settings

**Files:**
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/ui/settings/SettingsViewModel.kt`
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/ui/settings/SettingsScreen.kt`
- Test: `android/app/src/test/java/org/p23q/shoppinglist/ui/settings/SettingsViewModelTest.kt`
- Modify (ctor arg only): `android/app/src/test/java/org/p23q/shoppinglist/ui/settings/SettingsScreenTest.kt` (3 sites)

**Interfaces:**
- Consumes: `NotificationPrefsStore` (Task 2).
- Produces: `SettingsUiState.notificationsEnabled: Boolean`; `SettingsViewModel.setNotificationsEnabled(Boolean): Job`; `SettingsViewModel` gains an 8th ctor param `notificationPrefs: NotificationPrefsStore` (appended last).

- [ ] **Step 1: Write the failing tests** in `SettingsViewModelTest.kt` (remember: `runTest(mainDispatcherRule.dispatcher)`):

```kotlin
@Test
fun `notification toggle state loads from prefs and updates live (T-65)`() = runTest(mainDispatcherRule.dispatcher) {
    val viewModel = newViewModel()
    assertTrue(viewModel.uiState.first { it.notificationsEnabled }.notificationsEnabled)

    viewModel.setNotificationsEnabled(false).join()

    assertFalse(viewModel.uiState.first { !it.notificationsEnabled }.notificationsEnabled)
    assertFalse(notificationPrefs.notificationsEnabled.first())
}
```

Test-class plumbing: add a `private lateinit var notificationPrefs: NotificationPrefsStore` field; in `setUp()` create it exactly like Task 2's test (temp `preferences_pb` file); append it to the `newViewModel()` construction. Imports: `org.p23q.shoppinglist.data.notify.NotificationPrefsStore`.

- [ ] **Step 2: Run** `--tests "org.p23q.shoppinglist.ui.settings.SettingsViewModelTest"`. Expected: compile FAIL.

- [ ] **Step 3: Implement.** `SettingsViewModel.kt`:
  - `SettingsUiState`: add `val notificationsEnabled: Boolean = true,`
  - ctor: append `private val notificationPrefs: NotificationPrefsStore,`
  - in `init`, alongside the theme collector:

```kotlin
viewModelScope.launch {
    notificationPrefs.notificationsEnabled.collect { enabled ->
        _uiState.update { it.copy(notificationsEnabled = enabled) }
    }
}
```

  - new method (near `setTheme`):

```kotlin
/** Global collaborator-change notification toggle (T-65); per-list mutes live in list properties. */
fun setNotificationsEnabled(enabled: Boolean): Job = viewModelScope.launch {
    notificationPrefs.setNotificationsEnabled(enabled)
}
```

  - import `org.p23q.shoppinglist.data.notify.NotificationPrefsStore`.

`SettingsScreen.kt` — a "Notifications" section between the Theme section and the debug cert toggle, with the API 33+ permission request fired when the user turns it ON (screen-level, keeping the ViewModel Android-free):

```kotlin
Text("Notifications", style = MaterialTheme.typography.titleMedium)
val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.weight(1f)) {
        Text("Collaborator changes")
        Text(
            "Notify when someone else edits a shared list. Mute individual lists in their list properties.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Switch(
        checked = state.notificationsEnabled,
        onCheckedChange = { enabled ->
            viewModel.setNotificationsEnabled(enabled)
            // API 33+ needs the runtime permission; requesting on enable (not cold start) per T-65.
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
}
Spacer(Modifier.height(16.dp))
```

Imports to add: `android.Manifest`, `android.os.Build`, `androidx.activity.compose.rememberLauncherForActivityResult`, `androidx.activity.result.contract.ActivityResultContracts`.

- [ ] **Step 4: Fix `SettingsScreenTest.kt`'s 3 construction sites** — each gains a `NotificationPrefsStore` built on a fresh temp file (pattern per site, matching the existing crashLogWriter blocks):

```kotlin
val notifPrefsFile = File.createTempFile("settings_screen_notif_prefs", ".preferences_pb")
notifPrefsFile.deleteOnExit()
val notificationPrefs = NotificationPrefsStore(PreferenceDataStoreFactory.create { notifPrefsFile })
```

appended as the 8th `SettingsViewModel(...)` argument; import `org.p23q.shoppinglist.data.notify.NotificationPrefsStore`.

- [ ] **Step 5: Run** `--tests "org.p23q.shoppinglist.ui.settings.SettingsViewModelTest" --tests "org.p23q.shoppinglist.ui.settings.SettingsScreenTest"`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/org/p23q/shoppinglist/ui/settings/ \
        android/app/src/test/java/org/p23q/shoppinglist/ui/settings/
git commit -m "feat(android): global collaborator-notification toggle in Settings (T-65)"
```

---

### Task 7: Per-list mute in list properties

**Files:**
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/ui/listprops/ListPropsViewModel.kt`
- Modify: `android/app/src/main/java/org/p23q/shoppinglist/ui/listprops/ListPropsScreen.kt`
- Test: `android/app/src/test/java/org/p23q/shoppinglist/ui/listprops/ListPropsViewModelTest.kt`
- Modify (ctor arg only): `android/app/src/test/java/org/p23q/shoppinglist/ui/listprops/ListPropsScreenTest.kt` (3 sites)

**Interfaces:**
- Consumes: `NotificationPrefsStore.mutedListIds` / `setListMuted` (Task 2).
- Produces: `ListPropsUiState.notificationsEnabledForList: Boolean` (default `true`); `ListPropsViewModel.setListNotificationsEnabled(Boolean): Job`; `ListPropsViewModel` gains a 5th ctor param `notificationPrefs: NotificationPrefsStore` (appended last).

- [ ] **Step 1: Write the failing test** in `ListPropsViewModelTest.kt`:

```kotlin
@Test
fun `the per-list notification toggle reflects and writes the mute preference (T-65)`() = runTest(mainDispatcherRule.dispatcher) {
    val viewModel = newViewModel()
    assertTrue(viewModel.uiState.first { it.notificationsEnabledForList }.notificationsEnabledForList)

    viewModel.setListNotificationsEnabled(false).join()

    assertFalse(viewModel.uiState.first { !it.notificationsEnabledForList }.notificationsEnabledForList)
    assertEquals(setOf(listId), notificationPrefs.mutedListIds.first())

    viewModel.setListNotificationsEnabled(true).join()

    assertTrue(viewModel.uiState.first { it.notificationsEnabledForList }.notificationsEnabledForList)
    assertTrue(notificationPrefs.mutedListIds.first().isEmpty())
}
```

Plumbing: `private lateinit var notificationPrefs: NotificationPrefsStore` field, created in `setUp()` on a temp file, appended to the `newViewModel()` helper's `ListPropsViewModel(...)` construction.

- [ ] **Step 2: Run** `--tests "org.p23q.shoppinglist.ui.listprops.ListPropsViewModelTest"`. Expected: compile FAIL.

- [ ] **Step 3: Implement.** `ListPropsViewModel.kt`:
  - `ListPropsUiState`: add `val notificationsEnabledForList: Boolean = true,`
  - ctor: append `private val notificationPrefs: NotificationPrefsStore,`
  - in `init` (still local-only — DataStore is local):

```kotlin
viewModelScope.launch {
    notificationPrefs.mutedListIds.collect { muted ->
        _uiState.update { it.copy(notificationsEnabledForList = listId !in muted) }
    }
}
```

  - new method (near `requestLeave`):

```kotlin
/** Per-list collaborator-notification mute (T-65) — device-local, deliberately not synced. */
fun setListNotificationsEnabled(enabled: Boolean): Job = viewModelScope.launch {
    notificationPrefs.setListMuted(listId, muted = !enabled)
}
```

`ListPropsScreen.kt` — a "Notifications" section between the Notes section and "Shared with":

```kotlin
Text("Notifications", style = MaterialTheme.typography.titleMedium)
Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text("Notify about changes to this list", modifier = Modifier.weight(1f))
    Switch(
        checked = state.notificationsEnabledForList,
        onCheckedChange = { viewModel.setListNotificationsEnabled(it) },
    )
}
Spacer(Modifier.height(16.dp))
```

Imports: `androidx.compose.material3.Switch`.

- [ ] **Step 4: Fix `ListPropsScreenTest.kt`'s 3 construction sites** — same temp-file `NotificationPrefsStore` pattern as Task 6 Step 4, appended as the 5th `ListPropsViewModel(...)` argument.

- [ ] **Step 5: Run** `--tests "org.p23q.shoppinglist.ui.listprops.ListPropsViewModelTest" --tests "org.p23q.shoppinglist.ui.listprops.ListPropsScreenTest"`. Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/org/p23q/shoppinglist/ui/listprops/ \
        android/app/src/test/java/org/p23q/shoppinglist/ui/listprops/
git commit -m "feat(android): per-list notification mute in list properties (T-65)"
```

---

### Task 8: Full verification and ticket close

- [ ] **Step 1: Full suite ×2.** `timeout 500 ./gradlew :app:testDebugUnitTest --rerun` twice (sequentially, background). Expected: both `BUILD SUCCESSFUL`. Any failure: read the JUnit XML for the full trace and fix root-cause before proceeding (see `app/build/test-results/testDebugUnitTest/TEST-*.xml`).

- [ ] **Step 2: Lint.** `timeout 500 ./gradlew :app:lintDebug --offline`. Expected: `BUILD SUCCESSFUL`. Watch specifically for `MissingPermission` on the `notify()` call — the explicit `checkSelfPermission` gate in `canPost()` should satisfy it; if not, hoist the check to directly guard the `notify()` call site.

- [ ] **Step 3: Stop daemons.** `./gradlew --stop` (Pi memory discipline).

- [ ] **Step 4: Verify nothing unexpected is uncommitted.** `git status --short` should show ONLY `ic_brand_logo.xml` and `ic_launcher_foreground.xml` (never staged). Anything else means a missed `git add` in an earlier task — commit it with the matching task's message.

- [ ] **Step 5: Close the ticket.**

```bash
GITTOC=<repo>/.agents/skills/gittoc/scripts/gittoc
"$GITTOC" note T-65 "Done. SyncEngine detects pulled items whose last_touched_by differs from the logged-in account (account-scoped per the ticket; silent on cursor-0 hydration, unknown own account, or null last_touched_by) and reports raw per-list counts to a CollaboratorChangeNotifier seam. The Android poster gates on: app-foregrounded (silent - change already on screen), the new global Settings toggle, per-list mutes (list properties), and POST_NOTIFICATIONS (runtime-requested on enable, API 33+). One batched notification per sync pass, fixed id; tap opens the list (or overview when several lists changed). Prefs are DataStore-backed, device-local, survive logout. Commits: <list the task commit hashes>."
"$GITTOC" close T-65
```

- [ ] **Step 6: Report** to the user: what shipped, the gate order, test counts, and that manual on-device verification of the actual notification UX falls under T-29 (a unit-tested notification pipeline still deserves one real-device look).

---

## Self-Review (performed)

- **Spec coverage:** account-scoped detection ✔ (Task 4, `sessionState.accountId`), changed-rows-only ✔ (cursor-delta pull + cursor-0 suppression), permission on 33+ requested at a sensible moment ✔ (Task 6, on toggle-enable), one channel + tap-to-open list/overview ✔ (Task 5), batched per sync run ✔ (single fixed-id notification), silent no-change syncs ✔ (empty `foreign` early-return), global toggle in Settings ✔ (Task 6), per-list toggle in list settings ✔ (Task 7), foreground behavior decided: suppress ✔ (Task 3+5), web out of scope ✔ (untouched).
- **Type consistency:** `CollaboratorChange(listId, listName, changedItemCount)` used identically in Tasks 4/5; `NotificationPrefsStore` API identical in Tasks 2/5/6/7; `EXTRA_OPEN_LIST_ID` defined Task 5, consumed Task 5's test; ctor params always appended last.
- **Known judgment calls encoded:** notification prefs survive logout (like theme); `lastTouchedBy == null` never notifies; unknown own account id never notifies; `SyncEngineTest` fixtures may need local JSON helpers (flagged in Task 4 Step 2).
