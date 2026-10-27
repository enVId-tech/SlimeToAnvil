plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

dependencies {
    // Pure-Java zstd (no natives, relocatable, Java 8 compatible). Only decompression is needed at runtime.
    api(libs.aircompressor)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.shadowJar {
    archiveBaseName.set("SlimeToAnvil")
    archiveClassifier.set("all")
    manifest { attributes("Main-Class" to "dev.etran.slimetoanvil.cli.MainKt") }
}

// Runnable artifacts in build/executable:
//   SlimeToAnvil      - single self-executing file for Linux/macOS (shell stub + jar), run as ./SlimeToAnvil
//   SlimeToAnvil.bat  - Windows launcher, needs SlimeToAnvil.jar beside it (copied there too)
val executable by tasks.registering {
    group = "build"
    description = "Creates the self-executing SlimeToAnvil launcher files."
    val fatJar = tasks.shadowJar.flatMap { it.archiveFile }
    val outDir = layout.buildDirectory.dir("executable")
    val launcherDir = layout.projectDirectory.dir("src/launcher")
    dependsOn(tasks.shadowJar)
    inputs.file(fatJar)
    inputs.dir(launcherDir)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.also { it.mkdirs() }
        val jarBytes = fatJar.get().asFile.readBytes()
        val unix = File(dir, "SlimeToAnvil")
        unix.outputStream().use { out ->
            out.write(launcherDir.file("stub.sh").asFile.readBytes())
            out.write(jarBytes)
        }
        unix.setExecutable(true, false)
        File(dir, "SlimeToAnvil.jar").writeBytes(jarBytes)
        launcherDir.file("SlimeToAnvil.bat").asFile.copyTo(File(dir, "SlimeToAnvil.bat"), overwrite = true)
    }
}

// Native binary (no Java needed to run it). GraalVM's native-image cannot cross-compile, so this produces the
// binary for the machine it runs on: build/native/SlimeToAnvil-<linux|windows|macos>-<x64|arm64>[.exe].
// Needs a GraalVM JDK 21+ (GRAALVM_HOME, or native-image on the PATH). The CI workflow builds all platforms.
val nativeImage by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds a native executable for the current OS/architecture with GraalVM native-image."
    val fatJar = tasks.shadowJar.flatMap { it.archiveFile }
    dependsOn(tasks.shadowJar)

    val osProp = System.getProperty("os.name").lowercase()
    val osName = when {
        "win" in osProp -> "windows"
        "mac" in osProp || "darwin" in osProp -> "macos"
        else -> "linux"
    }
    val archProp = System.getProperty("os.arch").lowercase()
    val arch = if (archProp == "aarch64" || archProp == "arm64") "arm64" else "x64"
    val outDir = layout.buildDirectory.dir("native")
    val baseName = "SlimeToAnvil-$osName-$arch"

    val graalHome = System.getenv("GRAALVM_HOME")
    val binary = if (osName == "windows") "native-image.cmd" else "native-image"
    executable = if (graalHome != null) "$graalHome/bin/$binary" else binary

    inputs.file(fatJar)
    outputs.dir(outDir)
    doFirst {
        if (graalHome == null) {
            logger.warn("GRAALVM_HOME is not set; looking for '$binary' on the PATH. On Windows run this from the " +
                "'x64 Native Tools Command Prompt for VS' so the C++ build tools are found.")
        }
        outDir.get().asFile.mkdirs()
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--no-fallback", "-O2",
            "-jar", fatJar.get().asFile.absolutePath,
            "-o", outDir.get().file(baseName).asFile.absolutePath,
        )
    })
}

// Self-contained app image built with jpackage (ships with the JDK, fully supported; no GraalVM needed).
// The image bundles a small jlink runtime, so end users do not need Java installed. jpackage cannot
// cross-compile either, so run it on each OS (the CI workflow does): build/jpackage/SlimeToAnvil-<os>-<arch>.zip
val jpackageImage by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds a self-contained app image (bundled runtime) with the JDK's jpackage."
    val fatJar = tasks.shadowJar.flatMap { it.archiveFile }
    dependsOn(tasks.shadowJar)

    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val inputDir = layout.buildDirectory.dir("jpackage-input")
    val destDir = layout.buildDirectory.dir("jpackage/image")
    val jpackage = File(System.getProperty("java.home"), "bin/" + if (isWindows) "jpackage.exe" else "jpackage")
    executable = jpackage.absolutePath

    inputs.file(fatJar)
    outputs.dir(destDir)
    doFirst {
        destDir.get().asFile.deleteRecursively()
        inputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        fatJar.get().asFile.copyTo(File(inputDir.get().asFile, "SlimeToAnvil.jar"))
    }
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            addAll(listOf("--type", "app-image", "--name", "SlimeToAnvil", "--app-version", project.version.toString()))
            addAll(listOf("--input", inputDir.get().asFile.absolutePath))
            addAll(listOf("--main-jar", "SlimeToAnvil.jar", "--main-class", "dev.etran.slimetoanvil.cli.MainKt"))
            addAll(listOf("--dest", destDir.get().asFile.absolutePath))
            // java.base + jdk.unsupported (sun.misc.Unsafe, used by the zstd decoder) is all the tool needs.
            addAll(listOf("--add-modules", "java.base,jdk.unsupported"))
            // jlink's --compress changed in JDK 21 ("zip-6"); JDK 17 only understands the numeric levels 0-2.
            val compress = if (JavaVersion.current() >= JavaVersion.VERSION_21) "zip-6" else "2"
            addAll(listOf("--jlink-options", "--strip-debug --no-header-files --no-man-pages --compress=$compress"))
            if (isWindows) add("--win-console")
        }
    })
}

val jpackageZip by tasks.registering(Zip::class) {
    group = "build"
    description = "Zips the jpackage app image as SlimeToAnvil-<os>-<arch>.zip."
    dependsOn(jpackageImage)
    val osProp = System.getProperty("os.name").lowercase()
    val osName = when {
        "win" in osProp -> "windows"
        "mac" in osProp || "darwin" in osProp -> "macos"
        else -> "linux"
    }
    val archProp = System.getProperty("os.arch").lowercase()
    val arch = if (archProp == "aarch64" || archProp == "arm64") "arm64" else "x64"
    archiveFileName.set("SlimeToAnvil-$osName-$arch.zip")
    destinationDirectory.set(layout.buildDirectory.dir("jpackage"))
    from(layout.buildDirectory.dir("jpackage/image"))
    isPreserveFileTimestamps = false
}

tasks.build { dependsOn(tasks.shadowJar, executable) }
