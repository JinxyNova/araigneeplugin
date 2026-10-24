package com.heledron.spideranimation.horror

import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.random.Random

/**
 * Mémoire des joueurs déjà visés, pour répartir l'araignée entre tous les joueurs d'un serveur multijoueur :
 * ceux qu'elle n'a pas visés depuis longtemps ont plus de chances d'être choisis.
 */
object HorrorVictims {
    private val lastTargeted = HashMap<UUID, Long>()

    fun mark(player: Player) {
        val now = System.currentTimeMillis()
        lastTargeted[player.uniqueId] = now
        if (lastTargeted.size > 200) lastTargeted.entries.removeIf { now - it.value > 3_600_000L }
    }

    /** Secondes depuis la dernière fois que ce joueur a été visé (jamais visé = très longtemps). */
    private fun staleSeconds(player: Player): Double {
        val last = lastTargeted[player.uniqueId] ?: return 1.0e9
        return (System.currentTimeMillis() - last) / 1000.0
    }

    /**
     * Tire un joueur au hasard, pondéré : plus il a été visé récemment, moins il a de chances.
     * [exclude] est écarté sauf s'il est le seul candidat. [from] favorise légèrement les joueurs proches.
     */
    fun pick(candidates: List<Player>, exclude: UUID?, memorySeconds: Double, from: Vector?): Player? {
        if (candidates.isEmpty()) return null
        var pool = candidates
        if (exclude != null && candidates.size > 1) pool = candidates.filter { it.uniqueId != exclude }
        if (pool.size == 1) return pool[0]

        val memory = memorySeconds.coerceAtLeast(1.0)
        val weights = pool.map { player ->
            var weight = 0.1 + (staleSeconds(player) / memory).coerceAtMost(1.0)
            if (from != null) weight /= 1.0 + player.location.toVector().distance(from) / 150.0
            weight.coerceAtLeast(0.01)
        }

        var roll = Random.nextDouble() * weights.sum()
        for (i in pool.indices) {
            roll -= weights[i]
            if (roll <= 0.0) return pool[i]
        }
        return pool.last()
    }
}
