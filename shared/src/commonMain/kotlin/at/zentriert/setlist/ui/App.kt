package at.zentriert.setlist.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import at.zentriert.setlist.MainViewModel
import at.zentriert.setlist.Screen

@Composable
fun App(vm: MainViewModel) {
    SetlistTheme {
        Surface {
            // Re-read on every change so edits show up immediately.
            val data by vm.data.collectAsStateWithLifecycle()
            val setlists = data.setlists
            when (val screen = vm.screen) {
                Screen.Home -> HomeScreen(vm, setlists)
                is Screen.Import -> ImportScreen(vm, screen.state)
                Screen.Library -> LibraryScreen(vm, data.library)
                is Screen.Edit -> setlists.firstOrNull { it.id == screen.setlistId }
                    ?.let { EditScreen(vm, it, data.library) }
                    ?: LaunchedEffect(Unit) { vm.goHome() }
                is Screen.Perform -> setlists.firstOrNull { it.id == screen.setlistId }
                    ?.let { PerformScreen(vm, it) }
                    ?: LaunchedEffect(Unit) { vm.goHome() }
            }
        }
    }
}
