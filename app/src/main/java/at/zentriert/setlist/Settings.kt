package at.zentriert.setlist

import android.content.Context

data class PerformSettings(
    val fontSize: Float = 34f,
    val nextCount: Int = 1,
    val keepScreenOn: Boolean = true,
) {
    companion object {
        const val MIN_FONT = 18f
        const val MAX_FONT = 96f
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = PerformSettings(
        fontSize = prefs.getFloat("fontSize", 34f),
        nextCount = prefs.getInt("nextCount", 1),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
    )

    fun save(s: PerformSettings) = prefs.edit()
        .putFloat("fontSize", s.fontSize)
        .putInt("nextCount", s.nextCount)
        .putBoolean("keepScreenOn", s.keepScreenOn)
        .apply()
}
