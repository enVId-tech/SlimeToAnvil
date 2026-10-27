plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    // The converter engine (also the standalone CLI) lives in the SlimeToAnvil module.
    implementation(project(":SlimeToAnvil"))
    // bungeecord-chat (a transitive of the 1.8.8 API) is no longer published anywhere reliable and is not used here.
    compileOnly(libs.spigot.api) { exclude(group = "net.md-5", module = "bungeecord-chat") }
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveBaseName.set("BedWars1058-AnvilConverter")
    archiveClassifier.set("")
    // Relocate everything we bundle so we never clash with other plugins (or the server's own libs).
    relocate("kotlin", "dev.etran.bedwars.anvilconverter.libs.kotlin")
    relocate("org.jetbrains", "dev.etran.bedwars.anvilconverter.libs.jetbrains")
    relocate("org.intellij", "dev.etran.bedwars.anvilconverter.libs.intellij")
    relocate("io.airlift", "dev.etran.bedwars.anvilconverter.libs.airlift")
    exclude("dev/etran/slimetoanvil/cli/**") // the CLI entry point has no place inside a plugin
}

tasks.build { dependsOn(tasks.shadowJar) }
