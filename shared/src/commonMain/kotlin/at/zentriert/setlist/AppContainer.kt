package at.zentriert.setlist

import at.zentriert.setlist.audio.AudioEngine
import at.zentriert.setlist.audio.createAudioEngine
import kotlinx.io.files.Path

/** Wires the app's long-lived objects; created once per process by each platform entry point. */
class AppContainer(
    dataDir: Path,
    store: KeyValueStore,
    val discovery: Discovery,
    audio: AudioEngine = createAudioEngine(),
) {
    val repository = SetlistRepository(dataDir)
    val settingsStore = SettingsStore(store)
    val audio: AudioEngine = audio
}
