# `:core` — the Android client's data layer

The API client, the Room database, the repositories and the sync engine of the
Android app, as a plain Kotlin/JVM module. The Android SDK is not on this
module's classpath, so nothing here can use Android, and that is what keeps the
code portable. Everything that needs the platform lives in `:app`
(see [`../README.md`](../README.md#modules)).

```bash
./gradlew :core:test   # plain JUnit, no Robolectric
```

The tests that read the cross-client tables in `../../shared-test-cases/`
(`ExpenseMathTest`, `CategoryCanonTest`, `NameOrderTest`, `PastedInviteTest`) resolve them relative
to this directory, the same depth as `app/`.

## What may be imported

- The Kotlin standard library, kotlinx-coroutines, kotlinx-serialization and the
  JDK.
- From `androidx.*`, only `androidx.room` and `androidx.sqlite`. Both are Kotlin
  Multiplatform, so this module compiles against their JVM artifacts.
- Nothing from `android.*`, any other `androidx.*`, or `dagger.*`. Hilt modules
  stay in `:app`.

Tolerated for now, until the module goes multiplatform:

| import | files |
|---|---|
| `retrofit2.http.*` annotations | `api/Api.kt` |
| `okhttp3.Interceptor`, `okhttp3.Response` | `api/AuthInterceptor.kt`, `api/ErrorInterceptor.kt`, `api/Protocol.kt`, `account/AccountSessions.kt` |
| `javax.inject.Inject` on constructors | `repo/ItemsRepo.kt`, `repo/ListsRepo.kt`, `sync/SyncEngine.kt`, `sync/SyncStatus.kt` |
| `javax.inject.Singleton` | `sync/SyncStatus.kt` |

The classes in `account/` and `AuthRepositoryImpl` carry no annotations; `:app`
builds them in its Hilt modules.

## Platform interfaces

When code here needs something only the platform can do, it declares an
interface and `:app` implements it and binds it in Hilt:

| interface | implemented in `:app` by |
|---|---|
| `account/SecretStore` (tokens by local account id), `account/LastOpenedListStore` | `KeystoreSecretStore` (Keystore-backed EncryptedSharedPreferences) |
| `account/ApiFactory` | `RetrofitApiFactory` (Retrofit and OkHttp, and the debug-only certificate opt-in) |
| `DeviceIdProvider` | `DeviceIdModule`, from `ServerConfig` (DataStore) |
| `sync/SyncTrigger` | `SyncScheduler` (WorkManager) |
| `sync/CollaboratorChangeNotifier` | `CollaboratorChangeNotificationPoster` (notifications) |

## Accounts

- `db/AccountEntity` is one account; every `ListEntity` has an `accountId` (a
  foreign key), and every `ItemEntity` carries its list's.
- Lists and items are keyed by `localId`, this phone's own id; `serverId` is the
  wire's `id`, unique per account (`(accountId, serverId)` is a unique index),
  and an item names its list by `listLocalId`. The repositories and everything
  above them take local ids. `SyncEngine` is the only code that looks rows up by
  server id (`getByServerId(accountId, serverId)`): a pull merges into the
  syncing account's row, creating it when absent, so a list two accounts share
  is a row of each. An item pulled for a list the account does not hold gets a
  hidden stub list with every clock at 0, which the list's own pull fills in.
  A row a pull creates takes the local id its row had before a `410` re-base
  dropped it; otherwise its server id, unless another account's row already
  has that local id, and then a fresh one. So a list pulled again after a
  re-base or a new sign-in keeps the local id that notification mutes and the
  last-opened list name it by.
- `account/AccountRegistry` is the only writer of the `accounts` table. It keeps
  the table in memory after `load()`, so reads are synchronous; a change applies
  to that copy at once and is written through. `load()` clears every `outdated`
  flag: a new process may be a newer build, and if it is not, its first request
  raises the flag again. A row the table refuses (the unique server and
  server-side id) is an exception from `add`/`update`, and the copy goes back
  to the table's row. `reorder(ids)` sets the user's order (`sortOrder`).
  `addLocal()` makes the local area, the one account with no server (at most
  one per phone), with an empty label that the UI replaces by a string resource.
  `remove(id)` deletes an account with its lists and items in one transaction;
  the local area only while it holds no list that is not deleted, counted in
  that transaction (it returns false otherwise).
  `withAccountLock(id)` serialises one account's sync, local sign-out and
  removal.
- `account/AccountSessions` builds one `AccountSession` per server account, on
  first use: an `Api` whose interceptors carry that account's token and report to
  that account (a `401` to the account's current token deletes it and sets
  `signedIn = false`: the row says it is signed out, and its lists stay; `426`
  sets `outdated`, an accepted request clears it), and the account's share of
  `SyncStatus`.
  `updateRequired` is true when every server account is outdated and the phone
  holds no local area.
  `unbound()` is a token-less client that reports to no account, for what is
  asked before an account exists and for `/app-version`.
- `AuthRepository` creates or re-activates an account on login and keeps every
  other account. The login says whom it expects (`LoginExpectation`): adding an
  account refuses one that is here and signed in (`AlreadyAddedException`), and
  signing an account in again refuses another account's credentials
  (`WrongAccountException`, also for the local area's row, which no server
  account may take over); either is decided before the matched row changes,
  and ends the session the server just opened. A row that records no
  server-side account (migrated from 3.1.0) takes on the one that signs in again
  for it. A row whose server has not answered this phone yet (no
  `serverProtocol`) takes the URL it is signed in again with; after that the
  URL is the row's, and the login form shows it read-only. Before all that it asks `/app-version` for the server's protocol
  whenever it holds none between `MIN_SERVER_PROTOCOL` and `PROTOCOL_VERSION`
  (`api/Protocol.kt`): the server says it in the `200` and in the
  `no_app_package` `404`. A server below the floor, one above this build and an
  answer that is not a Tuppu server's are each their own exception, for the
  login screen to name. There is no sign-out; removing an account ends its
  session on the server, best-effort, and deletes its rows, and the local area that still holds a list is refused
  (`LocalAreaNotEmptyException`).
- `sync/SyncEngine.syncNow()` syncs every signed-in, up-to-date server account
  that has a token (one without is signed out); `syncAccount()` is one, under
  its account lock. The local area is never synced, and its dirty rows count
  in no account's pending figure. After an account's first successful sync
  in a process, a server whose protocol the account does not hold yet is asked
  `/app-version` once, with no token, and the answer is stored. `SyncStatus.state` is the worst of the
  accounts, `SyncStatus.accounts` each one; a signed-out or outdated account
  counts in its pending and blocked rows only, not in the last sync or error.
- `ListKind.choices(serverAccount)` is the kinds a list of an account can have:
  no ledger in the local area. `ListsRepo.create` throws for another kind, and
  `setKind` returns false instead of converting. `ListsRepo.duplicate(listId,
  copySuffix, targetAccountId)` copies a list into any account on the phone, the
  local area included, with fresh ids and clocks and no roster or close votes;
  the copy's name is the source's, a space and `copySuffix`, which `:app` passes
  from a string resource;
  `ItemsRepo.duplicateForList` gives the copied items the target list's account.
  A ledger is copied only within its own account.

`:app` also builds the database (`Room.databaseBuilder(context, AppDb::class.java, …)`)
and holds its `Migration` objects and `AppDbMigrationTest`.

## Room on the JVM

Room generates `AppDb_Impl` here for the JVM, and that differs from what it
generates for Android in one way: it has no `clearAllTables()`, which exists
only in Room's Android artifact. Calling it from `:app` compiles and then fails
at run time with `AbstractMethodError`. Nothing calls it: rows are deleted per
account, through `AccountRegistry.remove` and the `…ForAccount` DAO deletes, so
the registry's in-memory copy of the `accounts` table stays true.

The exported schemas live in `schemas/`, one directory per `@Database` class.
A change to them is the reviewable record of a schema change.
