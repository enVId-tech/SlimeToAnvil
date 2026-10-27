package dev.etran.slimetoanvil.nbt

/** Minimal NBT model. Only what the Slime -> Anvil conversion needs, but complete for the format. */
sealed class Tag {
    abstract val typeId: Int

    companion object {
        const val END = 0
        const val BYTE = 1
        const val SHORT = 2
        const val INT = 3
        const val LONG = 4
        const val FLOAT = 5
        const val DOUBLE = 6
        const val BYTE_ARRAY = 7
        const val STRING = 8
        const val LIST = 9
        const val COMPOUND = 10
        const val INT_ARRAY = 11
        const val LONG_ARRAY = 12
    }
}

class ByteTag(val value: Byte) : Tag() { override val typeId get() = BYTE }
class ShortTag(val value: Short) : Tag() { override val typeId get() = SHORT }
class IntTag(val value: Int) : Tag() { override val typeId get() = INT }
class LongTag(val value: Long) : Tag() { override val typeId get() = LONG }
class FloatTag(val value: Float) : Tag() { override val typeId get() = FLOAT }
class DoubleTag(val value: Double) : Tag() { override val typeId get() = DOUBLE }
class ByteArrayTag(val value: ByteArray) : Tag() { override val typeId get() = BYTE_ARRAY }
class StringTag(val value: String) : Tag() { override val typeId get() = STRING }
class IntArrayTag(val value: IntArray) : Tag() { override val typeId get() = INT_ARRAY }
class LongArrayTag(val value: LongArray) : Tag() { override val typeId get() = LONG_ARRAY }

class ListTag(var elementType: Int = END, val items: MutableList<Tag> = ArrayList()) : Tag() {
    override val typeId get() = LIST

    fun add(tag: Tag) {
        if (elementType == END) elementType = tag.typeId
        items.add(tag)
    }

    val size: Int get() = items.size
    fun compounds(): List<CompoundTag> = items.filterIsInstance<CompoundTag>()
}

class CompoundTag(val entries: LinkedHashMap<String, Tag> = LinkedHashMap()) : Tag() {
    override val typeId get() = COMPOUND

    operator fun get(key: String): Tag? = entries[key]
    operator fun set(key: String, tag: Tag) { entries[key] = tag }
    fun has(key: String): Boolean = entries.containsKey(key)
    fun remove(key: String): Tag? = entries.remove(key)

    fun putByte(key: String, v: Int) = set(key, ByteTag(v.toByte()))
    fun putInt(key: String, v: Int) = set(key, IntTag(v))
    fun putLong(key: String, v: Long) = set(key, LongTag(v))
    fun putString(key: String, v: String) = set(key, StringTag(v))
    fun putBoolean(key: String, v: Boolean) = set(key, ByteTag(if (v) 1 else 0))

    /** Reads any numeric tag as a Long (NBT writers are not consistent about integer widths). */
    private fun number(key: String): Long? = when (val t = entries[key]) {
        is ByteTag -> t.value.toLong()
        is ShortTag -> t.value.toLong()
        is IntTag -> t.value.toLong()
        is LongTag -> t.value
        is FloatTag -> t.value.toLong()
        is DoubleTag -> t.value.toLong()
        else -> null
    }

    fun getInt(key: String, default: Int = 0): Int = number(key)?.toInt() ?: default
    fun getLong(key: String, default: Long = 0L): Long = number(key) ?: default
    fun getString(key: String): String? = (entries[key] as? StringTag)?.value
    fun getList(key: String): ListTag? = entries[key] as? ListTag
    fun getCompound(key: String): CompoundTag? = entries[key] as? CompoundTag

    /** Returns a shallow copy that can be modified without touching the original entries. */
    fun copy(): CompoundTag = CompoundTag(LinkedHashMap(entries))
}
