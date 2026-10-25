package at.zentriert.setlist.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFC107)

private val colors = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    secondary = Color(0xFFFFE082),
    background = Color(0xFF101010),
    surface = Color(0xFF101010),
    surfaceContainer = Color(0xFF1C1C1C),
    surfaceContainerHigh = Color(0xFF242424),
)

/** Always dark: the app is meant for dim stages. */
@Composable
fun SetlistTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, content = content)

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
