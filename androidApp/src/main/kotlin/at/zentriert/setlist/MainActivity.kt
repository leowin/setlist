package at.zentriert.setlist

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import at.zentriert.setlist.ui.App

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = (application as SetlistApp).container
                return MainViewModel(container.repository, container.settingsStore, container.audio) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent { App(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Senden" from Google Notizen (Keep) delivers the note as plain text. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val name = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
        var body = text
        // Keep puts the note title as the first line of the text as well; don't make it a song.
        if (name.isNotEmpty() && body.trimStart().startsWith(name)) {
            body = body.trimStart().removePrefix(name)
        }
        vm.startImport(body.trim('\n', '\r'), name)
    }

    /** Bluetooth page turners/pedals usually send arrow or page keys. Volume keys stay volume keys. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = when (event.keyCode) {
            in NEXT_KEYS -> PerformKey.NEXT
            in PREVIOUS_KEYS -> PerformKey.PREVIOUS
            else -> null
        }
        if (key == null || !vm.inPerformance) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.handleKey(key)
        return true
    }

    private companion object {
        val NEXT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        val PREVIOUS_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
    }
}
