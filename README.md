# Slime To Anvil World Converter / BedWars1058-AnvilConverter

| Module | What it is |
|---|---|
| `SlimeToAnvil` | Converter library + command line utility. Reads Slime Region Format (`.slime`) worlds and writes plain Anvil worlds. |
| `BedWars1058-AnvilConverter` | Spigot/Paper plugin that wraps the library: converts the slime arena worlds of a BedWars1058 server into Anvil template worlds. |

The plugin reuses the utility's code, so both behave identically.

## Build

```
./gradlew build
```

Outputs:

- `SlimeToAnvil/build/executable/SlimeToAnvil` - self-executing single file for Linux/macOS (`./SlimeToAnvil lobby.slime`)
- `SlimeToAnvil/build/executable/SlimeToAnvil.bat` + `SlimeToAnvil.jar` - Windows launcher (keep the two together)
- `SlimeToAnvil/build/libs/SlimeToAnvil-1.0.0-all.jar` - the same runnable jar (`java -jar`)
- `BedWars1058-AnvilConverter/build/libs/BedWars1058-AnvilConverter-1.0.0.jar` - drop into `plugins/`

### Standalone builds (no Java needed)

The recommended way is `jpackage`, which ships with the JDK (no GraalVM needed). It produces an app image with a small bundled
runtime (~40 MB, ~23 MB zipped):

```
./gradlew :SlimeToAnvil:jpackageZip
# -> SlimeToAnvil/build/jpackage/SlimeToAnvil-<linux|windows|macos>-<x64|arm64>.zip
```

Launcher inside the zip: `SlimeToAnvil/bin/SlimeToAnvil` (Linux), `SlimeToAnvil/SlimeToAnvil.exe` (Windows, console app),
`SlimeToAnvil.app/Contents/MacOS/SlimeToAnvil` (macOS).

`jpackage` cannot cross-compile, so each OS has to be built on that OS. `.github/workflows/native.yml` does this for
linux-x64, linux-arm64, windows-x64, macos-arm64 and macos-x64, and attaches the zips to a GitHub release when you push a
`v*` tag. Only the Linux x64 image has been built and run so far. Binaries downloaded on macOS/Windows are unsigned, so
Gatekeeper / SmartScreen may ask for confirmation.

A single-file native binary via GraalVM is still available as an alternative (`GRAALVM_HOME=... ./gradlew :SlimeToAnvil:nativeImage`).

Everything is compiled to Java 8 bytecode (`-Xjdk-release=8`), so the same jars run on Java 8 servers (1.8) up to the newest
Java versions. The plugin bundles and relocates the Kotlin stdlib and the zstd decoder (pure Java, no natives), so it does
not depend on anything else being installed.

## SlimeToAnvil (CLI)

```
./SlimeToAnvil                                  # no arguments: converts every .slime in the current folder
./SlimeToAnvil lobby.slime                      # -> ./lobby/   (Windows: SlimeToAnvil.bat)
./SlimeToAnvil slime_worlds/ -o arenas/ --force # every .slime in a folder
./SlimeToAnvil lobby.slime --info               # inspect only
```

The executable still needs a Java runtime (8+) on the machine; it honours `JAVA_HOME`.

Run with no arguments, it converts every `.slime` file in the current folder into a folder with the same name next to it
(existing converted worlds are left alone unless `--force` is given).

Options: `-o/--output`, `-f/--force`, `--no-entities`, `--no-level-dat`, `--keep-extra`, `--world-version <n>`,
`--min-section <y>`, `--info`.

## BedWars1058-AnvilConverter (plugin)

Commands (permission `bwanvil.use`, default op; aliases `/anvilconverter`, `/bw-anvil`):

- `/bwanvil list` - slime worlds found, whether each is a BedWars1058 arena, whether it is already converted
- `/bwanvil info <name>` - format version, era, chunk/entity counts
- `/bwanvil convert <name|all> [overwrite]` - convert (async); refuses worlds that are currently loaded
- `/bwanvil reload`

By default the world is written to `<server>/<name>/`, which is where BedWars1058's default world handling expects the arena
template. If the folder already exists it is renamed to `<name>.bak-<timestamp>` first (`existing-world` in `config.yml`).
Optional `auto-convert` converts missing arena worlds at startup. Restart or reload BedWars1058 afterwards.
Slime files are never modified.

## Version support

| Slime file | Minecraft | Output |
|---|---|---|
| v1-v9, world version 1-3 | 1.8 - 1.12 | `Level` chunks with `Blocks`/`Data`, `level.dat`, light always populated |
| v1-v9, world version 4-7 | 1.13 - 1.17 | palette chunks, `Heightmaps`, 3D biomes for 1.15+, separate `entities/` regions for 1.17 |
| v10-v12 | 1.18 - 26.x | 1.18+ chunk layout (`sections` / `block_states` / `biomes`), the DataVersion in the file is copied through |

Because v10+ files carry their own DataVersion, new Minecraft releases need no code change as long as the chunk container
layout stays as in 1.18+. `level.dat` is only written for 1.8 - 1.12; its newer layouts change nearly every release, and every
server creates a valid one itself when the file is missing (the region files are kept).

Slime worlds from 1.8 and 1.12 have been tested to work, but regardless, it is **HIGHLY RECOMMENDED** to create backups.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.