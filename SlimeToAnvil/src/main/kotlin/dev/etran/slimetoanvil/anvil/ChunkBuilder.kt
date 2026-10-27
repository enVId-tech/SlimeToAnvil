package dev.etran.slimetoanvil.anvil

import dev.etran.slimetoanvil.nbt.ByteArrayTag
import dev.etran.slimetoanvil.nbt.CompoundTag
import dev.etran.slimetoanvil.nbt.IntArrayTag
import dev.etran.slimetoanvil.nbt.IntTag
import dev.etran.slimetoanvil.nbt.ListTag
import dev.etran.slimetoanvil.nbt.LongArrayTag
import dev.etran.slimetoanvil.nbt.Tag
import dev.etran.slimetoanvil.slime.Era
import dev.etran.slimetoanvil.slime.SlimeChunk
import dev.etran.slimetoanvil.slime.SlimeSection
import dev.etran.slimetoanvil.slime.SlimeWorld

/** The finished NBT for one chunk. [entities] is only set for 1.17+, where entities live in separate region files. */
class BuiltChunk(val chunk: CompoundTag, val entities: CompoundTag?)

object ChunkBuilder {
    private const val DATA_VERSION_1_14 = 1952
    private const val DATA_VERSION_1_15 = 2225
    private const val DATA_VERSION_1_17 = 2724
    private const val DATA_VERSION_1_18 = 2825

    fun build(world: SlimeWorld, chunk: SlimeChunk, includeEntities: Boolean): BuiltChunk = when (world.era) {
        Era.LEGACY -> buildLegacy(world, chunk, includeEntities)
        Era.FLATTENED -> buildFlattened(world, chunk, includeEntities)
        Era.MODERN -> buildModern(world, chunk, includeEntities)
    }

    // ------------------------------------------------------------------------------------ 1.8 - 1.12

    private fun buildLegacy(world: SlimeWorld, c: SlimeChunk, includeEntities: Boolean): BuiltChunk {
        val level = CompoundTag()
        level.putInt("xPos", c.x)
        level.putInt("zPos", c.z)
        level.putLong("LastUpdate", 0L)
        level.putLong("InhabitedTime", 0L)
        level.putByte("V", 1)
        level.putByte("TerrainPopulated", 1)
        level["Biomes"] = ByteArrayTag(ByteArray(256) { i -> (c.biomes?.getOrNull(i) ?: 1).toByte() })
        level["HeightMap"] = IntArrayTag(c.legacyHeightMap ?: IntArray(256))

        // Pre-1.13 servers dereference the light arrays unconditionally, so they are never left out.
        var allLit = true
        val sections = ListTag(Tag.COMPOUND)
        for (s in c.sections.sortedBy { it.y }) {
            if (s.blockLight == null || s.skyLight == null) allLit = false
            sections.add(CompoundTag().apply {
                putByte("Y", s.y)
                this["Blocks"] = ByteArrayTag(s.blocks ?: ByteArray(4096))
                this["Data"] = ByteArrayTag(s.data ?: ByteArray(2048))
                this["BlockLight"] = ByteArrayTag(s.blockLight ?: ByteArray(2048))
                this["SkyLight"] = ByteArrayTag(s.skyLight ?: ByteArray(2048) { 0xFF.toByte() })
            })
        }
        level["Sections"] = sections
        level.putByte("LightPopulated", if (allLit) 1 else 0)
        level["Entities"] = ListTag(Tag.COMPOUND).also { list -> if (includeEntities) c.entities.forEach { list.add(it) } }
        level["TileEntities"] = ListTag(Tag.COMPOUND).also { list -> c.tileEntities.forEach { list.add(it) } }

        val root = CompoundTag()
        if (world.dataVersion > 0) root.putInt("DataVersion", world.dataVersion)
        root["Level"] = level
        return BuiltChunk(root, null)
    }

    // ------------------------------------------------------------------------------------ 1.13 - 1.17

