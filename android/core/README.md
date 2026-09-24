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
| `okhttp3.Interceptor`, `okhttp3.Response` | `api/AuthInterceptor.kt`, `api/ErrorInterceptor.kt`, `api/Protocol.kt` |
| `javax.inject.Inject` on constructors | `DefaultCurrencyState.kt`, `api/AuthInterceptor.kt`, `api/ErrorInterceptor.kt`, `api/Protocol.kt`, `api/SessionEvents.kt`, `repo/ItemsRepo.kt`, `repo/ListsRepo.kt`, `sync/SyncEngine.kt`, `sync/SyncStatus.kt` |
| `javax.inject.Singleton` | `DefaultCurrencyState.kt`, `api/Protocol.kt`, `api/SessionEvents.kt`, `sync/SyncStatus.kt` |

## Platform interfaces

When code here needs something only the platform can do, it declares an
interface and `:app` implements it and binds it in Hilt:

| interface | implemented in `:app` by |
|---|---|
| `api/ApiSource` | `ApiProvider` (Retrofit and OkHttp, rebuilt when the server URL changes) |
| `api/TokenProvider`, `SessionState` | `SessionStore` (Keystore-backed EncryptedSharedPreferences) |
| `DeviceIdProvider` | `DeviceIdModule`, from `ServerConfig` (DataStore) |
| `sync/SyncTrigger` | `SyncScheduler` (WorkManager) |
| `sync/CollaboratorChangeNotifier` | `CollaboratorChangeNotificationPoster` (notifications) |

`:app` also builds the database (`Room.databaseBuilder(context, AppDb::class.java, …)`)
and holds its `Migration` objects and `AppDbMigrationTest`.

## Room on the JVM

Room generates `AppDb_Impl` here for the JVM, and that differs from what it
generates for Android in one way: it has no `clearAllTables()`, which exists
only in Room's Android artifact. Calling it from `:app` compiles and then fails
at run time with `AbstractMethodError`. Use `AppDb.clearAll()`
(`db/ClearAll.kt`) instead.

The exported schemas live in `schemas/`, one directory per `@Database` class.
A change to them is the reviewable record of a schema change.
