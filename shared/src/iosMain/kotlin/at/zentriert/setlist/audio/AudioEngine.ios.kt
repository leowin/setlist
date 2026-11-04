package at.zentriert.setlist.audio

/** The CoreAudio backend is built with the iOS app on a Mac; until then the engine stays silent. */
actual fun createAudioEngine(): AudioEngine = SilentAudioEngine()
