package at.zentriert.setlist

data class PerformSettings(
    val fontSize: Float = 34f,
    val nextCount: Int = 1,
    val keepScreenOn: Boolean = true,
    val clickVolume: Float = 0.8f,
    /** 1 = beats only, 2 = eighths, 3 = triplets, 4 = sixteenths. */
    val subdivision: Int = 1,
    val countInBars: Int = 1,
    /** Which part this phone's owner plays; drives the quick mix choices. */
    val myInstrument: Instrument? = null,
    val mixMode: MixMode = MixMode.ALL,
) {
    companion object {
        const val MIN_FONT = 18f
        const val MAX_FONT = 96f
    }
}

enum class MixMode(val label: String) { ALL("Alle"), ONLY_ME("Nur ich"), WITHOUT_ME("Alle außer mir") }

class SettingsStore(private val store: KeyValueStore) {
    fun load() = PerformSettings(
        fontSize = store.getFloat("fontSize", 34f),
        nextCount = store.getInt("nextCount", 1),
        keepScreenOn = store.getBoolean("keepScreenOn", true),
        clickVolume = store.getFloat("clickVolume", 0.8f),
        subdivision = store.getInt("subdivision", 1),
        countInBars = store.getInt("countInBars", 1),
        myInstrument = Instrument.entries.getOrNull(store.getInt("myInstrument", -1)),
        mixMode = MixMode.entries.getOrNull(store.getInt("mixMode", 0)) ?: MixMode.ALL,
    )

    fun save(s: PerformSettings) {
        store.putFloat("fontSize", s.fontSize)
        store.putInt("nextCount", s.nextCount)
        store.putBoolean("keepScreenOn", s.keepScreenOn)
        store.putFloat("clickVolume", s.clickVolume)
        store.putInt("subdivision", s.subdivision)
        store.putInt("countInBars", s.countInBars)
        store.putInt("myInstrument", s.myInstrument?.ordinal ?: -1)
        store.putInt("mixMode", s.mixMode.ordinal)
    }
}
