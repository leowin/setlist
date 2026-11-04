package at.zentriert.setlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.lastPathComponent
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size > 0) result.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return result
}

private class PickerDelegate(val onPicked: (ByteArray, String) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val data = NSData.dataWithContentsOfURL(url) ?: return
        onPicked(data.toByteArray(), url.lastPathComponent ?: "import.mid")
    }
}

@Composable
actual fun rememberFilePicker(onPicked: (bytes: ByteArray, name: String) -> Unit): () -> Unit {
    val delegate = remember { PickerDelegate(onPicked) }
    return {
        val picker = UIDocumentPickerViewController(documentTypes = listOf("public.data"), inMode = UIDocumentPickerMode.UIDocumentPickerModeImport)
        picker.delegate = delegate
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(picker, animated = true, completion = null)
    }
}
