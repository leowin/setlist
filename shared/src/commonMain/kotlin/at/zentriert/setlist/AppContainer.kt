package at.zentriert.setlist

import kotlinx.io.files.Path

/** Wires the app's long-lived objects; created once per process by each platform entry point. */
class AppContainer(dataDir: Path, store: KeyValueStore) {
    val repository = SetlistRepository(dataDir)
    val settingsStore = SettingsStore(store)
}
