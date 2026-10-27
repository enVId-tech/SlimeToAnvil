package dev.etran.slimetoanvil.nbt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream

class NbtFormatException(message: String) : RuntimeException(message)

/** Big-endian NBT reading and writing (uncompressed unless stated otherwise). */
object NbtIO {
    private const val MAX_DEPTH = 512

    // ---------------------------------------------------------------- reading

    /**
     * Reads a root compound from [bytes]. Slime files store vanilla "named root" NBT; some writers emit a
     * nameless root instead, so we fall back to that if the named parse fails.
     */
    fun readRoot(bytes: ByteArray): CompoundTag {
        val named = try {
            readRoot(ByteBuffer.wrap(bytes), named = true)
        } catch (e: RuntimeException) {
            null
        }
        if (named != null) return named
        return readRoot(ByteBuffer.wrap(bytes), named = false)
    }

    /** Reads one root compound from the current position of [buf] and leaves it after the compound. */
    fun readRoot(buf: ByteBuffer, named: Boolean = true): CompoundTag {
        val type = buf.get().toInt()
        if (type != Tag.COMPOUND) throw NbtFormatException("Root tag is not a compound (type $type)")
        if (named) readUtf(buf)
        return readCompound(buf, 0)
    }

    private fun readPayload(buf: ByteBuffer, type: Int, depth: Int): Tag {
        if (depth > MAX_DEPTH) throw NbtFormatException("NBT nesting too deep")
        return when (type) {
            Tag.BYTE -> ByteTag(buf.get())
            Tag.SHORT -> ShortTag(buf.short)
            Tag.INT -> IntTag(buf.int)
            Tag.LONG -> LongTag(buf.long)
            Tag.FLOAT -> FloatTag(buf.float)
            Tag.DOUBLE -> DoubleTag(buf.double)
            Tag.BYTE_ARRAY -> {
                val len = checkLength(buf, buf.int, 1)
                ByteArrayTag(ByteArray(len).also { buf.get(it) })
            }
            Tag.STRING -> StringTag(readUtf(buf))
            Tag.LIST -> {
                val elementType = buf.get().toInt()
                val len = buf.int
                if (len < 0 || (len > 0 && elementType == Tag.END)) throw NbtFormatException("Bad list header")
                if (len > buf.remaining()) throw NbtFormatException("List longer than remaining data")
                val list = ListTag(elementType)
                repeat(len) { list.items.add(readPayload(buf, elementType, depth + 1)) }
                list
            }
            Tag.COMPOUND -> readCompound(buf, depth + 1)
            Tag.INT_ARRAY -> {
                val len = checkLength(buf, buf.int, 4)
                IntArrayTag(IntArray(len) { buf.int })
            }
            Tag.LONG_ARRAY -> {
                val len = checkLength(buf, buf.int, 8)
                LongArrayTag(LongArray(len) { buf.long })
            }
            else -> throw NbtFormatException("Unknown tag type $type")
        }
    }

    private fun readCompound(buf: ByteBuffer, depth: Int): CompoundTag {
        val compound = CompoundTag()
        while (true) {
            val type = buf.get().toInt()
            if (type == Tag.END) return compound
            val name = readUtf(buf)
            compound.entries[name] = readPayload(buf, type, depth + 1)
        }
    }

    private fun checkLength(buf: ByteBuffer, len: Int, elementSize: Int): Int {
        if (len < 0 || len.toLong() * elementSize > buf.remaining()) throw NbtFormatException("Bad array length $len")
        return len
    }

    /** NBT strings are Java "modified UTF-8"; DataInputStream knows that encoding. */
    private fun readUtf(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        if (len > buf.remaining()) throw NbtFormatException("String longer than remaining data")
        val raw = ByteArray(len + 2)
        raw[0] = (len shr 8).toByte()
        raw[1] = len.toByte()
        buf.get(raw, 2, len)
        return DataInputStream(ByteArrayInputStream(raw)).readUTF()
    }

    // ---------------------------------------------------------------- writing

    fun writeRoot(out: DataOutput, name: String, root: CompoundTag) {
        out.writeByte(Tag.COMPOUND)
        out.writeUTF(name)
        writePayload(out, root)
    }

    fun toBytes(root: CompoundTag, name: String = ""): ByteArray {
        val bos = ByteArrayOutputStream(4096)
        DataOutputStream(bos).use { writeRoot(it, name, root) }
        return bos.toByteArray()
    }

    /** zlib (what region files use, compression id 2). */
    fun toZlibBytes(root: CompoundTag, name: String = ""): ByteArray {
        val bos = ByteArrayOutputStream(4096)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            DataOutputStream(DeflaterOutputStream(bos, deflater)).use { writeRoot(it, name, root) }
        } finally {
            deflater.end()
        }
        return bos.toByteArray()
    }

    /** gzip (what level.dat and map_*.dat use). */
    fun toGzipBytes(root: CompoundTag, name: String = ""): ByteArray {
        val bos = ByteArrayOutputStream(1024)
        DataOutputStream(GZIPOutputStream(bos)).use { writeRoot(it, name, root) }
        return bos.toByteArray()
    }

    private fun writePayload(out: DataOutput, tag: Tag) {
        when (tag) {
            is ByteTag -> out.writeByte(tag.value.toInt())
            is ShortTag -> out.writeShort(tag.value.toInt())
            is IntTag -> out.writeInt(tag.value)
            is LongTag -> out.writeLong(tag.value)
            is FloatTag -> out.writeFloat(tag.value)
            is DoubleTag -> out.writeDouble(tag.value)
            is ByteArrayTag -> { out.writeInt(tag.value.size); out.write(tag.value) }
            is StringTag -> out.writeUTF(tag.value)
            is ListTag -> {
                val type = if (tag.items.isEmpty()) Tag.END else tag.elementType
                out.writeByte(type)
                out.writeInt(tag.items.size)
                for (item in tag.items) writePayload(out, item)
            }
            is CompoundTag -> {
                for ((name, value) in tag.entries) {
                    out.writeByte(value.typeId)
                    out.writeUTF(name)
                    writePayload(out, value)
                }
                out.writeByte(Tag.END)
            }
            is IntArrayTag -> { out.writeInt(tag.value.size); for (v in tag.value) out.writeInt(v) }
            is LongArrayTag -> { out.writeInt(tag.value.size); for (v in tag.value) out.writeLong(v) }
        }
    }
}
