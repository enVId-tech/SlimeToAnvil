package dev.etran.slimetoanvil.slime

import dev.etran.slimetoanvil.nbt.CompoundTag
import dev.etran.slimetoanvil.nbt.ListTag
import dev.etran.slimetoanvil.nbt.NbtIO
import dev.etran.slimetoanvil.nbt.Tag
import java.nio.ByteBuffer
import java.util.BitSet

data class ReadOptions(
    /** Overrides the "world version" byte of legacy (v1-v9) files. Use if a file was written by an unusual fork. */
    val forceLegacyWorldVersion: Int? = null,
    /** v10+ files do not store the lowest section index. -4 is right for the vanilla overworld (1.18+). */
    val minSectionY: Int = -4,
)

/**
 * Reads the Slime Region Format as written by SlimeWorldManager (SWM), Advanced Slime World Manager (ASWM)
 * and AdvancedSlimePaper (ASP):
 *
 *  - v1..v9   legacy single-blob layout (1.8 .. 1.17), entities / tile entities stored globally
 *  - v10..v12 Minecraft-DataVersion based layout (1.18+); v11+ keeps entities inside each chunk
 */
object SlimeReader {
    const val MAGIC = 0xB10B
    const val MAX_SUPPORTED_VERSION = 12

    private val LEGACY_DATA_VERSIONS = mapOf(1 to 0, 2 to 169, 3 to 922, 4 to 1519, 5 to 1952, 6 to 2586, 7 to 2724)

    fun read(bytes: ByteArray, options: ReadOptions = ReadOptions()): SlimeWorld {
        val buf = ByteBuffer.wrap(bytes)
        try {
            if (buf.remaining() < 3) throw SlimeFormatException("File too small to be a slime world")
            val magic = buf.short.toInt() and 0xFFFF
            if (magic != MAGIC) throw SlimeFormatException("Not a slime file (bad magic 0x${magic.toString(16)})")
            val version = buf.get().toInt() and 0xFF
            return when {
                version in 1..9 -> readLegacy(buf, version, options)
                version in 10..MAX_SUPPORTED_VERSION -> readModern(buf, version, options)
                version > MAX_SUPPORTED_VERSION -> throw SlimeFormatException(
                    "Slime format v$version is newer than this tool supports (max v$MAX_SUPPORTED_VERSION)"
                )
                else -> throw SlimeFormatException("Invalid slime format version $version")
            }
        } catch (e: java.nio.BufferUnderflowException) {
            throw SlimeFormatException("Unexpected end of file (truncated or unsupported variant)", e)
        } catch (e: dev.etran.slimetoanvil.nbt.NbtFormatException) {
            throw SlimeFormatException("Corrupt NBT: ${e.message}", e)
        }
    }

    // =====================================================================================================
    // Legacy: v1 .. v9
    // =====================================================================================================

    private fun readLegacy(buf: ByteBuffer, version: Int, options: ReadOptions): SlimeWorld {
        // v6+ store the world version; older files predate it (v4/v5 were the first 1.13 capable ones).
        val storedWorldVersion = when {
            version >= 6 -> buf.get().toInt() and 0xFF
            version >= 4 -> 4
            else -> 1
        }
        val worldVersion = options.forceLegacyWorldVersion ?: storedWorldVersion

        val dataVersion = LEGACY_DATA_VERSIONS[worldVersion] ?: throw SlimeFormatException(
            "Unsupported slime world version $worldVersion (supported: 1.8 - 1.17). " +
                "Worlds saved on 1.18+ should be Slime v10+; otherwise pass the world version explicitly."
        )
        val era = if (worldVersion >= 4) Era.FLATTENED else Era.LEGACY

        val minX = buf.short.toInt()
        val minZ = buf.short.toInt()
        val width = buf.short.toInt() and 0xFFFF
        val depth = buf.short.toInt() and 0xFFFF
        if (width == 0 || depth == 0) {
            throw SlimeFormatException("World has no chunks ($width x $depth)")
        }
        val bitmask = ByteArray((width.toLong() * depth + 7).div(8).toInt())
        buf.get(bitmask)
        val chunkBits = BitSet.valueOf(bitmask)

        val chunkBlock = Zstd.readBlock(buf)
        val tileBlock = Zstd.readBlock(buf)
        val entityBlock = if (version >= 3 && buf.get().toInt() != 0) Zstd.readBlock(buf) else null
        val extraBlock = if (version >= 2) Zstd.readBlock(buf) else null
        val mapBlock = if (version >= 7) Zstd.readBlock(buf) else null

        val chunkBuf = ByteBuffer.wrap(chunkBlock)
        val chunks = ArrayList<SlimeChunk>()
        for (z in 0 until depth) {
            for (x in 0 until width) {
                if (!chunkBits.get(z * width + x)) continue
                chunks.add(readLegacyChunk(chunkBuf, version, worldVersion, minX + x, minZ + z))
            }
        }

        distribute(
            chunks,
            tiles = listOfCompounds(NbtIO.readRoot(tileBlock), "tiles", "tileEntities"),
            entities = entityBlock?.let { listOfCompounds(NbtIO.readRoot(it), "entities") } ?: emptyList(),
        )
        return SlimeWorld(
            formatVersion = version,
            worldVersion = worldVersion,
            era = era,
            dataVersion = dataVersion,
            chunks = chunks,
            extra = extraBlock?.let { NbtIO.readRoot(it) } ?: CompoundTag(),
            maps = mapBlock?.let { listOfCompounds(NbtIO.readRoot(it), "maps") } ?: emptyList(),
        )
    }

