# iPhone port: design

Tuppu gets an iPhone client that shares the Android client's code: the data layer (`:core`) and
the screens (Compose Multiplatform). Android and iPhone then have the same layout, wording and
behaviour by construction, and the parity rule in AGENTS.md covers three clients with the effort
of two. Everything that exists because the phone holds several accounts applies to the iPhone as
it does to Android.

## Decisions

- **Shared Compose UI**, not SwiftUI. The iPhone app is an Xcode shell around the shared screens.
  It looks like the Android app; native touches can come later where they matter.
- **No Mac on the maintainer's side.** Everything up to the iOS shell is built and tested on Linux
  through the Android target. The shared modules' iOS targets are compiled on Linux with Kotlin's
  klib cross-compilation where that works. Linking, signing and running the app happen on a hosted
  macOS CI service, which uploads to TestFlight (decided at step 3).
- **Wire protocol unchanged** until step 4.

## Modules (target)

| module | kind | holds |
|---|---|---|
| `:core` | KMP: jvm, iosArm64, iosSimulatorArm64 | API (Ktor), Room database, repositories, sync engine, platform interfaces |
| `:shared` | KMP: android, iosArm64, iosSimulatorArm64 | screens, view models, navigation, theme, strings and drawables (Compose resources), dependency wiring (Koin) |
| `:app` | Android application | Activity, WorkManager workers, notifications, Keystore, DataStore, the Android implementations of the platform interfaces |
| `ios/` | Xcode project | the iOS shell: `UIViewController` hosting the shared UI, Keychain, background refresh, the iOS implementations |

`:core` keeps a `jvm()` target rather than an Android one, so it stays free of the Android SDK and its
tests stay plain JUnit.

## Steps

0. **`:core` multiplatform** (T-295). Ktor instead of Retrofit/OkHttp (OkHttp engine on Android,
   Darwin on iOS), no `javax.inject`, pure-Kotlin replacements for UUID, time, locks, URI, Unicode
   normalisation and exceptions; Room per platform. No user-visible change.
1. **iOS platform implementations** (T-349), compiled but not yet run: Keychain `SecretStore`, database
   location, device id, Darwin HTTP engine with the self-signed-certificate opt-in, a `SyncTrigger`.
2. **Shared UI module**, in three tickets, each leaving Android unchanged for its users:
   - **2a, dependency injection** (T-350): Hilt out, Koin in (Koin is multiplatform and has Compose,
     ViewModel and WorkManager support). Still all in `:app`.
   - **2b, resources** (T-351): strings and drawables become Compose resources (`composeResources/values*/strings.xml`
     keeps the Android XML format and the nine locales). `crossClient.test.ts` and the string
     checks follow the new path. Code outside composition (notifications, workers) reads strings
     through the resources API.
   - **2c, the move** (T-352): screens, view models, navigation and theme move to `:shared`'s common source
     set. What needs Android goes behind `expect`/`actual` or an interface implemented in `:app`:
     intents and the share sheet, clipboard, number/date/currency formatting (`AppFormat`), locale
     switching, system-bar icons, crash logs, update download. Robolectric screen tests and the
     Roborazzi screenshots stay on `:shared`'s Android target.
3. **iOS shell** (T-353; needs the macOS CI and an Apple Developer account): Xcode project, login,
   overview, lists and items, sync while the app is open; TestFlight builds from CI.
4. **Background sync and notifications on iOS** (T-354). iOS has no WorkManager; background refresh runs when
   the system chooses. Reliable invite and collaborator notifications need Apple push: the server
   registers device tokens and sends pushes (a wire change with its own design).
5. **Distribution and updates** (T-355). TestFlight / App Store; the update-required screen links to the
   store on iOS, the server keeps serving the APK for Android.

## Testing

- Android behaviour is the reference: every step up to 2c is verified by the existing gate
  (`verify-all.sh`), which also compiles the iOS targets once they exist.
- iOS-only code is kept thin, so little can break where there is no simulator at hand.
