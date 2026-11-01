import SwiftUI
import Shared

/// The iOS shell: Koin, the database and the first sync start in `startIosApp()` (:shared's
/// iosMain, IosApp.kt), as `ShoppingListApp` does on Android; the window shows the shared UI.
@main
struct TuppuApp: App {
    init() {
        IosAppKt.startIosApp()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
