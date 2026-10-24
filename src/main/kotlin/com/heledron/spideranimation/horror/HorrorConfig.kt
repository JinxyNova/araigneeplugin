package com.heledron.spideranimation.horror

import com.heledron.spideranimation.utilities.colors.Oklab
import com.heledron.spideranimation.utilities.colors.toOklab
import org.bukkit.Color
import org.bukkit.configuration.file.FileConfiguration
import kotlin.random.Random

/** Intervalle de durée en secondes, tiré au hasard en ticks. */
class SecondsRange(val min: Double, val max: Double) {
    fun randomTicks(): Int {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        return ((lo + Random.nextDouble() * (hi - lo)) * 20.0).toInt().coerceAtLeast(1)
    }
}

/** Convertit "#rrggbb" (ou "rrggbb") en couleur Oklab. Renvoie null si invalide. */
fun parseHexColor(text: String): Oklab? = try {
    Color.fromRGB(text.trim().removePrefix("#").toInt(16)).toOklab()
} catch (_: Exception) {
    null
}

private fun parsePalette(list: List<String>, fallback: List<String>): List<Oklab> {
    val parsed = list.mapNotNull(::parseHexColor)
    return parsed.ifEmpty { fallback.mapNotNull(::parseHexColor) }
}

/** Lecture de config.yml. Une nouvelle instance est créée à chaque /horror reload. */
class HorrorConfig(c: FileConfiguration) {
    // --- comportement ---------------------------------------------------------
    val followRange = c.getDouble("behavior.follow-range", 260.0)
    val seeRange = c.getDouble("behavior.see-range", 64.0)
    val seeAngle = c.getDouble("behavior.see-angle", 55.0)
    val requireLineOfSight = c.getBoolean("behavior.require-line-of-sight", true)
    val ignoreCreative = c.getBoolean("behavior.ignore-creative", false)
    val closestApproach = c.getDouble("behavior.closest-approach", 5.0)

    // approche : rapide de très loin, puis lente avec ralentissements / arrêts aléatoires
    val farDistance = c.getDouble("behavior.far-distance", 70.0)
    val farSpeed = c.getDouble("behavior.far-speed", 0.3)
    val stalkPhase = SecondsRange(
        c.getDouble("behavior.stalk-phase-seconds.min", 1.0),
        c.getDouble("behavior.stalk-phase-seconds.max", 4.0),
    )
    val stalkStopChance = c.getDouble("behavior.stalk-stop-chance", 0.25)
    val stalkMinFactor = c.getDouble("behavior.stalk-min-speed-factor", 0.15).coerceIn(0.0, 1.0)

    // regard insistant : trop longtemps / trop de fois -> elle fonce 1 à 3 s après
    val stareTicks = (c.getDouble("behavior.aggro.stare-seconds", 3.0) * 20).toInt().coerceAtLeast(1)
    val looksLimit = c.getInt("behavior.aggro.looks-limit", 3).coerceAtLeast(1)
    val looksDecayTicks = (c.getDouble("behavior.aggro.looks-decay-seconds", 30.0) * 20).toInt().coerceAtLeast(20)
    val rushTime = SecondsRange(
        c.getDouble("behavior.aggro.rush-seconds.min", 1.0),
        c.getDouble("behavior.aggro.rush-seconds.max", 3.0),
    )

    // disparition pendant une charge
    val vanishChancePerSecond = c.getDouble("behavior.vanish-during-charge-chance-per-second", 0.15)

    val freezeTime = SecondsRange(
        c.getDouble("behavior.freeze-seconds.min", 1.0),
        c.getDouble("behavior.freeze-seconds.max", 10.0),
    )
    val chargeMaxTicks = (c.getDouble("behavior.charge-max-seconds", 7.0) * 20).toInt().coerceAtLeast(20)

    val stalkSpeed = c.getDouble("behavior.stalk-speed", 0.05)
    val chargeSpeed = c.getDouble("behavior.charge-speed", 0.4)
    val chargeSpeedCap = c.getDouble("behavior.charge-speed-cap", 0.6)

    val attackRange = c.getDouble("behavior.attack.range", 1.0)
    val attackDamage = c.getDouble("behavior.attack.damage", 4.0)
    val attackKnockback = c.getDouble("behavior.attack.knockback", 0.6)
    val attackCalmTicks = (c.getDouble("behavior.attack.calm-seconds", 2.0) * 20).toInt().coerceAtLeast(0)

    // --- taille aléatoire -----------------------------------------------------
    val scaleEnabled = c.getBoolean("scale.enabled", true)
    val scaleMin = c.getDouble("scale.min", 0.5).coerceAtLeast(0.2)
    val scaleMax = c.getDouble("scale.max", 3.0).coerceAtLeast(scaleMin)
    val scaleInterval = SecondsRange(
        c.getDouble("scale.change-interval.min", 4.0),
        c.getDouble("scale.change-interval.max", 14.0),
    )
    val scaleSteps = c.getInt("scale.transition-steps", 8).coerceAtLeast(1)
    val scaleStepTicks = c.getInt("scale.ticks-per-step", 2).coerceAtLeast(1)
    val scaleOnCharge = c.getBoolean("scale.grow-on-charge", true)

    // --- couleurs -------------------------------------------------------------
    val colorsEnabled = c.getBoolean("colors.enabled", true)
    val colorFadeSpeed = c.getDouble("colors.fade-speed", 0.04)
    val colorVariation = c.getDouble("colors.piece-variation", 0.03).toFloat()
    val colorInterval = SecondsRange(
        c.getDouble("colors.change-interval.min", 3.0),
        c.getDouble("colors.change-interval.max", 9.0),
    )
    val calmColors = parsePalette(
        c.getStringList("colors.calm"),
        listOf("#0a0a0a", "#14081f", "#0c1a0c", "#1c0a0a"),
    )
    val alertColors = parsePalette(
        c.getStringList("colors.alert"),
        listOf("#5c0000", "#c9c3a5", "#3b0f4d"),
    )
    val chargeColors = parsePalette(
        c.getStringList("colors.charge"),
        listOf("#ff1111", "#a30000", "#e8e8e8"),
    )

    // --- sons -----------------------------------------------------------------
    val soundsEnabled = c.getBoolean("sounds.enabled", true)
    val ambientInterval = SecondsRange(
        c.getDouble("sounds.ambient-seconds.min", 6.0),
        c.getDouble("sounds.ambient-seconds.max", 20.0),
    )

    // --- apparition automatique ----------------------------------------------
    val autoSpawnEnabled = c.getBoolean("auto-spawn.enabled", true)
    val autoSpawnCheckTicks = (c.getDouble("auto-spawn.check-seconds", 6.0) * 20).toLong().coerceAtLeast(20L)
    val autoSpawnChance = c.getDouble("auto-spawn.chance", 0.7)
    val autoSpawnOnlyAtNight = c.getBoolean("auto-spawn.only-at-night", false)
    val autoSpawnMinChunks = c.getDouble("auto-spawn.min-chunks", 1.0).coerceAtLeast(0.5)
    val autoSpawnMaxChunks = c.getDouble("auto-spawn.max-chunks", 15.0).coerceAtLeast(autoSpawnMinChunks)
    val autoSpawnMaxSpiders = c.getInt("auto-spawn.max-per-player", 2)
    val autoDespawnTicks = (c.getDouble("auto-spawn.despawn-seconds", 60.0) * 20).toInt().coerceAtLeast(20)
}
