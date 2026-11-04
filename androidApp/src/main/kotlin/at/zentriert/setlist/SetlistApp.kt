package at.zentriert.setlist

import android.app.Application
import kotlinx.io.files.Path

class SetlistApp : Application() {
    val container by lazy {
        AppContainer(
            dataDir = Path(filesDir.absolutePath),
            store = SharedPreferencesStore(getSharedPreferences("settings", MODE_PRIVATE)),
            discovery = NsdDiscovery(this),
        )
    }
}
