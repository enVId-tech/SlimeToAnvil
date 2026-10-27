package dev.etran.bedwars.anvilconverter

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin

class AnvilConverterPlugin : JavaPlugin() {

    lateinit var service: ConvertService
        private set

    override fun onEnable() {
        saveDefaultConfig()
        loadService()
        val command = AnvilCommand(this)
        getCommand("bwanvil")?.apply {
            executor = command
            tabCompleter = command
        }

        if (config.getBoolean("auto-convert.enabled", false)) {
            // One tick later so BedWars1058 (soft dependency) and the world plugins are fully enabled.
            Bukkit.getScheduler().runTaskLater(this, Runnable { autoConvert() }, 1L)
        }
        logger.info("Enabled. Use /bwanvil list to see convertible slime worlds.")
    }

    fun loadService() {
        reloadConfig()
        service = ConvertService(Bukkit.getWorldContainer().absoluteFile.let { it.canonicalFile }, config)
    }

    private fun autoConvert() {
        val onlyArenas = config.getBoolean("auto-convert.only-arenas", true)
        val arenas = service.arenaNames().toSet()
        val pending = service.slimeNames()
            .filter { !onlyArenas || it in arenas }
            .filter { !service.isConverted(it) && !service.isLoaded(it) }
        if (pending.isEmpty()) return
        logger.info("Auto-converting ${pending.size} slime world(s): ${pending.joinToString()}")
        convertAsync(pending, overwrite = false, sender = null)
    }

    /**
     * Converts [names] one after another off the main thread and reports back on the main thread.
     * [sender] = null reports to the console log only.
     */
    fun convertAsync(names: List<String>, overwrite: Boolean, sender: CommandSender?) {
        val svc = service
        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            val outcomes = names.map { svc.convert(it, overwrite) }
            Bukkit.getScheduler().runTask(this, Runnable {
                for (o in outcomes) report(sender, o)
                val ok = outcomes.count { it.result != null }
                reply(sender, "${ChatColor.GRAY}Finished: ${ChatColor.GREEN}$ok${ChatColor.GRAY}/${outcomes.size} converted. " +
                    "Restart or reload BedWars1058 so it picks up the new world folder(s).")
            })
        })
    }

    private fun report(sender: CommandSender?, o: ConvertOutcome) {
        val r = o.result
        when {
            r != null -> {
                reply(sender, "${ChatColor.GREEN}✔ ${o.name}${ChatColor.GRAY}: ${r.chunks} chunks, ${r.regions} region(s), " +
                    "${r.entities} entities in ${r.elapsedMillis} ms (slime v${r.formatVersion}, dataVersion ${r.dataVersion})")
                r.warnings.forEach { reply(sender, "${ChatColor.DARK_GRAY}  $it") }
            }
            o.skipped != null -> reply(sender, "${ChatColor.YELLOW}- ${o.name}${ChatColor.GRAY}: skipped, ${o.skipped}")
            else -> reply(sender, "${ChatColor.RED}✘ ${o.name}${ChatColor.GRAY}: ${o.error}")
        }
    }

    fun reply(sender: CommandSender?, message: String) {
        if (sender != null) sender.sendMessage("${ChatColor.GOLD}[AnvilConverter] $message")
        else logger.info(ChatColor.stripColor(message))
    }
}
