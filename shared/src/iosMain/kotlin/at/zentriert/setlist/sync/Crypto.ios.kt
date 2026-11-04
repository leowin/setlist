package at.zentriert.setlist.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCryptorCreateWithMode
import platform.CoreCrypto.CCCryptorRefVar
import platform.CoreCrypto.CCCryptorRelease
import platform.CoreCrypto.CCCryptorUpdate
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCModeCTR
import platform.CoreCrypto.kCCModeOptionCTR_BE
import platform.CoreCrypto.ccNoPadding
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
actual object CryptoPrimitives {
    actual fun sha256(data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        val input = if (data.isEmpty()) ByteArray(1) else data
        input.usePinned { i -> out.usePinned { o -> CC_SHA256(i.addressOf(0), data.size.convert(), o.addressOf(0).reinterpret()) } }
        return out
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
        val k = if (key.isEmpty()) ByteArray(1) else key
        val d = if (data.isEmpty()) ByteArray(1) else data
        k.usePinned { kp -> d.usePinned { dp -> out.usePinned { op ->
            CCHmac(kCCHmacAlgSHA256, kp.addressOf(0), key.size.convert(), dp.addressOf(0), data.size.convert(), op.addressOf(0))
        } } }
        return out
    }

    actual fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val out = ByteArray(data.size)
        memScoped {
            val cryptor = alloc<CCCryptorRefVar>()
            key.usePinned { kp -> iv.usePinned { ip ->
                CCCryptorCreateWithMode(
                    kCCEncrypt, kCCModeCTR, kCCAlgorithmAES, ccNoPadding,
                    ip.addressOf(0), kp.addressOf(0), key.size.convert(), null, 0.convert(), 0, kCCModeOptionCTR_BE, cryptor.ptr,
                )
            } }
            val moved = alloc<size_tVar>()
            data.usePinned { dp -> out.usePinned { op ->
                CCCryptorUpdate(cryptor.value, dp.addressOf(0), data.size.convert(), op.addressOf(0), out.size.convert(), moved.ptr)
            } }
            CCCryptorRelease(cryptor.value)
        }
        return out
    }

    actual fun randomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        if (count > 0) out.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
        return out
    }
}
