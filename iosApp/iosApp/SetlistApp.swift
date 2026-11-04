import SwiftUI
import Shared

@main
struct SetlistApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                .preferredColorScheme(.dark)
        }
    }
}

/// Hosts the Compose Multiplatform UI from the shared Kotlin module.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
