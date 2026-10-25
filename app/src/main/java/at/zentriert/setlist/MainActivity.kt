package at.zentriert.setlist

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import at.zentriert.setlist.ui.EditScreen
import at.zentriert.setlist.ui.HomeScreen
import at.zentriert.setlist.ui.ImportScreen
import at.zentriert.setlist.ui.LibraryScreen
import at.zentriert.setlist.ui.PerformScreen
import at.zentriert.setlist.ui.SetlistTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)

        setContent {
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Senden" from Google Notizen (Keep) delivers the note as plain text. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        var name = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
        var body = text
        // Keep puts the note title as the first line of the text as well; don't make it a song.
        if (name.isNotEmpty() && body.trimStart().startsWith(name)) {
            body = body.trimStart().removePrefix(name)
        }
        vm.startImport(body.trim('\n', '\r'), name)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        vm.handleKey(event) || super.dispatchKeyEvent(event)
}