    private fun readLegacyChunk(buf: ByteBuffer, version: Int, worldVersion: Int, x: Int, z: Int): SlimeChunk {
        var legacyHeightMap: IntArray? = null
        var heightMaps: CompoundTag? = null
        val biomes: IntArray

        if (worldVersion < 4) {
            legacyHeightMap = IntArray(256) { buf.int }
            // v8 (only) prefixed the biome array with its length even for legacy worlds; v9 fixed that.
            val length = if (version == 8) buf.int else 256
            if (length < 0 || length > buf.remaining()) throw SlimeFormatException("Bad biome array length $length")
            val raw = ByteArray(length).also { buf.get(it) }
            biomes = IntArray(raw.size) { raw[it].toInt() and 0xFF }
        } else {
            val hmLength = buf.int
            if (hmLength < 0 || hmLength > buf.remaining()) throw SlimeFormatException("Bad heightmap length $hmLength")
            heightMaps = NbtIO.readRoot(ByteArray(hmLength).also { buf.get(it) })
            val length = if (version >= 8) buf.int else 256
            if (length < 0 || length.toLong() * 4 > buf.remaining()) throw SlimeFormatException("Bad biome array length $length")
            biomes = IntArray(length) { buf.int }
        }

        val sectionBits = BitSet.valueOf(ByteArray(2).also { buf.get(it) })
        val sections = ArrayList<SlimeSection>()
        for (y in 0 until 16) {
            if (!sectionBits.get(y)) continue
            sections.add(
                if (worldVersion < 4) readLegacySection(buf, version, y) else readFlattenedSection(buf, version, y)
            )
        }
        return SlimeChunk(x, z, sections, legacyHeightMap = legacyHeightMap, heightMaps = heightMaps, biomes = biomes)
    }

    private fun readOptionalLight(buf: ByteBuffer, version: Int): ByteArray? =
        if (version < 5 || buf.get().toInt() != 0) ByteArray(2048).also { buf.get(it) } else null

    private fun readLegacySection(buf: ByteBuffer, version: Int, y: Int): SlimeSection {
        val blockLight = readOptionalLight(buf, version)
        val blocks = ByteArray(4096).also { buf.get(it) }
        val data = ByteArray(2048).also { buf.get(it) }
        val skyLight = readOptionalLight(buf, version)
        if (version < 4) { // "HypixelBlocks3": an opaque extension we do not need
            val skip = buf.short.toInt() and 0xFFFF
            buf.position(buf.position() + skip)
        }
        return SlimeSection(y, blockLight, skyLight, blocks = blocks, data = data)
    }

    private fun readFlattenedSection(buf: ByteBuffer, version: Int, y: Int): SlimeSection {
        val blockLight = readOptionalLight(buf, version)
        val paletteSize = buf.int
        if (paletteSize < 0 || paletteSize > 65536) throw SlimeFormatException("Bad palette size $paletteSize")
        val palette = List(paletteSize) { NbtIO.readRoot(buf) }
        val stateCount = buf.int
        if (stateCount < 0 || stateCount.toLong() * 8 > buf.remaining()) throw SlimeFormatException("Bad block state length $stateCount")
        val states = LongArray(stateCount) { buf.long }
        val skyLight = readOptionalLight(buf, version)
        return SlimeSection(y, blockLight, skyLight, palette = palette, blockStates = states)
    }

