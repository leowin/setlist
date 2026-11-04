package at.zentriert.setlist

import androidx.compose.runtime.Composable

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
