package at.zentriert.setlist

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

class SettingsStore(private val store: KeyValueStore) {
    fun load() = PerformSettings(
        fontSize = store.getFloat("fontSize", 34f),
        nextCount = store.getInt("nextCount", 1),
        keepScreenOn = store.getBoolean("keepScreenOn", true),
    )

    fun save(s: PerformSettings) {
        store.putFloat("fontSize", s.fontSize)
        store.putInt("nextCount", s.nextCount)
        store.putBoolean("keepScreenOn", s.keepScreenOn)
    }
}