    // =====================================================================================================
    // Modern: v10 .. v12
    // =====================================================================================================

    private fun readModern(buf: ByteBuffer, version: Int, options: ReadOptions): SlimeWorld {
        val dataVersion = buf.int
        if (dataVersion < 1000) throw SlimeFormatException("Implausible DataVersion $dataVersion")
        val chunkBlock = Zstd.readBlock(buf)
        val minSection = if (dataVersion >= 2825) options.minSectionY else 0

        val chunks = parseModernChunks(chunkBlock, version, minSection)
        val tail = buf.duplicate().order(buf.order())
        var tiles: List<CompoundTag> = emptyList()
        var entities: List<CompoundTag> = emptyList()
        val extra: CompoundTag
        var maps: List<CompoundTag> = emptyList()

        if (version >= 11) {
            extra = if (tail.hasRemaining()) NbtIO.readRoot(Zstd.readBlock(tail)) else CompoundTag()
            if (tail.hasRemaining()) maps = runCatching { listOfCompounds(NbtIO.readRoot(Zstd.readBlock(tail)), "maps") }.getOrDefault(emptyList())
        } else {
            // v10 keeps global tile entity / entity lists after the chunks. Different forks wrote an optional
            // "has entities" flag, so we try both layouts and keep whichever consumes the file exactly.
            val start = tail.position()
            var result: V10Tail? = null
            var failure: Exception? = null
            for (withFlag in booleanArrayOf(false, true)) {
                tail.position(start)
                try {
                    result = readV10Tail(tail, withFlag)
                    break
                } catch (e: Exception) {
                    failure = e
                }
            }
            val r = result ?: throw SlimeFormatException("Unreadable v10 trailer: ${failure?.message}", failure)
            tiles = r.tiles; entities = r.entities; extra = r.extra; maps = r.maps
        }

        if (tiles.isNotEmpty() || entities.isNotEmpty()) distribute(chunks, tiles, entities)
        return SlimeWorld(version, dataVersion, Era.MODERN, dataVersion, chunks, extra, maps)
    }

    private class V10Tail(val tiles: List<CompoundTag>, val entities: List<CompoundTag>, val extra: CompoundTag, val maps: List<CompoundTag>)

    private fun readV10Tail(buf: ByteBuffer, withFlag: Boolean): V10Tail {
        val tiles = listOfCompounds(NbtIO.readRoot(Zstd.readBlock(buf)), "tiles", "tileEntities", "block_entities")
        val entities = if (!withFlag || buf.get().toInt() != 0) {
            listOfCompounds(NbtIO.readRoot(Zstd.readBlock(buf)), "entities")
        } else emptyList()
        val extra = NbtIO.readRoot(Zstd.readBlock(buf))
        val maps = if (buf.hasRemaining()) listOfCompounds(NbtIO.readRoot(Zstd.readBlock(buf)), "maps") else emptyList()
        if (buf.hasRemaining()) throw SlimeFormatException("Trailing data after v10 layout")
        return V10Tail(tiles, entities, extra, maps)
    }

