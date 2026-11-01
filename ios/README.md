# `ios/` — the iPhone shell

The Xcode project that turns the shared code into the iPhone app
([`../docs/ios-port-design.md`](../docs/ios-port-design.md)): a SwiftUI window
whose only view hosts `MainViewController()` from `:shared`'s iOS source set,
after `startIosApp()` has started Koin, opened the database and run the first
sync (`../android/shared/src/iosMain/…/ios/IosApp.kt`). Everything the user
sees is the shared Compose UI; this directory holds no screens.

The target's "Compile Kotlin" phase runs
`../android/gradlew :shared:embedAndSignAppleFrameworkForXcode`, which links
`Shared.framework` (`:shared` with `:core`) for the SDK and configuration Xcode
is building and puts it where the target's framework search path points. So
Xcode's own build is the whole build; nothing is run by hand first.

`Configuration/Config.xcconfig` holds what varies: the bundle id, the app name,
the versions (the Android app's, `MARKETING_VERSION` = `versionName`,
`CURRENT_PROJECT_VERSION` = `versionCode`) and the Apple Developer team, empty
for the simulator.

## Building and running in the simulator

On a Mac with Xcode (the maintainer's macOS guest: an Intel Mac, whose
simulator runs x86_64, the `iosX64` target), from this directory:

```bash
# Build for the simulator (the first run compiles the Kotlin side: minutes).
xcodebuild -project Tuppu.xcodeproj -scheme Tuppu -configuration Debug \
    -destination 'platform=iOS Simulator,name=iPhone 16' -derivedDataPath build build

# Boot a device once (xcrun simctl list devices), install and launch.
xcrun simctl boot "iPhone 16"
xcrun simctl install booted build/Build/Products/Debug-iphonesimulator/Tuppu.app
xcrun simctl launch booted org.p23q.shoppinglist
xcrun simctl io booted screenshot screen.png
```

The destination picks the architecture: this simulator on an Intel Mac is the
`iosX64` target, on an Apple Silicon Mac `iosSimulatorArm64`. A device build
(`-destination generic/platform=iOS`, `-configuration Release`) links the
`iosArm64` framework with Kotlin/Native's whole-program optimization, which
takes about 40 minutes on the guest and more heap than the project's
`gradle.properties` (sized for a small host) allows: put

```
org.gradle.jvmargs=-Xmx8g -XX:MaxMetaspaceSize=1g
kotlin.daemon.jvmargs=-Xmx6g
kotlin.native.jvmArgs=-Xmx6g
```

in `~/.gradle/gradle.properties` on the Mac. Without a team id, pass
`CODE_SIGNING_ALLOWED=NO` to build unsigned; installing on a phone and
TestFlight need `TEAM_ID` and signing (T-355).

The app only talks HTTPS. Against a development server with a self-signed
certificate, trust its certificate in the simulator first:
`xcrun simctl keychain booted add-root-cert server.pem`.

## No Metal, no screens

Compose Multiplatform draws on iOS through Skia on Metal and has no software
renderer, so on a Mac without a GPU — the maintainer's macOS guest, a VM —
the simulator cannot show the shared UI: `MetalView` would end the process
with "Metal is not supported on this system". `ContentView` checks for a Metal
device and shows a plain notice instead, so the app still starts there, and
`startIosApp()` has run: the data layer is up.

That is what the app's tests use. `TuppuTests` (`xcodebuild test`, the Tuppu
scheme) run inside the app on the simulator, on its real Keychain, database
and HTTP engine, through `IosServices` (`:shared`'s `iosMain`): the Keychain
store, and — given a server — login, a list, an item, two syncs and the
account's removal:

```bash
TEST_RUNNER_TUPPU_SERVER_URL=https://localhost:8723 \
TEST_RUNNER_TUPPU_EMAIL=sim@example.org TEST_RUNNER_TUPPU_PASSWORD=… \
xcodebuild test -project Tuppu.xcodeproj -scheme Tuppu \
    -destination 'platform=iOS Simulator,name=iPhone 16' -derivedDataPath build
```

(`TEST_RUNNER_` variables in xcodebuild's environment reach the tests; after
the command they would be build settings.)

Without the three variables the round trip is skipped. Seeing the screens on
iOS needs a Mac with a GPU or a hosted macOS runner (T-355); on Android the
same screens are covered by `:shared`'s Robolectric and screenshot tests.
