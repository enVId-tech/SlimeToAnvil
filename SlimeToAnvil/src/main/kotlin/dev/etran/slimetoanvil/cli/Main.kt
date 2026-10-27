package dev.etran.slimetoanvil.cli

import dev.etran.slimetoanvil.convert.ConversionException
import dev.etran.slimetoanvil.convert.ConversionOptions
import dev.etran.slimetoanvil.convert.SlimeConverter
import dev.etran.slimetoanvil.slime.ReadOptions
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

private const val VERSION = "1.0.0"

private val USAGE = """
SlimeToAnvil $VERSION - convert Slime Region Format worlds (SWM / ASWM / ASP) to Anvil worlds

Usage:
  SlimeToAnvil [<input.slime | directory>] [options]

  Without an input, every .slime file in the current folder is converted into a folder of the same
  name next to it (so just run it, or double-click it, inside your slime_worlds folder).

Options:
  -o, --output <dir>        Output folder. For a single file this is the world folder to create
                            (default: a folder named after the file, next to it). For a directory input it is
                            the parent that receives one folder per .slime file (default: the input directory).
  -f, --force               Replace an existing converted world in the output folder
      --no-entities         Do not copy entities
      --no-level-dat        Do not write level.dat (only written for 1.8 - 1.12 worlds anyway)
      --keep-extra          Keep the slime "extra" data as slime_extra.nbt
      --world-version <n>   Override the slime world version of legacy (v1-v9) files:
                            1=1.8  2=1.9  3=1.11/1.12  4=1.13  5=1.14  6=1.16  7=1.17
      --min-section <y>     Lowest section index for v10+ files (default -4, the 1.18+ overworld)
      --info                Only print information about the slime file(s)
  -h, --help                Show this help
      --version             Show the version

Examples:
  SlimeToAnvil lobby.slime
  SlimeToAnvil slime_worlds/ -o arenas/ --force
""".trimIndent()

private class UsageError(message: String) : Exception(message)

fun main(args: Array<String>) {
    val code = try {
        run(args)
    } catch (e: UsageError) {
        System.err.println("Error: ${e.message}\n")
        System.err.println(USAGE)
        2
    }
    // Double-clicking the .exe on Windows would close the console before the output can be read.
    if (args.isEmpty() && System.getProperty("os.name").lowercase().contains("win") && System.console() != null) {
        println("\nPress Enter to exit...")
        readLine()
    }
    exitProcess(code)
}

private fun run(args: Array<String>): Int {
    var input: String? = null
    var output: String? = null
    var force = false
    var entities = true
    var levelDat = true
    var keepExtra = false
    var infoOnly = false
    var worldVersion: Int? = null
    var minSection = -4

    var i = 0
    fun value(flag: String): String = args.getOrNull(++i) ?: throw UsageError("$flag needs a value")
    fun number(flag: String): Int = value(flag).toIntOrNull() ?: throw UsageError("$flag needs a whole number")

    while (i < args.size) {
        when (val arg = args[i]) {
            "-h", "--help" -> { println(USAGE); return 0 }
            "--version" -> { println("SlimeToAnvil $VERSION"); return 0 }
            "-o", "--output" -> output = value(arg)
            "-f", "--force" -> force = true
            "--no-entities" -> entities = false
            "--no-level-dat" -> levelDat = false
            "--keep-extra" -> keepExtra = true
            "--info" -> infoOnly = true
            "--world-version" -> worldVersion = number(arg)
            "--min-section" -> minSection = number(arg)
            else -> {
                if (arg.startsWith("-")) throw UsageError("Unknown option $arg")
                if (input != null) throw UsageError("Only one input is supported (got '$input' and '$arg')")
                input = arg
            }
        }
        i++
    }
    // No input given (e.g. the program was just double-clicked): use the folder it is run from.
    val inputPath = Paths.get(input ?: ".")
    if (input == null) println("No input given: converting every .slime file in ${inputPath.toAbsolutePath().normalize()}")
    if (!Files.exists(inputPath)) {
        System.err.println("Input does not exist: $inputPath")
        return 1
    }

    val options = ConversionOptions(
        overwrite = force,
        includeEntities = entities,
        writeLevelDat = levelDat,
        keepSlimeExtra = keepExtra,
        read = ReadOptions(forceLegacyWorldVersion = worldVersion, minSectionY = minSection),
    )

    val files: List<Path> = if (Files.isDirectory(inputPath)) {
        Files.list(inputPath).use { s ->
            s.filter { it.fileName.toString().endsWith(".slime") }.sorted().collect(java.util.stream.Collectors.toList())
        }
    } else listOf(inputPath)
    if (files.isEmpty()) {
        System.err.println("No .slime files found in ${inputPath.toAbsolutePath().normalize()}")
        return 1
    }

    var failures = 0
    for (file in files) {
        try {
            if (infoOnly) printInfo(file, options) else convertOne(file, inputPath, output, options)
        } catch (e: ConversionException) {
            failures++
            System.err.println("FAILED ${file.fileName}: ${e.message}")
            if (e.message?.contains("overwrite") == true) System.err.println("  (run again with --force to replace it)")
        }
    }
    if (files.size > 1) println("Done: ${files.size - failures}/${files.size} converted.")
    if (failures > 0 && !force) println("Hint: use --force to replace worlds that were already converted.")
    return if (failures == 0) 0 else 1
}

private fun convertOne(file: Path, inputRoot: Path, output: String?, options: ConversionOptions) {
    val name = SlimeConverter.baseName(file)
    val target: Path = when {
        output == null -> (file.toAbsolutePath().parent ?: Paths.get(".")).resolve(name)
        Files.isDirectory(inputRoot) || file != inputRoot -> Paths.get(output).resolve(name)
        else -> Paths.get(output)
    }
    val r = SlimeConverter.convert(file, target.toAbsolutePath().normalize(), options.copy(levelName = name))
    println(
        "${file.fileName} -> ${r.outputDir}  " +
            "[slime v${r.formatVersion}, ${r.era.name.lowercase()}, dataVersion ${r.dataVersion}] " +
            "${r.chunks} chunks in ${r.regions} region(s), ${r.tileEntities} tile entities, ${r.entities} entities " +
            "(${r.elapsedMillis} ms)"
    )
    r.warnings.forEach { println("  note: $it") }
}

private fun printInfo(file: Path, options: ConversionOptions) {
    val w = SlimeConverter.inspect(file, options.read)
    val minX = w.chunks.minOf { it.x }; val maxX = w.chunks.maxOf { it.x }
    val minZ = w.chunks.minOf { it.z }; val maxZ = w.chunks.maxOf { it.z }
    println("${file.fileName}")
    println("  slime format : v${w.formatVersion}")
    println("  era          : ${w.era.name.lowercase()} (world version ${w.worldVersion}, dataVersion ${w.dataVersion})")
    println("  chunks       : ${w.chunkCount} (x $minX..$maxX, z $minZ..$maxZ)")
    println("  tile entities: ${w.chunks.sumOf { it.tileEntities.size }}, entities: ${w.chunks.sumOf { it.entities.size }}")
    println("  maps         : ${w.maps.size}")
}
