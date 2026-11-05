package at.zentriert.setlist

import androidx.compose.runtime.Composable
import platform.AVFAudio.AVAudioSession
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun rememberMicrophonePermission(onResult: (Boolean) -> Unit): () -> Unit = {
    AVAudioSession.sharedInstance().requestRecordPermission { granted ->
        dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
    }
}
