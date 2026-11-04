package at.zentriert.setlist.edit

/** General MIDI percussion names for pad labels and the assignment list. */
object DrumNames {
    val ALL: List<Pair<Int, String>> = listOf(
        35 to "Kick 2", 36 to "Kick", 37 to "Side Stick", 38 to "Snare", 39 to "Clap", 40 to "Snare 2",
        41 to "Floor Tom 2", 42 to "HiHat zu", 43 to "Floor Tom", 44 to "HiHat Pedal", 45 to "Tom tief",
        46 to "HiHat offen", 47 to "Tom mittel", 48 to "Tom hoch 2", 49 to "Crash", 50 to "Tom hoch",
        51 to "Ride", 52 to "China", 53 to "Ride Bell", 54 to "Tambourin", 55 to "Splash", 56 to "Cowbell",
        57 to "Crash 2", 58 to "Vibraslap", 59 to "Ride 2", 60 to "Bongo hoch", 61 to "Bongo tief",
        62 to "Conga gedämpft", 63 to "Conga hoch", 64 to "Conga tief", 65 to "Timbale hoch", 66 to "Timbale tief",
        67 to "Agogo hoch", 68 to "Agogo tief", 69 to "Cabasa", 70 to "Maracas", 71 to "Whistle kurz",
        72 to "Whistle lang", 73 to "Guiro kurz", 74 to "Guiro lang", 75 to "Claves", 76 to "Woodblock hoch",
        77 to "Woodblock tief", 78 to "Cuica gedämpft", 79 to "Cuica offen", 80 to "Triangel gedämpft", 81 to "Triangel",
    )
    private val map = ALL.toMap()
    fun name(key: Int): String = map[key] ?: "Drum $key"

    private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun noteName(key: Int): String = NOTE_NAMES[key % 12] + (key / 12 - 1)
}
