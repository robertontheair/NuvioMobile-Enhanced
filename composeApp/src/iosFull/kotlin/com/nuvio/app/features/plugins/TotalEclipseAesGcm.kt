package com.nuvio.app.features.plugins

/**
 * Total Eclipse: AES-GCM (NIST SP 800-38D) built on a plain AES block cipher, so the app uses
 * only public CommonCrypto (CCCrypt in ECB mode). CommonCrypto's CCCryptorGCM* functions are
 * private API, and App Store Connect rejects any build that references them (ITMS-90338), even
 * for TestFlight. Used for plugin crypto only; no additional authenticated data (as before).
 *
 * [ecb] encrypts whole 16-byte blocks with the key (AES-ECB, no padding).
 */
internal object TotalEclipseAesGcm {
    private const val BLOCK = 16
    private const val TAG = 16

    /** ciphertext followed by the 16-byte tag. */
    fun encrypt(iv: ByteArray, plain: ByteArray, ecb: (ByteArray) -> ByteArray): ByteArray {
        val h = ecb(ByteArray(BLOCK))
        val j0 = j0(h, iv)
        val cipher = ctr(j0, plain, ecb)
        val tag = tag(h, j0, cipher, ecb)
        return cipher + tag
    }

    /** [data] is ciphertext followed by the 16-byte tag; throws when the tag doesn't match. */
    fun decrypt(iv: ByteArray, data: ByteArray, ecb: (ByteArray) -> ByteArray): ByteArray {
        require(data.size >= TAG) { "Data too short for GCM decryption" }
        val cipher = data.copyOfRange(0, data.size - TAG)
        val expected = data.copyOfRange(data.size - TAG, data.size)
        val h = ecb(ByteArray(BLOCK))
        val j0 = j0(h, iv)
        val actual = tag(h, j0, cipher, ecb)
        var diff = 0
        for (i in 0 until TAG) diff = diff or (actual[i].toInt() xor expected[i].toInt())
        check(diff == 0) { "AES-GCM tag verification failed" }
        return ctr(j0, cipher, ecb)
    }

    private fun j0(h: ByteArray, iv: ByteArray): ByteArray {
        if (iv.size == 12) return iv + byteArrayOf(0, 0, 0, 1)
        val padded = iv + ByteArray((BLOCK - iv.size % BLOCK) % BLOCK) + ByteArray(8) + be64(iv.size.toLong() * 8)
        return ghash(h, padded)
    }

    /** CTR from inc32(J0): keystream = AES(counter blocks), all in one ECB call. */
    private fun ctr(j0: ByteArray, input: ByteArray, ecb: (ByteArray) -> ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val blocks = (input.size + BLOCK - 1) / BLOCK
        val counters = ByteArray(blocks * BLOCK)
        var counter = j0.copyOf()
        for (b in 0 until blocks) {
            counter = inc32(counter)
            counter.copyInto(counters, b * BLOCK)
        }
        val stream = ecb(counters)
        return ByteArray(input.size) { i -> (input[i].toInt() xor stream[i].toInt()).toByte() }
    }

    private fun tag(h: ByteArray, j0: ByteArray, cipher: ByteArray, ecb: (ByteArray) -> ByteArray): ByteArray {
        val padded = cipher + ByteArray((BLOCK - cipher.size % BLOCK) % BLOCK) +
            be64(0) + be64(cipher.size.toLong() * 8)
        val s = ghash(h, padded)
        val e = ecb(j0)
        return ByteArray(TAG) { i -> (e[i].toInt() xor s[i].toInt()).toByte() }
    }

    private fun inc32(block: ByteArray): ByteArray {
        val out = block.copyOf()
        var i = BLOCK - 1
        while (i >= BLOCK - 4) {
            out[i] = (out[i] + 1).toByte()
            if (out[i].toInt() != 0) break
            i--
        }
        return out
    }

    /** GHASH over whole blocks: Y = (Y xor X_i) · H in GF(2^128). */
    private fun ghash(h: ByteArray, data: ByteArray): ByteArray {
        val hHi = readLong(h, 0)
        val hLo = readLong(h, 8)
        var yHi = 0L
        var yLo = 0L
        var offset = 0
        while (offset < data.size) {
            val xHi = yHi xor readLong(data, offset)
            val xLo = yLo xor readLong(data, offset + 8)
            // Multiply (xHi, xLo) by (hHi, hLo): right-shift algorithm, R = 0xE1 || 0^120.
            var zHi = 0L
            var zLo = 0L
            var vHi = hHi
            var vLo = hLo
            for (i in 0 until 128) {
                val bit = if (i < 64) (xHi ushr (63 - i)) and 1L else (xLo ushr (127 - i)) and 1L
                if (bit == 1L) {
                    zHi = zHi xor vHi
                    zLo = zLo xor vLo
                }
                val lsb = vLo and 1L
                vLo = (vLo ushr 1) or (vHi shl 63)
                vHi = vHi ushr 1
                if (lsb == 1L) vHi = vHi xor (0xE1L shl 56)
            }
            yHi = zHi
            yLo = zLo
            offset += BLOCK
        }
        return be64(yHi) + be64(yLo)
    }

    private fun readLong(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return v
    }

    private fun be64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }
}
