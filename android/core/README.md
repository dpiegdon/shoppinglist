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
(`ExpenseMathTest`, `CategoryCanonTest`, `NameOrderTest`) resolve them relative
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
| `javax.inject.Inject` on constructors | `DefaultCurrencyState.kt`, `repo/ItemsRepo.kt`, `repo/ListsRepo.kt`, `sync/SyncEngine.kt`, `sync/SyncStatus.kt` |
| `javax.inject.Singleton` | `DefaultCurrencyState.kt`, `sync/SyncStatus.kt` |

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

`api/ApiSource` is the single-account screens' API client; `:app` binds it to
`account/CurrentAccountApi`, the current account's session.

## Accounts

- `db/AccountEntity` is one account; every `ListEntity` has an `accountId` (a
  foreign key), and items reach their account through their list.
- `account/AccountRegistry` is the only writer of the `accounts` table. It keeps
  the table in memory after `load()`, so reads are synchronous; a change applies
  to that copy at once and is written through. `load()` clears every `outdated`
  flag: a new process may be a newer build, and if it is not, its first request
  raises the flag again.
- `account/AccountSessions` builds one `AccountSession` per server account, on
  first use: an `Api` whose interceptors carry that account's token and report to
  that account (`401` sets `signedIn = false` and emits on `forcedLogout`, `426`
  sets `outdated`, an accepted request clears it), and the account's share of
  `SyncStatus`. `updateRequired` is true when every server account is outdated,
  or a sign-in attempt was refused with `426`. `unbound()` is a token-less client
  for what is asked before an account exists.
- `account/CurrentAccount` is the first server account, for the screens that
  still show one. It goes once they take an account id of their own.
- `AuthRepository` creates or re-activates an account on login, after checking
  the server against `MIN_SERVER_PROTOCOL` (`api/Protocol.kt`) the first time; a
  logout signs one account out and keeps its unpushed rows.
- `sync/SyncEngine.syncNow()` syncs every signed-in, up-to-date server account;
  `syncAccount()` is one. `SyncStatus.state` is the worst of the accounts,
  `SyncStatus.accounts` each one.

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