    private fun parseModernChunks(block: ByteArray, version: Int, minSection: Int): MutableList<SlimeChunk> {
        // Newer writers prefix the array with a chunk count; be tolerant and also accept "read until the end".
        var firstError: Exception? = null
        for (withCount in booleanArrayOf(true, false)) {
            try {
                val buf = ByteBuffer.wrap(block)
                val count = if (withCount) buf.int else Int.MAX_VALUE
                if (count < 0) continue
                val chunks = ArrayList<SlimeChunk>()
                while (buf.hasRemaining() && chunks.size < count) chunks.add(readModernChunk(buf, version, minSection))
                if (withCount && chunks.size != count) continue
                if (buf.hasRemaining()) continue
                return chunks
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        throw SlimeFormatException("Could not parse v$version chunk data: ${firstError?.message}", firstError)
    }

    private fun readModernChunk(buf: ByteBuffer, version: Int, minSection: Int): SlimeChunk {
        val x = buf.int
        val z = buf.int
        val sectionCount = buf.int
        if (sectionCount < 0 || sectionCount > 4096) throw SlimeFormatException("Bad section count $sectionCount")

        val sections = ArrayList<SlimeSection>(sectionCount)
        for (i in 0 until sectionCount) {
            val sky = if (buf.get().toInt() != 0) ByteArray(2048).also { buf.get(it) } else null
            val light = if (buf.get().toInt() != 0) ByteArray(2048).also { buf.get(it) } else null
            val blockStates = readSizedNbt(buf)
            val biomes = readSizedNbt(buf)
            sections.add(SlimeSection(minSection + i, light, sky, blockStatesTag = blockStates, biomesTag = biomes))
        }
        val heightMaps = readSizedNbt(buf)

        val chunk = SlimeChunk(x, z, sections, heightMaps = heightMaps)
        if (version >= 11) {
            chunk.tileEntities.addAll(listOfCompounds(readNbtBlob(buf), "tileEntities", "tiles", "block_entities"))
            chunk.entities.addAll(listOfCompounds(readNbtBlob(buf), "entities"))
        }
        return if (version >= 12) {
            val pdc = if (buf.hasRemaining()) readSizedNbt(buf) else null
            SlimeChunk(x, z, sections, heightMaps = heightMaps, tileEntities = chunk.tileEntities, entities = chunk.entities, extra = pdc)
        } else chunk
    }

    /** `int length, NBT[length]` */
    private fun readSizedNbt(buf: ByteBuffer): CompoundTag {
        val length = buf.int
        if (length < 0 || length > buf.remaining()) throw SlimeFormatException("Bad NBT block length $length")
        if (length == 0) return CompoundTag()
        return NbtIO.readRoot(ByteArray(length).also { buf.get(it) })
    }

    /**
     * Entity blocks are `int length, NBT` in some writers and `int compressed, int uncompressed, zstd` in others.
     * A raw named-root compound always starts with the TAG_Compound id (10); a size field never does.
     */
    private fun readNbtBlob(buf: ByteBuffer): CompoundTag {
        val length = buf.int
        if (length < 0 || length > buf.remaining()) throw SlimeFormatException("Bad NBT block length $length")
        if (length == 0) return CompoundTag()
        val raw = buf.get(buf.position()).toInt() == Tag.COMPOUND
        return if (raw) {
            NbtIO.readRoot(ByteArray(length).also { buf.get(it) })
        } else {
            val uncompressed = buf.int
            val compressed = ByteArray(length).also { buf.get(it) }
            NbtIO.readRoot(Zstd.decompress(compressed, uncompressed))
        }
    }

    // =====================================================================================================
    // helpers
    // =====================================================================================================

    private fun listOfCompounds(root: CompoundTag, vararg keys: String): List<CompoundTag> {
        for (key in keys) root.getList(key)?.let { return it.compounds() }
        // fall back to the first list that exists at all (some forks use other key names)
        return root.entries.values.filterIsInstance<ListTag>().firstOrNull()?.compounds() ?: emptyList()
    }

    /** Places legacy global tile entities / entities into the chunk they physically are in. */
    private fun distribute(chunks: List<SlimeChunk>, tiles: List<CompoundTag>, entities: List<CompoundTag>) {
        val byPos = HashMap<Long, SlimeChunk>(chunks.size * 2)
        for (c in chunks) byPos[key(c.x, c.z)] = c
        for (tile in tiles) {
            val chunk = byPos[key(Math.floorDiv(tile.getInt("x"), 16), Math.floorDiv(tile.getInt("z"), 16))]
            chunk?.tileEntities?.add(tile)
        }
        for (entity in entities) {
            val pos = entity.getList("Pos")?.items ?: continue
            val px = (pos.getOrNull(0) as? dev.etran.slimetoanvil.nbt.DoubleTag)?.value ?: continue
            val pz = (pos.getOrNull(2) as? dev.etran.slimetoanvil.nbt.DoubleTag)?.value ?: continue
            val chunk = byPos[key(Math.floorDiv(Math.floor(px).toInt(), 16), Math.floorDiv(Math.floor(pz).toInt(), 16))]
            chunk?.entities?.add(entity)
        }
    }

    private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
}
