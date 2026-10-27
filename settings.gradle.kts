pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
            name = "spigot"
            content { includeGroup("org.spigotmc"); includeGroup("net.md-5") }
        }
    }
}

rootProject.name = "etran-bedwars-anvil"

// The standalone utility (also reused as the engine of the plugin).
include("SlimeToAnvil")
// The BedWars1058 plugin that wraps the utility.
include("BedWars1058-AnvilConverter")
