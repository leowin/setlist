package at.zentriert.setlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Milliseconds since the Unix epoch. */
expect fun currentTimeMillis(): Long

/** Today's date in the user's locale, for default setlist names. */
expect fun todayLabel(): String

/** Small persistent key/value store (SharedPreferences on Android, NSUserDefaults on iOS). */
interface KeyValueStore {
    fun getFloat(key: String, default: Float): Float
    fun getInt(key: String, default: Int): Int
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putFloat(key: String, value: Float)
    fun putInt(key: String, value: Int)
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
}

/**
 * Stage behaviour of the performance screen: keep the display awake, go full screen and – for
 * "screen off" – show black at minimum backlight so a tap or pedal press brings the list back
 * instantly without a lock screen in between.
 */
@Composable
expect fun StageEffects(keepScreenOn: Boolean, blackout: Boolean)

/** Returns a function that opens the system share sheet with plain text. */
@Composable
expect fun rememberShareText(): (title: String, text: String) -> Unit

/** Returns a function that opens the system file picker; the chosen file arrives as bytes + name. */
@Composable
expect fun rememberFilePicker(onPicked: (bytes: ByteArray, name: String) -> Unit): () -> Unit

/**
 * Sheet music/tabs rendered by the bundled alphaTab (web/alphatab) in a web view.
 * [cursorTick] is in alphaTab ticks (960 per quarter) or null for no cursor. [marks] colours beats while
 * listening: "tick:state,…" with state 0 good, 1 fair, 2 bad (alphaTab ticks).
 */
@Composable
expect fun NotationView(tex: String, cursorTick: Long?, scale: Float, light: Boolean, onTap: (tick: Long) -> Unit, modifier: Modifier, marks: String = "")

/** Monotonic clock in nanoseconds (same clock the audio engine uses for its timestamps). */
expect fun monotonicNanos(): Long

/** Finds and announces band hosts on the local network (mDNS/Bonjour, service _setlist._tcp). */
interface Discovery {
    fun advertise(name: String, port: Int)
    fun stopAdvertising()
    fun discover(onFound: (name: String, host: String, port: Int) -> Unit, onLost: (name: String) -> Unit)
    fun stopDiscovery()
}

/** Returns a function that asks for microphone access; [onResult] gets true when it is granted. */
@Composable
expect fun rememberMicrophonePermission(onResult: (Boolean) -> Unit): () -> Unit
