package at.zentriert.setlist

import android.app.Application

class SetlistApp : Application() {
    val repository by lazy { SetlistRepository(this) }
    val settingsStore by lazy { SettingsStore(this) }
}
