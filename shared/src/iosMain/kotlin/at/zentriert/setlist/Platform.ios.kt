package at.zentriert.setlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIScreen

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun monotonicNanos(): Long = platform.posix.clock_gettime_nsec_np(platform.posix.CLOCK_MONOTONIC).toLong()

actual fun todayLabel(): String =
    NSDateFormatter().apply { dateStyle = NSDateFormatterMediumStyle }.stringFromDate(NSDate())

/** The app's Documents directory, where data.json and scores live. */
fun documentsDirectory(): String =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String

class UserDefaultsStore(private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) : KeyValueStore {
    private fun has(key: String) = defaults.objectForKey(key) != null
    override fun getFloat(key: String, default: Float) = if (has(key)) defaults.floatForKey(key) else default
    override fun getInt(key: String, default: Int) = if (has(key)) defaults.integerForKey(key).toInt() else default
    override fun getBoolean(key: String, default: Boolean) = if (has(key)) defaults.boolForKey(key) else default
    override fun putFloat(key: String, value: Float) = defaults.setFloat(value, key)
    override fun putInt(key: String, value: Int) = defaults.setInteger(value.toLong(), key)
    override fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, key)
    override fun getString(key: String, default: String) = defaults.stringForKey(key) ?: default
    override fun putString(key: String, value: String) = defaults.setObject(value, key)
}

@Composable
actual fun StageEffects(keepScreenOn: Boolean, blackout: Boolean) {
    // Keep the display awake while on stage (if enabled).
    DisposableEffect(keepScreenOn) {
        UIApplication.sharedApplication.idleTimerDisabled = keepScreenOn
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
    // "Screen off": black picture at minimum backlight; restore the user's brightness afterwards.
    DisposableEffect(blackout) {
        val screen = UIScreen.mainScreen
        val previous = screen.brightness
        if (blackout) screen.brightness = 0.0
        onDispose { if (blackout) screen.brightness = previous }
    }
}

@Composable
actual fun rememberShareText(): (title: String, text: String) -> Unit = { _, text ->
    val controller = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    UIApplication.sharedApplication.keyWindow?.rootViewController
        ?.presentViewController(controller, animated = true, completion = null)
}
