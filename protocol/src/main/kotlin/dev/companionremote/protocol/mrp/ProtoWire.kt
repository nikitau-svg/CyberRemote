package dev.companionremote.protocol.mrp

import java.io.ByteArrayOutputStream

/** Protobuf-style unsigned varints used only by MRP transport framing. */
internal fun encodeVarint(value: Long): ByteArray {
    require(value >= 0) { "varint value must not be negative" }
    val output = ByteArrayOutputStream(10)
    var current = value
    do {
        var byte = (current and 0x7F).toInt()
        current = current ushr 7
        if (current != 0L) byte = byte or 0x80
        output.write(byte)
    } while (current != 0L)
    return output.toByteArray()
}

/** Returns the decoded value and number of consumed bytes. */
internal fun decodeVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
    require(offset in 0..data.size) { "varint offset is outside the buffer" }
    var value = 0L
    var shift = 0
    var index = offset
    while (index < data.size && shift < 64) {
        val byte = data[index].toInt() and 0xFF
        value = value or ((byte and 0x7F).toLong() shl shift)
        index += 1
        if (byte and 0x80 == 0) return value to (index - offset)
        shift += 7
    }
    if (index >= data.size) throw MrpException("truncated varint")
    throw MrpException("varint is too long")
}
