import SwiftUI
import UIKit
import Metal
import Shared

/// `MainViewController()` (:shared's iosMain) is the whole app: TuppuApp from where a cold start lands.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        IosAppKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        if MTLCreateSystemDefaultDevice() == nil {
            // Compose Multiplatform draws through Skia on Metal and has no software renderer on
            // iOS: in a simulator on a Mac without a GPU (the maintainer's macOS guest) the shared
            // UI cannot be shown at all, and would end the process. The Kotlin side has started
            // (startIosApp), so the app's XCTests can still drive it; see ../README.md.
            VStack(spacing: 12) {
                Text("Tuppu").font(.title)
                Text("This device has no Metal graphics, which the screens need. The app's data layer is running; its tests can use it.")
                    .multilineTextAlignment(.center)
                    .padding()
            }
        } else {
            ComposeView()
                .ignoresSafeArea(.keyboard) // Compose has its own keyboard handling
        }
    }
}
