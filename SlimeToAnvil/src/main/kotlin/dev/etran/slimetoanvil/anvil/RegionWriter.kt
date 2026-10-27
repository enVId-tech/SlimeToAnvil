package dev.etran.slimetoanvil.anvil

import dev.etran.slimetoanvil.nbt.CompoundTag
import dev.etran.slimetoanvil.nbt.NbtIO
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Builds a single `r.<x>.<z>.mca` file in memory (arenas are small) and writes it on [writeTo]. */
internal class RegionWriter {
    private val offsets = IntArray(1024)
    private val timestamps = IntArray(1024)
    private val body = ByteArrayOutputStream()
    private var nextSector = 2 // sectors 0 and 1 are the header
    private val now = (System.currentTimeMillis() / 1000L).toInt()

    fun addChunk(chunkX: Int, chunkZ: Int, root: CompoundTag) {
        val payload = NbtIO.toZlibBytes(root)
        val length = payload.size + 1
        val total = length + 4
        val sectors = (total + SECTOR - 1) / SECTOR
        if (sectors > 255) throw IllegalStateException("Chunk $chunkX,$chunkZ is too large for a region file ($total bytes)")

        val out = DataOutputStream(body)
        out.writeInt(length)
        out.writeByte(COMPRESSION_ZLIB)
        out.write(payload)
        val padding = sectors * SECTOR - total
        if (padding > 0) out.write(ByteArray(padding))

        val index = (chunkX and 31) + (chunkZ and 31) * 32
        offsets[index] = (nextSector shl 8) or sectors
        timestamps[index] = now
        nextSector += sectors
    }

    fun writeTo(file: Path) {
        Files.createDirectories(file.parent)
        val header = ByteArrayOutputStream(SECTOR * 2)
        DataOutputStream(header).apply {
            for (v in offsets) writeInt(v)
            for (v in timestamps) writeInt(v)
        }
        Files.newOutputStream(file).use { out ->
            header.writeTo(out)
            body.writeTo(out)
        }
    }

    private companion object {
        const val SECTOR = 4096
        const val COMPRESSION_ZLIB = 2
    }
}

/** Collects chunks and writes them into `region/` (and `entities/` for 1.17+) of a world folder. */
class AnvilWorldWriter(private val worldDir: Path) {
    private val regions = HashMap<Long, RegionWriter>()
    private val entityRegions = HashMap<Long, RegionWriter>()

    var chunkCount = 0
        private set

    fun addChunk(chunkX: Int, chunkZ: Int, chunk: CompoundTag, entities: CompoundTag? = null) {
        regionFor(regions, chunkX shr 5, chunkZ shr 5).addChunk(chunkX, chunkZ, chunk)
        if (entities != null) regionFor(entityRegions, chunkX shr 5, chunkZ shr 5).addChunk(chunkX, chunkZ, entities)
        chunkCount++
    }

    val regionCount: Int get() = regions.size

    fun writeAll() {
        write(regions, worldDir.resolve("region"))
        write(entityRegions, worldDir.resolve("entities"))
    }

    private fun write(map: Map<Long, RegionWriter>, dir: Path) {
        for ((key, writer) in map) {
            val rx = (key shr 32).toInt()
            val rz = key.toInt()
            writer.writeTo(dir.resolve("r.$rx.$rz.mca"))
        }
    }

    private fun regionFor(map: MutableMap<Long, RegionWriter>, rx: Int, rz: Int): RegionWriter =
        map.getOrPut((rx.toLong() shl 32) or (rz.toLong() and 0xFFFFFFFFL)) { RegionWriter() }
}
