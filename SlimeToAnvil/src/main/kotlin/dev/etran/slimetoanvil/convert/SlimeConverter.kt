package dev.etran.slimetoanvil.convert

import dev.etran.slimetoanvil.anvil.AnvilWorldWriter
import dev.etran.slimetoanvil.anvil.ChunkBuilder
import dev.etran.slimetoanvil.anvil.WorldFiles
import dev.etran.slimetoanvil.slime.Era
import dev.etran.slimetoanvil.slime.ReadOptions
import dev.etran.slimetoanvil.slime.SlimeReader
import dev.etran.slimetoanvil.slime.SlimeWorld
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class ConversionOptions(
    /** Replace an existing world in the output directory instead of refusing. */
    val overwrite: Boolean = false,
    /** Copy entities (armor stands, villagers, ...). BedWars shops are usually villagers, so this defaults to on. */
    val includeEntities: Boolean = true,
    /** Write a `level.dat` where the format is stable enough to do so (pre-1.13). */
    val writeLevelDat: Boolean = true,
    /** Keep the Slime per-world "extra" data next to the world as `slime_extra.nbt`. */
    val keepSlimeExtra: Boolean = false,
    val read: ReadOptions = ReadOptions(),
    val levelName: String? = null,
)

class ConversionResult(
    val outputDir: Path,
    val formatVersion: Int,
    val era: Era,
    val dataVersion: Int,
    val chunks: Int,
    val regions: Int,
    val tileEntities: Int,
    val entities: Int,
    val levelDatWritten: Boolean,
    val warnings: List<String>,
    val elapsedMillis: Long,
)

class ConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Converts a `.slime` file into an Anvil world folder. Thread-safe and free of any Minecraft/Bukkit types. */
object SlimeConverter {

    @Throws(ConversionException::class)
    fun convert(input: Path, outputDir: Path, options: ConversionOptions = ConversionOptions()): ConversionResult {
        val bytes = try {
            Files.readAllBytes(input)
        } catch (e: IOException) {
            throw ConversionException("Cannot read $input: ${e.message}", e)
        }
        return convert(bytes, outputDir, options.copy(levelName = options.levelName ?: baseName(input)))
    }

    @Throws(ConversionException::class)
    fun convert(slimeBytes: ByteArray, outputDir: Path, options: ConversionOptions = ConversionOptions()): ConversionResult {
        val started = System.nanoTime()
        val world = try {
            SlimeReader.read(slimeBytes, options.read)
        } catch (e: RuntimeException) {
            throw ConversionException(e.message ?: "Invalid slime file", e)
        }
        if (world.chunks.isEmpty()) throw ConversionException("The slime world contains no chunks")

        try {
            prepareOutput(outputDir, options.overwrite)
            val warnings = ArrayList<String>()
            val writer = AnvilWorldWriter(outputDir)
            var tiles = 0
            var entities = 0
            for (chunk in world.chunks) {
                val built = ChunkBuilder.build(world, chunk, options.includeEntities)
                writer.addChunk(chunk.x, chunk.z, built.chunk, built.entities)
                tiles += chunk.tileEntities.size
                if (options.includeEntities) entities += chunk.entities.size
            }
            writer.writeAll()

            var levelDat = false
            if (options.writeLevelDat) levelDat = WorldFiles.writeLevelDat(outputDir, world, options.levelName ?: "world")
            if (!levelDat && options.writeLevelDat) {
                warnings.add("No level.dat written for ${world.era.name.lowercase()} worlds; the server creates one on first load.")
            }
            val maps = WorldFiles.writeMaps(outputDir, world)
            if (maps > 0) warnings.add("Copied $maps map(s).")
            if (options.keepSlimeExtra) WorldFiles.writeSlimeExtra(outputDir, world)
            if (!options.includeEntities && world.chunks.any { it.entities.isNotEmpty() }) {
                warnings.add("Entities were present in the slime world but skipped (entities disabled).")
            }

            return ConversionResult(
                outputDir = outputDir,
                formatVersion = world.formatVersion,
                era = world.era,
                dataVersion = world.dataVersion,
                chunks = writer.chunkCount,
                regions = writer.regionCount,
                tileEntities = tiles,
                entities = entities,
                levelDatWritten = levelDat,
                warnings = warnings,
                elapsedMillis = (System.nanoTime() - started) / 1_000_000,
            )
        } catch (e: IOException) {
            throw ConversionException("Could not write ${outputDir}: ${e.message}", e)
        }
    }

    /** Reads only the header/chunks, e.g. for `info` style commands. */
    fun inspect(input: Path, read: ReadOptions = ReadOptions()): SlimeWorld = try {
        SlimeReader.read(Files.readAllBytes(input), read)
    } catch (e: IOException) {
        throw ConversionException("Cannot read $input: ${e.message}", e)
    } catch (e: RuntimeException) {
        throw ConversionException(e.message ?: "Invalid slime file", e)
    }

    fun baseName(path: Path): String = path.fileName.toString().removeSuffix(".slime")

    private val OWNED = listOf("region", "entities", "poi", "data", "level.dat", "slime_extra.nbt")

    private fun prepareOutput(dir: Path, overwrite: Boolean) {
        val existing = OWNED.map { dir.resolve(it) }.filter { Files.exists(it) }
        if (existing.isNotEmpty() && !overwrite) {
            throw ConversionException("${dir} already contains a world (use overwrite to replace it)")
        }
        // Only remove what a conversion itself produces, never the rest of the folder.
        existing.forEach { deleteRecursively(it) }
        Files.createDirectories(dir)
    }

    private fun deleteRecursively(path: Path) {
        if (Files.isDirectory(path)) Files.list(path).use { s -> s.forEach { deleteRecursively(it) } }
        Files.deleteIfExists(path)
    }
}
