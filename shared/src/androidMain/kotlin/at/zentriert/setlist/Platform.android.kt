package at.zentriert.setlist

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.DateFormat
import java.util.Date

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun todayLabel(): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())

class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getFloat(key: String, default: Float) = prefs.getFloat(key, default)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Window.setBrightness(value: Float) {
    attributes = attributes.apply { screenBrightness = value }
}

@Composable
actual fun StageEffects(keepScreenOn: Boolean, blackout: Boolean) {
    val window = LocalContext.current.findActivity()?.window

    // Keep the display awake while on stage (if enabled).
    DisposableEffect(window, keepScreenOn) {
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Full screen: hide status and navigation bars (swipe from the edge shows them briefly).
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // "Screen off": black picture at minimum backlight; the display stays awake.
    DisposableEffect(window, blackout) {
        window?.setBrightness(
            if (blackout) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
        )
        onDispose { window?.setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
}

@Composable
actual fun rememberShareText(): (title: String, text: String) -> Unit {
    val context = LocalContext.current
    return { title, text ->
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, title))
    }
}
