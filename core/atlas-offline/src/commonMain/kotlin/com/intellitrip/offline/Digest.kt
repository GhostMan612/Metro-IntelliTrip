package com.intellitrip.offline

// Pure-Kotlin SHA-256 + Base64 for commonMain.
//
// The JVM module previously used the platform MessageDigest and Base64 APIs.
// Neither exists on wasmJs, so the basemap store's
// content-addressed storage names and Base64 manifest fields need portable
// implementations. Both are deliberately dependency-free and kept in
// commonMain so the storage names are identical on every target (already
// installed JVM packages keep resolving).

private val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun base64Encode(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val rem = bytes.size - i
        val b1 = if (rem > 1) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (rem > 2) bytes[i + 2].toInt() and 0xFF else 0
        out.append(BASE64_ALPHABET[b0 ushr 2])
        out.append(BASE64_ALPHABET[((b0 shl 4) or (b1 ushr 4)) and 0x3F])
        if (rem > 1) out.append(BASE64_ALPHABET[((b1 shl 2) or (b2 ushr 6)) and 0x3F]) else out.append('=')
        if (rem > 2) out.append(BASE64_ALPHABET[b2 and 0x3F]) else out.append('=')
        i += 3
    }
    return out.toString()
}

internal fun base64Decode(text: String): ByteArray {
    val clean = text.trim()
    if (clean.isEmpty()) return ByteArray(0)
    val out = mutableListOf<Byte>()
    var acc = 0
    var nbits = 0
    for (ch in clean) {
        if (ch == '=') break
        val v = BASE64_ALPHABET.indexOf(ch)
        require(v >= 0) { "invalid Base64 character: $ch" }
        acc = (acc shl 6) or v
        nbits += 6
        if (nbits >= 8) {
            nbits -= 8
            out.add(((acc ushr nbits) and 0xFF).toByte())
        }
    }
    return out.toByteArray()
}

private val SHA256_K = intArrayOf(
    0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
    0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
    0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
    0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
    0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
    0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
    0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
    0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)

private fun sha256(bytes: ByteArray): ByteArray {
    var h0 = 0x6a09e667.toInt()
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372.toInt()
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f.toInt()
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab.toInt()
    var h7 = 0x5be0cd19.toInt()

    // Padding: append 0x80, zeros to 56 mod 64, then 64-bit big-endian bit length.
    val bitLen = bytes.size.toLong() * 8L
    val zeroPad = (56 - (bytes.size + 1) % 64 + 64) % 64
    val total = bytes.size + 1 + zeroPad + 8
    val msg = ByteArray(total)
    bytes.copyInto(msg)
    msg[bytes.size] = 0x80.toByte()
    for (i in 0 until 8) {
        msg[total - 1 - i] = ((bitLen ushr (i * 8)) and 0xFF).toByte()
    }

    val w = IntArray(64)
    var chunk = 0
    while (chunk < total) {
        for (i in 0 until 16) {
            val o = chunk + i * 4
            w[i] = ((msg[o].toInt() and 0xFF) shl 24) or
                ((msg[o + 1].toInt() and 0xFF) shl 16) or
                ((msg[o + 2].toInt() and 0xFF) shl 8) or
                (msg[o + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val s0 = (rotr(w[i - 15], 7) xor rotr(w[i - 15], 18)) xor (w[i - 15] ushr 3)
            val s1 = (rotr(w[i - 2], 17) xor rotr(w[i - 2], 19)) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h0; var b = h1; var c = h2; var d = h3
        var e = h4; var f = h5; var g = h6; var h = h7
        for (i in 0 until 64) {
            val s1 = (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25))
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + ch + SHA256_K[i] + w[i]
            val s0 = (rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22))
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            h = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
        chunk += 64
    }

    val out = ByteArray(32)
    listOf(h0, h1, h2, h3, h4, h5, h6, h7).forEachIndexed { idx, v ->
        out[idx * 4] = ((v ushr 24) and 0xFF).toByte()
        out[idx * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
        out[idx * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
        out[idx * 4 + 3] = (v and 0xFF).toByte()
    }
    return out
}

private fun rotr(value: Int, n: Int): Int = (value ushr n) or (value shl (32 - n))

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = sha256(bytes)
    val sb = StringBuilder(64)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        sb.append("0123456789abcdef"[v ushr 4])
        sb.append("0123456789abcdef"[v and 0x0F])
    }
    return sb.toString()
}
