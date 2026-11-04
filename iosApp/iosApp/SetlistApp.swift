import SwiftUI
import Shared

@main
struct SetlistApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                .preferredColorScheme(.dark)
                // Band invitations and share links (Associated Domains: applinks:<server host>).
                .onOpenURL { url in MainViewControllerKt.openLink(url: url.absoluteString) }
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
