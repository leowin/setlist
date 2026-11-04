package at.zentriert.setlist

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.viewmodel.compose.viewModel
import at.zentriert.setlist.ui.App
import kotlinx.io.files.Path
import platform.UIKit.UIViewController

private var activeViewModel: MainViewModel? = null
private var pendingLink: String? = null

/** Entry point for the iOS app (called from Swift). */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    val container = remember { AppContainer(Path(documentsDirectory()), UserDefaultsStore(), BonjourDiscovery()) }
    val vm = viewModel { MainViewModel(container.repository, container.settingsStore, container.audio, container.discovery) }
    activeViewModel = vm
    pendingLink?.let { vm.openLink(it); pendingLink = null }
    App(vm)
}

/** Band invitations and share links opened via Universal Links (SwiftUI `onOpenURL`). */
@Suppress("unused")
fun openLink(url: String) {
    val vm = activeViewModel
    if (vm != null) vm.openLink(url) else pendingLink = url
}