    private fun buildFlattened(world: SlimeWorld, c: SlimeChunk, includeEntities: Boolean): BuiltChunk {
        val dv = world.dataVersion
        val level = CompoundTag()
        level.putInt("xPos", c.x)
        level.putInt("zPos", c.z)
        level.putLong("LastUpdate", 0L)
        level.putLong("InhabitedTime", 0L)
        level.putString("Status", if (dv >= DATA_VERSION_1_14) "full" else "postprocessed")

        val biomes = c.biomes ?: IntArray(256) { 1 }
        level["Biomes"] = IntArrayTag(if (dv >= DATA_VERSION_1_15) to3dBiomes(biomes) else biomes)
        level["Heightmaps"] = c.heightMaps?.copy() ?: CompoundTag()

        var allLit = true
        val sections = ListTag(Tag.COMPOUND)
        for (s in c.sections.sortedBy { it.y }) {
            if (s.blockLight == null || s.skyLight == null) allLit = false
            sections.add(CompoundTag().apply {
                putByte("Y", s.y)
                this["Palette"] = ListTag(Tag.COMPOUND).also { p -> s.palette.orEmpty().forEach { p.add(it) } }
                this["BlockStates"] = LongArrayTag(s.blockStates ?: LongArray(0))
                s.blockLight?.let { this["BlockLight"] = ByteArrayTag(it) }
                s.skyLight?.let { this["SkyLight"] = ByteArrayTag(it) }
            })
        }
        level["Sections"] = sections
        if (dv >= DATA_VERSION_1_14) level.putByte("isLightOn", if (allLit) 1 else 0) // 0 => server recomputes light
        level["TileEntities"] = ListTag(Tag.COMPOUND).also { list -> c.tileEntities.forEach { list.add(it) } }
        level["Structures"] = CompoundTag().apply {
            this["Starts"] = CompoundTag()
            this["References"] = CompoundTag()
        }

        val separateEntities = dv >= DATA_VERSION_1_17
        if (!separateEntities) {
            level["Entities"] = ListTag(Tag.COMPOUND).also { list -> if (includeEntities) c.entities.forEach { list.add(it) } }
        }

        val root = CompoundTag()
        root.putInt("DataVersion", dv)
        root["Level"] = level
        return BuiltChunk(root, if (separateEntities && includeEntities) entityFile(dv, c) else null)
    }

    /** 1.15 switched from 256 2D biomes to 1024 biomes in 4x4x4 cells; replicate the column biome vertically. */
    private fun to3dBiomes(biomes: IntArray): IntArray {
        if (biomes.size >= 1024) return biomes
        if (biomes.size < 256) return IntArray(1024) { biomes.firstOrNull() ?: 1 }
        return IntArray(1024) { i ->
            val cellX = i and 3
            val cellZ = (i shr 2) and 3
            biomes[(cellZ * 4) * 16 + cellX * 4]
        }
    }

    // ------------------------------------------------------------------------------------ 1.18+

    private fun buildModern(world: SlimeWorld, c: SlimeChunk, includeEntities: Boolean): BuiltChunk {
        val dv = world.dataVersion
        val ordered = c.sections.sortedBy { it.y }
        val root = CompoundTag()
        root.putInt("DataVersion", dv)
        root.putInt("xPos", c.x)
        root.putInt("yPos", ordered.firstOrNull()?.y ?: -4)
        root.putInt("zPos", c.z)
        root.putLong("LastUpdate", 0L)
        root.putLong("InhabitedTime", 0L)
        root.putString("Status", "minecraft:full")

        var allLit = true
        val sections = ListTag(Tag.COMPOUND)
        for (s in ordered) {
            if (s.blockLight == null && s.skyLight == null) allLit = false
            sections.add(CompoundTag().apply {
                putByte("Y", s.y)
                this["block_states"] = s.blockStatesTag ?: CompoundTag()
                this["biomes"] = s.biomesTag ?: CompoundTag()
                s.blockLight?.let { this["BlockLight"] = ByteArrayTag(it) }
                s.skyLight?.let { this["SkyLight"] = ByteArrayTag(it) }
            })
        }
        root["sections"] = sections
        root.putByte("isLightOn", if (allLit) 1 else 0)
        root["block_entities"] = ListTag(Tag.COMPOUND).also { list -> c.tileEntities.forEach { list.add(it) } }
        root["Heightmaps"] = c.heightMaps?.copy() ?: CompoundTag()
        root["structures"] = CompoundTag().apply {
            this["starts"] = CompoundTag()
            this["References"] = CompoundTag()
        }
        return BuiltChunk(root, if (includeEntities) entityFile(dv, c) else null)
    }

    private fun entityFile(dataVersion: Int, c: SlimeChunk): CompoundTag? {
        if (c.entities.isEmpty()) return null
        return CompoundTag().apply {
            putInt("DataVersion", dataVersion)
            this["Position"] = IntArrayTag(intArrayOf(c.x, c.z))
            this["Entities"] = ListTag(Tag.COMPOUND).also { list -> c.entities.forEach { list.add(it) } }
        }
    }
}
