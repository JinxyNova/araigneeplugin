package com.heledron.spideranimation.horror

import com.heledron.spideranimation.AppState
import com.heledron.spideranimation.spider.components.Cloak
import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import com.heledron.spideranimation.utilities.overloads.direction
import com.heledron.spideranimation.utilities.overloads.eyePosition
import com.heledron.spideranimation.utilities.raycastGround
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.util.Vector

object HorrorCommand : CommandExecutor, TabCompleter {
    private val SUBCOMMANDS = listOf("spawn", "remove", "clear", "list", "reload", "scale", "color", "charge")

    private fun usage(sender: CommandSender) {
        sender.sendPlainMessage("Usage : /horror <spawn [taille] | remove | clear | list | reload | scale <taille> | color <#rrggbb|auto> | charge>")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            usage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "spawn" -> spawn(sender, args)
            "remove" -> {
                val entity = target(sender) ?: return noSpider(sender)
                entity.remove()
                sender.sendPlainMessage("Araignée supprimée.")
            }

            "clear" -> {
                val all = Horror.allSpiders()
                all.forEach { it.remove() }
                sender.sendPlainMessage("${all.size} araignée(s) supprimée(s).")
            }

            "list" -> list(sender)
            "reload" -> {
                Horror.reload()
                sender.sendPlainMessage("Configuration rechargée.")
            }

            "scale" -> {
                val value = args.getOrNull(1)?.toDoubleOrNull()
                if (value == null) {
                    sender.sendPlainMessage("Usage : /horror scale <taille>   (ex : /horror scale 2.5)")
                    return true
                }
                val entity = target(sender) ?: return noSpider(sender)
                val spider = entity.query<SpiderBody>() ?: return noSpider(sender)
                val options = entity.query<SpiderOptions>() ?: return noSpider(sender)
                val behavior = entity.query<HorrorBehavior>() ?: return noSpider(sender)

                val clamped = value.coerceIn(0.2, 8.0)
                behavior.setScaleNow(spider, options, clamped)
                sender.sendPlainMessage("Taille réglée à $clamped.")
            }

            "color" -> {
                val text = args.getOrNull(1)
                if (text == null) {
                    sender.sendPlainMessage("Usage : /horror color <#rrggbb>   ou   /horror color auto")
                    return true
                }
                val entity = target(sender) ?: return noSpider(sender)
                val cloak = entity.query<Cloak>() ?: return noSpider(sender)
                val behavior = entity.query<HorrorBehavior>() ?: return noSpider(sender)

                if (text.equals("auto", ignoreCase = true)) {
                    behavior.autoColor = true
                    sender.sendPlainMessage("Couleurs automatiques réactivées.")
                } else {
                    val color = parseHexColor(text)
                    if (color == null) {
                        sender.sendPlainMessage("Couleur invalide. Exemple : /horror color #8b0000")
                        return true
                    }
                    behavior.autoColor = false
                    cloak.tint = color
                    sender.sendPlainMessage("Couleur fixée sur $text (/horror color auto pour revenir au mode automatique).")
                }
            }

            "charge" -> {
                val entity = target(sender) ?: return noSpider(sender)
                val spider = entity.query<SpiderBody>() ?: return noSpider(sender)
                val options = entity.query<SpiderOptions>() ?: return noSpider(sender)
                val behavior = entity.query<HorrorBehavior>() ?: return noSpider(sender)
                behavior.forceCharge(spider, options, sender as? Player)
                sender.sendPlainMessage("Charge !")
            }

            else -> usage(sender)
        }
        return true
    }

    // -------------------------------------------------------------------------

    private fun noSpider(sender: CommandSender): Boolean {
        sender.sendPlainMessage("Aucune araignée d'horreur trouvée. Utilise /horror spawn.")
        return true
    }

    /** L'araignée la plus proche du joueur (ou la première si la commande vient de la console). */
    private fun target(sender: CommandSender): ECSEntity? {
        if (sender is Player) return Horror.nearest(sender.world, sender.location.toVector())
        return Horror.allSpiders().firstOrNull()
    }

    private fun spawn(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player
        if (player == null) {
            sender.sendPlainMessage("Cette commande doit être lancée par un joueur.")
            return
        }

        val scale = args.getOrNull(1)?.toDoubleOrNull()

        // pose l'araignée là où le joueur regarde ; à défaut, 8 blocs devant lui
        val hit = player.world.raycastGround(player.eyePosition, player.direction, 60.0)
        val ground: Vector = hit?.hitPosition ?: run {
            val ahead = player.location.toVector().add(player.direction.setY(0.0).normalize().multiply(8.0))
            val y = player.world.getHighestBlockYAt(ahead.blockX, ahead.blockZ) + 1.0
            Vector(ahead.x, y, ahead.z)
        }

        Horror.spawn(player.world, ground, player.location.toVector(), scale)
        sender.sendPlainMessage("Araignée d'horreur invoquée. Regarde-la... si tu l'oses.")
    }

    private fun list(sender: CommandSender) {
        val all = AppState.ecs.query<SpiderBody, HorrorBehavior>().toList()
        if (all.isEmpty()) {
            sender.sendPlainMessage("Aucune araignée d'horreur.")
            return
        }
        sender.sendPlainMessage("${all.size} araignée(s) :")
        for ((spider, behavior) in all) {
            val p = spider.position
            sender.sendPlainMessage(
                " - ${spider.world.name} (${p.blockX}, ${p.blockY}, ${p.blockZ}) état : ${behavior.state}"
            )
        }
    }

    // -------------------------------------------------------------------------

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String>? {
        return when (args.size) {
            1 -> SUBCOMMANDS.filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "color" -> listOf("auto", "#000000", "#8b0000", "#ff1111", "#14081f")
                "scale", "spawn" -> listOf("0.5", "1", "2", "3")
                else -> emptyList()
            }

            else -> emptyList()
        }
    }
}
