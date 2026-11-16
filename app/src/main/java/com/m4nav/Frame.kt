package com.m4nav

/** 帧构造：与 nav_final.py 完全一致。AB + 总长 + cmd + payload + CRC8(MAXIM)。 */
object Frame {

    private val TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) {
            c = if (c and 1 != 0) (c shr 1) xor 0x8C else c shr 1
        }
        c and 0xFF
    }

    fun crc8(data: ByteArray): Int {
        var c = 0
        for (b in data) {
            c = TABLE[(c xor (b.toInt() and 0xFF)) and 0xFF]
        }
        return c and 0xFF
    }

    const val ICON_CHAT = 0x08
    const val ICON_CALL = 0x01
    const val CMD_ALERT = 0x17
    const val MAX_TEXT_BYTES = 13

    /** 一块消息 = 一帧 ≤20 字节、片恒 01/01。 */
    fun alert(text: String, icon: Int = ICON_CHAT): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        require(body.size <= MAX_TEXT_BYTES) { "正文 ${body.size} 字节，超过 13" }
        val total = body.size + 7            // AB/len/cmd + icon/piece/total + 正文 + crc
        val out = ByteArray(total)
        out[0] = 0xAB.toByte()
        out[1] = total.toByte()
        out[2] = CMD_ALERT.toByte()
        out[3] = icon.toByte()
        out[4] = 0x01
        out[5] = 0x01
        System.arraycopy(body, 0, out, 6, body.size)
        out[total - 1] = crc8(out.copyOfRange(0, total - 1)).toByte()
        return out
    }

    fun hexs(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }
}
