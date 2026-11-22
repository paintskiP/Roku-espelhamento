package app.rokumirror

import java.io.ByteArrayOutputStream

/** Muxer MPEG-TS mínimo: só vídeo H.264 (Annex B). */
class Ts {
    private var ccV = 0
    private var ccPat = 0
    private var ccPmt = 0

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun crc(d: ByteArray): Int {
        var c = -1
        for (x in d) {
            c = c xor ((x.toInt() and 0xFF) shl 24)
            repeat(8) { c = if (c < 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
        }
        return c
    }

    private fun psi(pid: Int, cc: Int, sec: ByteArray): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = (0x40 or (pid shr 8)).toByte()
        p[2] = pid.toByte()
        p[3] = (0x10 or cc).toByte()
        p[4] = 0
        sec.copyInto(p, 5)
        val c = crc(sec)
        for (i in 0..3) p[5 + sec.size + i] = (c ushr (24 - 8 * i)).toByte()
        return p
    }

    /** PAT + PMT, no início de cada segmento. */
    fun header(): ByteArray {
        val pat = psi(0, ccPat++ and 15, b(0x00, 0xB0, 0x0D, 0x00, 0x01, 0xC1, 0x00, 0x00, 0x00, 0x01, 0xF0, 0x00))
        val pmt = psi(0x1000, ccPmt++ and 15,
            b(0x02, 0xB0, 0x12, 0x00, 0x01, 0xC1, 0x00, 0x00, 0xE1, 0x00, 0xF0, 0x00, 0x1B, 0xE1, 0x00, 0xF0, 0x00))
        return pat + pmt
    }

    /** Um quadro H.264 (pts em ticks de 90 kHz) vira pacotes TS no PID 0x100. */
    fun frame(es: ByteArray, pts: Long, key: Boolean): ByteArray {
        val h = b(0, 0, 1, 0xE0, 0, 0, 0x80, 0x80, 5,
            0x21 or (((pts shr 30) and 7).toInt() shl 1), ((pts shr 22) and 0xFF).toInt(),
            0x01 or (((pts shr 15) and 0x7F).toInt() shl 1), ((pts shr 7) and 0xFF).toInt(),
            0x01 or ((pts and 0x7F).toInt() shl 1))
        val data = h + es
        val q = pts - 9000
        val pcr = b(if (key) 0x50 else 0x10,
            ((q shr 25) and 0xFF).toInt(), ((q shr 17) and 0xFF).toInt(),
            ((q shr 9) and 0xFF).toInt(), ((q shr 1) and 0xFF).toInt(),
            (((q and 1).toInt()) shl 7) or 0x7E, 0)
        val out = ByteArrayOutputStream()
        var off = 0
        var first = true
        while (off < data.size) {
            val p = ByteArray(188) { 0xFF.toByte() }
            var ad: ByteArray? = if (first) pcr else null
            val space = 184 - (if (ad != null) ad.size + 1 else 0)
            val n = minOf(data.size - off, space)
            if (n < space) {
                val s = space - n
                ad = if (ad == null) {
                    val z = ByteArray(s - 1) { 0xFF.toByte() }
                    if (z.isNotEmpty()) z[0] = 0
                    z
                } else ad + ByteArray(s) { 0xFF.toByte() }
            }
            p[0] = 0x47
            p[1] = ((if (first) 0x40 else 0) or 0x01).toByte()
            p[2] = 0x00
            p[3] = (((if (ad != null) 3 else 1) shl 4) or (ccV++ and 15)).toByte()
            var o = 4
            if (ad != null) {
                p[o++] = ad.size.toByte()
                ad.copyInto(p, o)
                o += ad.size
            }
            data.copyInto(p, o, off, off + n)
            off += n
            first = false
            out.write(p)
        }
        return out.toByteArray()
    }
}
