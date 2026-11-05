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
    /** Total tap→sound delay from the calibration; -1 = not calibrated yet. */
    val inputLatencyMs: Int = -1,
    val quantizeOn: Boolean = true,
    val quantizeDivision: Int = 16,
    val quantizeStrength: Float = 1f,
    val noteDivision: Int = 8,
    val noteTriplet: Boolean = false,
    /** Recording replaces what is in the recorded range (true) or adds to it (false). */
    val recordReplace: Boolean = false,
    /** Sheet music view: which staff kind for stringed instruments and the zoom. */
    val notationView: Int = 2,
    val notationScale: Float = 1.0f,
    /** Concert view: 0 = text instructions, 1 = sheet music of my instrument, 2 = both. */
    val performMode: Int = 0,
    val notationLight: Boolean = false,
    /** Name shown to the band; extra delay applied to synced starts (e.g. for Bluetooth headphones). */
    val myName: String = "",
    val syncOffsetMs: Int = 0,
    /** Server (server/) for band sync, share links and the internet relay; https://… */
    val serverUrl: String = DEFAULT_SERVER,
    /** Random id of this phone, written as the author of every change it makes. */
    val deviceId: String = "",
    /** The band whose songs and setlists are shown; null = private. */
    val currentBandId: String? = null,
    /** Listening: microphone delay to subtract (ms), matching window (ms), grid subdivision (0 = like the metronome). */
    val micOffsetMs: Int = 0,
    val practiceWindowMs: Int = 120,
    val practiceGrid: Int = 0,
    /** Level meter: automatic or a fixed gain in dB. */
    val micAutoGain: Boolean = true,
    val micGainDb: Int = 0,
    /** Onset sensitivity 0–100 (50 = default thresholds). */
    val listenSensitivity: Int = 50,
) {
    companion object {
        const val MIN_FONT = 18f
        const val MAX_FONT = 96f
        /** Set after the first deployment (see server/README.md); empty disables the cloud features. */
        const val DEFAULT_SERVER = "https://setlist-server-814326728745.europe-west1.run.app"
    }
}

enum class MixMode(val label: String) { ALL("Alle"), ONLY_ME("Nur ich"), WITHOUT_ME("Alle außer mir"), NONE("Nichts") }

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
        inputLatencyMs = store.getInt("inputLatencyMs", -1),
        quantizeOn = store.getBoolean("quantizeOn", true),
        quantizeDivision = store.getInt("quantizeDivision", 16),
        quantizeStrength = store.getFloat("quantizeStrength", 1f),
        noteDivision = store.getInt("noteDivision", 8),
        noteTriplet = store.getBoolean("noteTriplet", false),
        recordReplace = store.getBoolean("recordReplace", false),
        notationView = store.getInt("notationView", 2),
        notationScale = store.getFloat("notationScale", 1.0f),
        performMode = store.getInt("performMode", 0),
        notationLight = store.getBoolean("notationLight", false),
        myName = store.getString("myName", store.getString("bandName", "")),
        syncOffsetMs = store.getInt("syncOffsetMs", 0),
        serverUrl = store.getString("serverUrl", PerformSettings.DEFAULT_SERVER),
        deviceId = store.getString("deviceId", "").ifEmpty { newId().also { store.putString("deviceId", it) } },
        currentBandId = store.getString("currentBandId", "").ifEmpty { null },
        micOffsetMs = store.getInt("micOffsetMs", 0),
        practiceWindowMs = store.getInt("practiceWindowMs", 120),
        practiceGrid = store.getInt("practiceGrid", 0),
        micAutoGain = store.getBoolean("micAutoGain", true),
        micGainDb = store.getInt("micGainDb", 0),
        listenSensitivity = store.getInt("listenSensitivity", 50),
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
        store.putInt("inputLatencyMs", s.inputLatencyMs)
        store.putBoolean("quantizeOn", s.quantizeOn)
        store.putInt("quantizeDivision", s.quantizeDivision)
        store.putFloat("quantizeStrength", s.quantizeStrength)
        store.putInt("noteDivision", s.noteDivision)
        store.putBoolean("noteTriplet", s.noteTriplet)
        store.putBoolean("recordReplace", s.recordReplace)
        store.putInt("notationView", s.notationView)
        store.putFloat("notationScale", s.notationScale)
        store.putInt("performMode", s.performMode)
        store.putBoolean("notationLight", s.notationLight)
        store.putString("myName", s.myName)
        store.putInt("syncOffsetMs", s.syncOffsetMs)
        store.putString("serverUrl", s.serverUrl)
        store.putString("deviceId", s.deviceId)
        store.putString("currentBandId", s.currentBandId.orEmpty())
        store.putInt("micOffsetMs", s.micOffsetMs)
        store.putInt("practiceWindowMs", s.practiceWindowMs)
        store.putInt("practiceGrid", s.practiceGrid)
        store.putBoolean("micAutoGain", s.micAutoGain)
        store.putInt("micGainDb", s.micGainDb)
        store.putInt("listenSensitivity", s.listenSensitivity)
    }
}
