package dev.etran.slimetoanvil.anvil

import dev.etran.slimetoanvil.nbt.CompoundTag
import dev.etran.slimetoanvil.nbt.IntTag
import dev.etran.slimetoanvil.nbt.NbtIO
import dev.etran.slimetoanvil.nbt.ShortTag
import dev.etran.slimetoanvil.slime.Era
import dev.etran.slimetoanvil.slime.SlimeWorld
import java.nio.file.Files
import java.nio.file.Path

/**
 * Auxiliary world files. Only the pre-1.13 era gets a `level.dat`: its format is small and stable, while
 * newer `level.dat` layouts change nearly every release. Every server version creates a correct one by itself
 * when the file is missing, and keeps our region files, so skipping it is the safe choice there.
 */
object WorldFiles {
    fun writeLevelDat(dir: Path, world: SlimeWorld, levelName: String): Boolean {
        if (world.era != Era.LEGACY) return false

        val minX = world.chunks.minOfOrNull { it.x } ?: 0
        val maxX = world.chunks.maxOfOrNull { it.x } ?: 0
        val minZ = world.chunks.minOfOrNull { it.z } ?: 0
        val maxZ = world.chunks.maxOfOrNull { it.z } ?: 0

        val data = CompoundTag().apply {
            putInt("version", 19133)
            if (world.dataVersion > 0) putInt("DataVersion", world.dataVersion)
            putByte("initialized", 1)
            putString("LevelName", levelName)
            putString("generatorName", "flat")
            putInt("generatorVersion", 0)
            putString("generatorOptions", "3;0;1") // one layer of air, plains: new chunks stay empty
            putLong("RandomSeed", 0L)
            putByte("MapFeatures", 0)
            putLong("LastPlayed", System.currentTimeMillis())
            putLong("Time", 0L)
            putLong("DayTime", 6000L)
            putLong("SizeOnDisk", 0L)
            putInt("GameType", 0)
            putInt("SpawnX", ((minX + maxX + 1) * 16) / 2)
            putInt("SpawnY", 64)
            putInt("SpawnZ", ((minZ + maxZ + 1) * 16) / 2)
            putBoolean("raining", false)
            putInt("rainTime", 0)
            putBoolean("thundering", false)
            putInt("thunderTime", 0)
            putBoolean("allowCommands", false)
            putBoolean("hardcore", false)
            putInt("clearWeatherTime", 0)
        }
        val root = CompoundTag().apply { this["Data"] = data }
        Files.write(dir.resolve("level.dat"), NbtIO.toGzipBytes(root))
        return true
    }

    /** Writes `data/map_<id>.dat` for worlds that carried maps. Returns how many were written. */
    fun writeMaps(dir: Path, world: SlimeWorld): Int {
        if (world.maps.isEmpty() || world.era == Era.MODERN) return 0
        val dataDir = dir.resolve("data")
        Files.createDirectories(dataDir)
        var highest = -1
        var written = 0
        for (map in world.maps) {
            val id = map.getInt("id", -1)
            if (id < 0) continue
            val content = map.copy().also { it.remove("id") }
            val root = CompoundTag().apply { this["data"] = content }
            Files.write(dataDir.resolve("map_$id.dat"), NbtIO.toGzipBytes(root))
            highest = maxOf(highest, id)
            written++
        }
        if (written > 0 && world.era == Era.LEGACY) {
            val counts = CompoundTag().apply { this["map"] = ShortTag(highest.toShort()) }
            Files.write(dataDir.resolve("idcounts.dat"), NbtIO.toGzipBytes(counts))
        }
        return written
    }

    /** Keeps the per-world custom data around for inspection (properties, plugin data). */
    fun writeSlimeExtra(dir: Path, world: SlimeWorld) {
        if (world.extra.entries.isEmpty()) return
        Files.write(dir.resolve("slime_extra.nbt"), NbtIO.toGzipBytes(world.extra))
    }
}
