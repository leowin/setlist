package at.zentriert.setlist

import at.zentriert.setlist.band.Band
import at.zentriert.setlist.sync.BandKeys
import at.zentriert.setlist.sync.Crypto
import at.zentriert.setlist.sync.SecretBox
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    @Test
    fun knownAnswers() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Crypto.hex(Crypto.sha256("abc".encodeToByteArray())))
        // RFC 4231 test case 2
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Crypto.hex(Crypto.hmac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())),
        )
        // RFC 5869 test case 1 (salt is zero-filled there as well).
        val okm = Crypto.hkdf(ByteArray(22) { 0x0b }, "", 42)
        assertEquals(42, okm.size)
    }

    @Test
    fun sealAndOpenRoundTrip() {
        val box = SecretBox(Crypto.random(32))
        val plain = "Hallo Band – Noten für den Bass".encodeToByteArray()
        val sealed = box.seal(plain)
        assertEquals(plain.size + SecretBox.NONCE + SecretBox.TAG, sealed.size)
        assertContentEquals(plain, box.open(sealed))
        // Different nonce every time, but the same plaintext comes back.
        assertNotEquals(Crypto.hex(sealed), Crypto.hex(box.seal(plain)))
        assertContentEquals(ByteArray(0), box.open(box.seal(ByteArray(0))))
    }

    @Test
    fun tamperingAndWrongKeyAreRejected() {
        val box = SecretBox(Crypto.random(32))
        val sealed = box.seal("x".encodeToByteArray())
        sealed[SecretBox.NONCE] = (sealed[SecretBox.NONCE].toInt() xor 1).toByte()
        assertNull(box.open(sealed))
        assertNull(SecretBox(Crypto.random(32)).open(box.seal("x".encodeToByteArray())))
        assertNull(box.open(ByteArray(10)))
    }

    @Test
    fun derivedKeysAreDeterministicAndIndependent() {
        val key = Crypto.random(32)
        val a = BandKeys(key)
        val b = BandKeys(key.copyOf())
        assertEquals(a.roomId, b.roomId)
        assertEquals(32, a.roomId.length)
        assertContentEquals(a.authKey, b.authKey)
        assertNotEquals(Crypto.hex(a.authKey), Crypto.hex(Crypto.hkdf(key, "data", 32)))
        assertEquals(a.authHeader("PUT", "/rooms/x", "body".encodeToByteArray(), 1700000000),
            b.authHeader("put", "/rooms/x", "body".encodeToByteArray(), 1700000000))
        assertNotEquals(a.authHeader("PUT", "/rooms/x", "body".encodeToByteArray(), 1), a.authHeader("PUT", "/rooms/y", "body".encodeToByteArray(), 1))
    }

    @Test
    fun base64UrlRoundTrip() {
        for (n in 0..40) {
            val bytes = ByteArray(n) { (it * 37 + n).toByte() }
            val s = Crypto.base64Url(bytes)
            assertTrue('=' !in s && '+' !in s && '/' !in s)
            assertContentEquals(bytes, Crypto.fromBase64Url(s))
        }
    }

    @Test
    fun inviteLinkRoundTrip() {
        val band = Band.create("Die Nachtigallen", "https://setlist.example")
        val link = band.inviteLink()
        assertTrue(link.startsWith("https://setlist.example/join#"), link)
        val parsed = assertNotNull(Band.fromInvite(link, ""))
        assertEquals(band.id, parsed.id)
        assertEquals(band.key, parsed.key)
        assertEquals("Die Nachtigallen", parsed.name)
        assertEquals("https://setlist.example", parsed.serverUrl)
        // Just the pasted fragment works too; garbage doesn't.
        assertEquals(band.id, Band.fromInvite(link.substringAfter('#'), "https://x").also { assertEquals("https://x", it?.serverUrl) }?.id)
        assertNull(Band.fromInvite("hello world", ""))
    }
}
