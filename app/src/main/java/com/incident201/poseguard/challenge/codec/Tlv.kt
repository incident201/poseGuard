package com.incident201.poseguard.challenge.codec

import java.io.ByteArrayOutputStream

class TlvFormatException(message: String) : Exception(message)

/** Varint/zigzag primitives shared by TLV fields and the pose trace stream. */
class ByteSink(initialCapacity: Int = 256) {
    private val out = ByteArrayOutputStream(initialCapacity)

    val size: Int get() = out.size()

    fun byte(value: Int) = out.write(value and 0xFF)

    fun bytes(value: ByteArray) = out.write(value, 0, value.size)

    fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun zigzag(value: Long) = varint((value shl 1) xor (value shr 63))

    fun fixed32(value: Int) {
        for (shift in 0 until 32 step 8) out.write((value ushr shift) and 0xFF)
    }

    fun fixed64(value: Long) {
        for (shift in 0 until 64 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

class ByteSource(private val data: ByteArray, position: Int = 0, private val end: Int = data.size) {
    var position: Int = position
        private set
    val remaining: Int get() = end - position
    val hasMore: Boolean get() = position < end

    fun byte(): Int {
        if (position >= end) throw TlvFormatException("Unexpected end of data")
        return data[position++].toInt() and 0xFF
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0 || count > remaining) throw TlvFormatException("Invalid length $count")
        return data.copyOfRange(position, position + count).also { position += count }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = byte()
            if (shift == 63 && b and 0xFE != 0) throw TlvFormatException("Varint overflow")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw TlvFormatException("Varint too long")
    }

    fun zigzag(): Long {
        val raw = varint()
        return (raw ushr 1) xor -(raw and 1)
    }

    fun fixed32(): Int {
        var result = 0
        for (shift in 0 until 32 step 8) result = result or (byte() shl shift)
        return result
    }

    fun fixed64(): Long {
        var result = 0L
        for (shift in 0 until 64 step 8) result = result or (byte().toLong() shl shift)
        return result
    }
}

/**
 * Tag-length-value message. Unknown tags are kept but ignored by readers, so newer files
 * stay readable by older builds and the other way round.
 */
class TlvWriter {
    private val sink = ByteSink()

    fun bytes(tag: Int, value: ByteArray) {
        sink.varint(tag.toLong())
        sink.varint(value.size.toLong())
        sink.bytes(value)
    }

    fun long(tag: Int, value: Long) = bytes(tag, ByteSink(10).apply { zigzag(value) }.toByteArray())
    fun int(tag: Int, value: Int) = long(tag, value.toLong())
    fun bool(tag: Int, value: Boolean) = long(tag, if (value) 1L else 0L)
    fun float(tag: Int, value: Float) = bytes(tag, ByteSink(4).apply { fixed32(value.toRawBits()) }.toByteArray())
    fun double(tag: Int, value: Double) = bytes(tag, ByteSink(8).apply { fixed64(value.toRawBits()) }.toByteArray())
    fun string(tag: Int, value: String) = bytes(tag, value.toByteArray(Charsets.UTF_8))
    fun <E : Enum<E>> enum(tag: Int, value: E) = string(tag, value.name)
    fun message(tag: Int, build: TlvWriter.() -> Unit) = bytes(tag, TlvWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = sink.toByteArray()
}

class TlvReader(data: ByteArray) {
    private val fields: Map<Int, List<ByteArray>>

    init {
        val source = ByteSource(data)
        val parsed = LinkedHashMap<Int, MutableList<ByteArray>>()
        while (source.hasMore) {
            val tag = source.varint()
            val length = source.varint()
            if (tag < 0 || tag > Int.MAX_VALUE || length < 0 || length > source.remaining) {
                throw TlvFormatException("Corrupted field")
            }
            parsed.getOrPut(tag.toInt()) { mutableListOf() }.add(source.bytes(length.toInt()))
        }
        fields = parsed
    }

    fun has(tag: Int): Boolean = fields.containsKey(tag)
    fun bytes(tag: Int): ByteArray? = fields[tag]?.last()
    fun repeated(tag: Int): List<ByteArray> = fields[tag].orEmpty()

    fun long(tag: Int): Long? = bytes(tag)?.let { ByteSource(it).zigzag() }
    fun int(tag: Int): Int? = long(tag)?.let {
        if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw TlvFormatException("Int out of range")
        it.toInt()
    }
    fun bool(tag: Int): Boolean? = long(tag)?.let { it != 0L }
    fun float(tag: Int): Float? = bytes(tag)?.let { Float.fromBits(ByteSource(it).fixed32()) }
    fun double(tag: Int): Double? = bytes(tag)?.let { Double.fromBits(ByteSource(it).fixed64()) }
    fun string(tag: Int): String? = bytes(tag)?.toString(Charsets.UTF_8)
    fun message(tag: Int): TlvReader? = bytes(tag)?.let(::TlvReader)

    inline fun <reified E : Enum<E>> enum(tag: Int, default: E): E =
        string(tag)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    fun requireBytes(tag: Int): ByteArray = bytes(tag) ?: throw TlvFormatException("Missing field $tag")
}
