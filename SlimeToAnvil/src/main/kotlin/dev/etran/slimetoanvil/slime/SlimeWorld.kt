package dev.etran.slimetoanvil.slime

import dev.etran.slimetoanvil.nbt.CompoundTag

/**
 * Which block storage layout a world uses. This decides how chunks have to be written back out as Anvil.
 *
 *  - [LEGACY]    1.8 - 1.12: numeric block ids (`Blocks` + `Data`)
 *  - [FLATTENED] 1.13 - 1.17: palette + packed `BlockStates`, chunk data inside a `Level` compound
 *  - [MODERN]    1.18+ (every Slime v10+ file): `block_states` / `biomes` containers per section, no `Level`
 */
enum class Era { LEGACY, FLATTENED, MODERN }

class SlimeSection(
    /** Section Y index (0..15 for pre-1.18 worlds; may be negative for 1.18+). */
    val y: Int,
    val blockLight: ByteArray?,
    val skyLight: ByteArray?,
    // --- LEGACY
    val blocks: ByteArray? = null,
    val data: ByteArray? = null,
    // --- FLATTENED
    val palette: List<CompoundTag>? = null,
    val blockStates: LongArray? = null,
    // --- MODERN
    val blockStatesTag: CompoundTag? = null,
    val biomesTag: CompoundTag? = null,
)

class SlimeChunk(
    val x: Int,
    val z: Int,
    val sections: List<SlimeSection>,
    /** LEGACY only: 256 ints. */
    val legacyHeightMap: IntArray? = null,
    /** FLATTENED / MODERN: the `Heightmaps` compound. */
    val heightMaps: CompoundTag? = null,
    /** LEGACY: 256 values (byte per column). FLATTENED: 256 or 1024 ints. MODERN: stored per section instead. */
    val biomes: IntArray? = null,
    val tileEntities: MutableList<CompoundTag> = ArrayList(),
    val entities: MutableList<CompoundTag> = ArrayList(),
    /** Extra per-chunk data (Slime v12 persistent data container), kept verbatim. */
    val extra: CompoundTag? = null,
)

class SlimeWorld(
    /** Slime file format version (1..12). */
    val formatVersion: Int,
    /**
     * Legacy files (v1..v9): the Slime "world version" byte (1 = 1.8 ... 7 = 1.17).
     * v10+ files: the Minecraft DataVersion.
     */
    val worldVersion: Int,
    val era: Era,
    /** Minecraft DataVersion to stamp on the output (0 = none, as in vanilla 1.8). */
    val dataVersion: Int,
    val chunks: List<SlimeChunk>,
    val extra: CompoundTag,
    val maps: List<CompoundTag>,
) {
    val chunkCount: Int get() = chunks.size
}

class SlimeFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
