package dev.etran.bedwars.anvilconverter

import dev.etran.slimetoanvil.convert.ConversionException
import dev.etran.slimetoanvil.convert.ConversionOptions
import dev.etran.slimetoanvil.convert.ConversionResult
import dev.etran.slimetoanvil.convert.SlimeConverter
import org.bukkit.Bukkit
import org.bukkit.configuration.file.FileConfiguration
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExistingWorldPolicy { SKIP, BACKUP, OVERWRITE }

/** Outcome of converting one world. Exactly one of [result] / [skipped] / [error] is set. */
class ConvertOutcome(
    val name: String,
    val result: ConversionResult? = null,
    val skipped: String? = null,
    val error: String? = null,
)

/** Bukkit-free logic of the plugin (apart from reading the server's world container). Safe to run off-thread. */
class ConvertService(private val serverRoot: File, config: FileConfiguration) {
    private val slimeDirs: List<File> = config.getStringList("slime-directories").map { resolve(it) }
    private val outputDir: File = config.getString("output-directory").orEmpty().ifBlank { null }?.let { resolve(it) } ?: serverRoot
    private val arenasDir: File = resolve(config.getString("arenas-directory") ?: "plugins/BedWars1058/Arenas")
    private val policy: ExistingWorldPolicy = runCatching {
        ExistingWorldPolicy.valueOf((config.getString("existing-world") ?: "backup").trim().uppercase(Locale.ROOT))
    }.getOrDefault(ExistingWorldPolicy.BACKUP)
    private val includeEntities: Boolean = config.getBoolean("include-entities", true)
    private val writeLevelDat: Boolean = config.getBoolean("write-level-dat", true)

    private fun resolve(path: String): File = File(path).let { if (it.isAbsolute) it else File(serverRoot, path) }

    fun slimeFile(name: String): File? {
        if (!isSafeName(name)) return null
        return slimeDirs.map { File(it, "$name.slime") }.firstOrNull { it.isFile }
    }

    fun slimeNames(): List<String> = slimeDirs
        .flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".slime") }?.toList().orEmpty() }
        .map { it.name.removeSuffix(".slime") }
        .distinct()
        .sortedBy { it.lowercase(Locale.ROOT) }

    fun arenaNames(): List<String> = arenasDir
        .listFiles { f -> f.isFile && f.name.endsWith(".yml") }?.map { it.name.removeSuffix(".yml") }.orEmpty()
        .sortedBy { it.lowercase(Locale.ROOT) }

    fun targetDir(name: String): File = File(outputDir, name)

    fun isConverted(name: String): Boolean = File(targetDir(name), "region").isDirectory

    /** Names that may become folder names: no separators, no traversal. */
    fun isSafeName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 64 && name != "." && name != ".." && name.all { it.isLetterOrDigit() || it in "_-. " }

    fun inspect(name: String): String {
        val file = slimeFile(name) ?: return "No slime world named '$name'."
        val w = SlimeConverter.inspect(file.toPath())
        return "slime v${w.formatVersion}, ${w.era.name.lowercase()} (dataVersion ${w.dataVersion}), " +
            "${w.chunkCount} chunks, ${w.chunks.sumOf { it.tileEntities.size }} tile entities, " +
            "${w.chunks.sumOf { it.entities.size }} entities"
    }

    /** Blocking; call from an async task. */
    fun convert(name: String, overwriteRequested: Boolean): ConvertOutcome {
        val source = slimeFile(name) ?: return ConvertOutcome(name, error = "no slime world named '$name' found")
        val target = targetDir(name)
        var replace = false

        if (hasWorld(target)) {
            val effective = if (overwriteRequested) ExistingWorldPolicy.OVERWRITE else policy
            when (effective) {
                ExistingWorldPolicy.SKIP -> return ConvertOutcome(name, skipped = "${target.name} already exists (existing-world: skip)")
                ExistingWorldPolicy.BACKUP -> {
                    val backup = File(target.parentFile, "${target.name}.bak-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}")
                    if (!target.renameTo(backup)) return ConvertOutcome(name, error = "could not move the existing world to ${backup.name}")
                }
                ExistingWorldPolicy.OVERWRITE -> replace = true
            }
        }

        return try {
            val options = ConversionOptions(
                overwrite = replace,
                includeEntities = includeEntities,
                writeLevelDat = writeLevelDat,
                levelName = name,
            )
            ConvertOutcome(name, result = SlimeConverter.convert(source.toPath(), target.toPath(), options))
        } catch (e: ConversionException) {
            ConvertOutcome(name, error = e.message ?: "conversion failed")
        } catch (e: Exception) {
            ConvertOutcome(name, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun hasWorld(dir: File): Boolean =
        dir.isDirectory && (File(dir, "region").exists() || File(dir, "level.dat").exists())

    /** Must run on the main thread: a loaded world's files are in use and must never be replaced. */
    fun isLoaded(name: String): Boolean = Bukkit.getWorld(name) != null
}
