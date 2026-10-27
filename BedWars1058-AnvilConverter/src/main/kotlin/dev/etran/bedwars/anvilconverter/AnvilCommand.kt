package dev.etran.bedwars.anvilconverter

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import java.util.Locale

class AnvilCommand(private val plugin: AnvilConverterPlugin) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("bwanvil.use")) {
            sender.sendMessage("${ChatColor.RED}You do not have permission to do that.")
            return true
        }
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "list" -> list(sender)
            "info" -> info(sender, args)
            "convert" -> convert(sender, args)
            "reload" -> {
                plugin.loadService()
                plugin.reply(sender, "${ChatColor.GREEN}Configuration reloaded.")
            }
            else -> usage(sender, label)
        }
        return true
    }

    private fun usage(sender: CommandSender, label: String) {
        sender.sendMessage("${ChatColor.GOLD}/$label list ${ChatColor.GRAY}- slime worlds and their conversion status")
        sender.sendMessage("${ChatColor.GOLD}/$label info <name> ${ChatColor.GRAY}- inspect a slime world")
        sender.sendMessage("${ChatColor.GOLD}/$label convert <name|all> [overwrite] ${ChatColor.GRAY}- convert to Anvil")
        sender.sendMessage("${ChatColor.GOLD}/$label reload ${ChatColor.GRAY}- reload config.yml")
    }

    private fun list(sender: CommandSender) {
        val svc = plugin.service
        val names = svc.slimeNames()
        if (names.isEmpty()) {
            plugin.reply(sender, "${ChatColor.YELLOW}No .slime files found in the configured slime-directories.")
            return
        }
        val arenas = svc.arenaNames().toSet()
        plugin.reply(sender, "${ChatColor.GRAY}${names.size} slime world(s):")
        for (name in names) {
            val tags = buildList {
                if (name in arenas) add("${ChatColor.AQUA}arena")
                add(if (svc.isConverted(name)) "${ChatColor.GREEN}converted" else "${ChatColor.RED}not converted")
            }
            sender.sendMessage("${ChatColor.GRAY} - ${ChatColor.WHITE}$name ${ChatColor.GRAY}[${tags.joinToString("${ChatColor.GRAY}, ")}${ChatColor.GRAY}]")
        }
    }

    private fun info(sender: CommandSender, args: Array<String>) {
        val name = args.getOrNull(1) ?: return plugin.reply(sender, "${ChatColor.RED}Usage: /bwanvil info <name>")
        val svc = plugin.service
        if (svc.slimeFile(name) == null) return plugin.reply(sender, "${ChatColor.RED}No slime world named '$name'.")
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val text = try { svc.inspect(name) } catch (e: Exception) { "${ChatColor.RED}${e.message}" }
            org.bukkit.Bukkit.getScheduler().runTask(plugin, Runnable { plugin.reply(sender, "${ChatColor.WHITE}$name${ChatColor.GRAY}: $text") })
        })
    }

    private fun convert(sender: CommandSender, args: Array<String>) {
        val target = args.getOrNull(1) ?: return plugin.reply(sender, "${ChatColor.RED}Usage: /bwanvil convert <name|all> [overwrite]")
        val overwrite = args.drop(2).any { it.equals("overwrite", true) || it.equals("--overwrite", true) }
        val svc = plugin.service

        val names = if (target.equals("all", true)) svc.slimeNames() else listOf(target)
        if (names.isEmpty()) return plugin.reply(sender, "${ChatColor.YELLOW}Nothing to convert.")
        for (n in names) {
            if (svc.slimeFile(n) == null) return plugin.reply(sender, "${ChatColor.RED}No slime world named '$n'.")
            if (svc.isLoaded(n)) {
                return plugin.reply(sender, "${ChatColor.RED}World '$n' is currently loaded. Unload it (or stop the arena) first.")
            }
        }
        plugin.reply(sender, "${ChatColor.GRAY}Converting ${names.size} world(s)...")
        plugin.convertAsync(names, overwrite, sender)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> {
        if (!sender.hasPermission("bwanvil.use")) return emptyList()
        val svc = plugin.service
        val options = when (args.size) {
            1 -> listOf("list", "info", "convert", "reload")
            2 -> when (args[0].lowercase(Locale.ROOT)) {
                "info" -> svc.slimeNames()
                "convert" -> listOf("all") + svc.slimeNames()
                else -> emptyList()
            }
            3 -> if (args[0].equals("convert", true)) listOf("overwrite") else emptyList()
            else -> emptyList()
        }
        val prefix = args.last().lowercase(Locale.ROOT)
        return options.filter { it.lowercase(Locale.ROOT).startsWith(prefix) }
    }
}
