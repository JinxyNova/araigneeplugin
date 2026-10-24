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
    val scaleMin = c.getDouble("scale.min", 0.4).coerceAtLeast(0.2)
    val scaleMax = c.getDouble("scale.max", 3.0).coerceAtLeast(scaleMin)
    val scaleStart = c.getDouble("scale.start", 0.5).coerceIn(0.2, 8.0)       // taille à l'apparition (petite = plus flippant)
    val scaleCalmMax = c.getDouble("scale.calm-max", 3.0).coerceAtLeast(scaleMin) // taille max tant qu'elle ne charge pas
    val scaleInterval = SecondsRange(
        c.getDouble("scale.change-interval.min", 4.0),
        c.getDouble("scale.change-interval.max", 14.0),
    )
    val scaleSteps = c.getInt("scale.transition-steps", 8).coerceAtLeast(1)
    val scaleStepTicks = c.getInt("scale.ticks-per-step", 2).coerceAtLeast(1)
    val scaleOnCharge = c.getBoolean("scale.grow-on-charge", true)

    // --- IA : comportements qui font peur ---------------------------------------
    val growWhenSeenChance = c.getDouble("ai.grow-when-seen-chance", 0.5)
    val ambushChancePerSecond = c.getDouble("ai.ambush.chance-per-second", 0.08)
    val ambushUnseenTicks = (c.getDouble("ai.ambush.unseen-seconds", 4.0) * 20).toInt().coerceAtLeast(20)
    val ambushMinDistance = c.getDouble("ai.ambush.min-distance", 12.0)
    val ambushMaxDistance = c.getDouble("ai.ambush.max-distance", 28.0).coerceAtLeast(ambushMinDistance)
    val burstChance = c.getDouble("ai.burst.chance", 0.2)
    val burstSpeed = c.getDouble("ai.burst.speed", 0.28)

    // --- camouflage (fond dans le décor) --------------------------------------
    val camouflageEnabled = c.getBoolean("camouflage.enabled", true)
    val camouflageFadeSpeed = c.getDouble("camouflage.fade-speed", 0.15)

    // --- screamer -------------------------------------------------------------
    val screamerEnabled = c.getBoolean("screamer.enabled", true)
    val screamerGrow = c.getBoolean("screamer.grow-to-max", true)
    val screamerBlindTicks = (c.getDouble("screamer.blindness-seconds", 1.5) * 20).toInt().coerceAtLeast(1)
    val screamerDarkTicks = (c.getDouble("screamer.darkness-seconds", 4.0) * 20).toInt().coerceAtLeast(1)
    val screamerSilence = c.getBoolean("screamer.silence-first", true)
    val screamerForceLook = c.getBoolean("screamer.force-look", true)
    val screamerFreezeTicks = (c.getDouble("screamer.freeze-seconds", 1.5) * 20).toInt().coerceAtLeast(0)
    val screamerFlicker = c.getBoolean("screamer.flicker", true)
    val screamerVanishChance = c.getDouble("screamer.vanish-chance", 0.4)
    val screamerVanishDelayTicks = (c.getDouble("screamer.vanish-delay-seconds", 3.0) * 20).toInt().coerceAtLeast(1)

    // --- fuite, cachette, sauts ------------------------------------------------
    val evadeEnabled = c.getBoolean("evade.enabled", true)
    val hideChance = c.getDouble("evade.hide-chance", 0.25)           // quand on la voit : va se cacher
    val retreatChance = c.getDouble("evade.retreat-chance", 0.25)     // quand on la voit : recule
    val evadeCooldown = SecondsRange(
        c.getDouble("evade.cooldown-seconds.min", 8.0),
        c.getDouble("evade.cooldown-seconds.max", 20.0),
    )
    val evadeCalmTicks = (c.getDouble("evade.calm-seconds-after", 2.0) * 20).toInt().coerceAtLeast(1)
    val retreatSpeed = c.getDouble("evade.retreat-speed", 0.18)
    val retreatTime = SecondsRange(
        c.getDouble("evade.retreat-seconds.min", 1.0),
        c.getDouble("evade.retreat-seconds.max", 2.5),
    )
    val retreatHopChance = c.getDouble("evade.retreat-hop-chance", 0.6)
    val hideSpeed = c.getDouble("evade.hide-speed", 0.25)
    val hideSearchRadius = c.getDouble("evade.hide-search-radius", 14.0).coerceAtLeast(5.0)
    val hideMinPlayerDistance = c.getDouble("evade.hide-min-player-distance", 6.0)
    val hideMaxTravelTicks = (c.getDouble("evade.hide-max-travel-seconds", 6.0) * 20).toInt().coerceAtLeast(20)
    val hideWait = SecondsRange(
        c.getDouble("evade.hide-wait-seconds.min", 4.0),
        c.getDouble("evade.hide-wait-seconds.max", 12.0),
    )
    val hideLeapChance = c.getDouble("evade.hide-leap-chance", 0.5)
    val hideAmbushDistance = c.getDouble("evade.hide-ambush-distance", 9.0)
    val hideAmbushChancePerSecond = c.getDouble("evade.hide-ambush-chance-per-second", 0.5)
    val hidePounceWhenSeenChance = c.getDouble("evade.hide-pounce-when-seen-chance", 0.35)

    val jumpEnabled = c.getBoolean("jump.enabled", true)
    val jumpPower = c.getDouble("jump.power", 0.45)            // vitesse verticale (taille 1)
    val jumpForward = c.getDouble("jump.forward", 0.25)        // vitesse horizontale
    val jumpCooldownTicks = (c.getDouble("jump.cooldown-seconds", 1.5) * 20).toInt().coerceAtLeast(1)
    val lungeDistance = c.getDouble("jump.lunge-distance", 9.0)
    val lungeChance = c.getDouble("jump.lunge-chance-per-tick", 0.08)

    val singleSpider = c.getBoolean("single-spider", true)

    // --- mini-araignée ------------------------------------------------------------
    val miniChance = c.getDouble("mini.chance", 0.20).coerceIn(0.0, 1.0)
    val miniScale = c.getDouble("mini.scale", 0.2).coerceIn(0.15, 0.6)
    val miniSpeedBoost = c.getDouble("mini.speed-boost", 3.5).coerceIn(1.0, 10.0)
    val miniClosest = c.getDouble("mini.closest-approach", 2.5)
    val miniAttackRange = c.getDouble("mini.attack-range", 1.6)
    val miniPounceDistance = c.getDouble("mini.pounce-distance", 5.0)
    val miniSeeRangeFactor = c.getDouble("mini.see-range-factor", 0.35).coerceIn(0.05, 1.0)
    val miniDamageFactor = c.getDouble("mini.damage-factor", 0.5).coerceIn(0.0, 5.0)
    val miniSpawnMaxChunks = c.getDouble("mini.spawn-max-chunks", 3.0).coerceAtLeast(0.5)

    // --- multijoueur : rotation des cibles --------------------------------------
    val targetSwitchMinTicks = (c.getDouble("multiplayer.target-switch-min-seconds", 45.0) * 20).toInt().coerceAtLeast(20)
    val targetSwitchMaxTicks = (c.getDouble("multiplayer.target-switch-max-seconds", 120.0) * 20).toInt().coerceAtLeast(targetSwitchMinTicks)
    val switchAfterAttackChance = c.getDouble("multiplayer.switch-after-attack-chance", 0.8)
    val victimMemorySeconds = c.getDouble("multiplayer.victim-memory-seconds", 600.0)

    // --- murs, plafonds ---------------------------------------------------------
    val climbEnabled = c.getBoolean("climb.enabled", true)
    val climbTurnRate = c.getDouble("climb.turn-rate", 0.07).coerceIn(0.01, 0.5)
    val climbSenseTicks = c.getInt("climb.sense-interval-ticks", 2).coerceAtLeast(1)
    val climbLockTicks = c.getInt("climb.lock-ticks", 10).coerceAtLeast(0)
    val climbWallRange = c.getDouble("climb.seek-wall-range", 18.0).coerceAtLeast(3.0)
    val climbSeekChance = c.getDouble("climb.seek-chance-per-second", 0.10)
    val climbSeekMin = c.getDouble("climb.seek-min-distance", 10.0)
    val climbSeekMax = c.getDouble("climb.seek-max-distance", 60.0).coerceAtLeast(climbSeekMin)
    val climbSeekTicks = (c.getDouble("climb.seek-max-seconds", 25.0) * 20).toInt().coerceAtLeast(40)
    val climbCooldownTicks = (c.getDouble("climb.cooldown-seconds", 20.0) * 20).toInt().coerceAtLeast(0)
    val climbDescendHeight = c.getDouble("climb.descend-height", 2.5)
    val pounceEnabled = c.getBoolean("climb.pounce.enabled", true)
    val pounceDistance = c.getDouble("climb.pounce.distance", 10.0)
    val pounceCooldownTicks = (c.getDouble("climb.pounce.cooldown-seconds", 2.0) * 20).toInt().coerceAtLeast(1)
    val dropDistance = c.getDouble("climb.pounce.drop-distance", 6.0)
    val threadEnabled = c.getBoolean("climb.silk-thread", true)
    val scratchSounds = c.getBoolean("climb.scratch-sounds", true)
    val ceilingAmbushChance = c.getDouble("climb.ceiling-ambush-chance", 0.5)

    // --- IA : coups en plus ------------------------------------------------------
    val predictiveCharge = c.getBoolean("ai.predictive-charge", true)
    val skitterChance = c.getDouble("ai.skitter-chance-per-second", 0.5)

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

    // --- angoisse (dread) -----------------------------------------------------
    val dreadEnabled = c.getBoolean("dread.enabled", true)
    val dreadHeartbeat = c.getBoolean("dread.heartbeat.enabled", true)
    val dreadHeartDistance = c.getDouble("dread.heartbeat.distance", 35.0).coerceAtLeast(1.0)
    val dreadNoises = c.getBoolean("dread.fake-noises.enabled", true)
    val dreadNoiseDistance = c.getDouble("dread.fake-noises.distance", 60.0)
    val dreadNoiseInterval = SecondsRange(
        c.getDouble("dread.fake-noises.interval-seconds.min", 4.0),
        c.getDouble("dread.fake-noises.interval-seconds.max", 12.0),
    )
    val dreadDarkness = c.getBoolean("dread.darkness-pulses.enabled", true)
    val dreadDarkDistance = c.getDouble("dread.darkness-pulses.distance", 22.0)
    val dreadDarkInterval = SecondsRange(
        c.getDouble("dread.darkness-pulses.interval-seconds.min", 3.0),
        c.getDouble("dread.darkness-pulses.interval-seconds.max", 8.0),
    )
    val dreadWhispers = c.getBoolean("dread.whispers.enabled", true)
    val dreadWhisperInterval = SecondsRange(
        c.getDouble("dread.whispers.interval-seconds.min", 15.0),
        c.getDouble("dread.whispers.interval-seconds.max", 40.0),
    )
    val dreadSilence = c.getBoolean("dread.silence-on-freeze", true)
    val dreadFear = c.getBoolean("dread.fear-on-freeze", true)
    val dreadChargeEffects = c.getBoolean("dread.charge-effects", true)
    val dreadAftermath = c.getBoolean("dread.aftermath", true)
    val dreadParticles = c.getBoolean("dread.particles", true)

    // --- apparition automatique ----------------------------------------------
    val autoSpawnEnabled = c.getBoolean("auto-spawn.enabled", true)
    val autoSpawnCheckTicks = (c.getDouble("auto-spawn.check-seconds", 6.0) * 20).toLong().coerceAtLeast(20L)
    val autoSpawnChance = c.getDouble("auto-spawn.chance", 0.7)
    val autoSpawnOnlyAtNight = c.getBoolean("auto-spawn.only-at-night", false)
    val autoSpawnMinChunks = c.getDouble("auto-spawn.min-chunks", 1.0).coerceAtLeast(0.5)
    val autoSpawnMaxChunks = c.getDouble("auto-spawn.max-chunks", 15.0).coerceAtLeast(autoSpawnMinChunks)
    val autoSpawnMaxSpiders = c.getInt("auto-spawn.max-per-player", 1)
    val autoSpawnMaxTotal = c.getInt("auto-spawn.max-total", 1)
    val autoDespawnTicks = (c.getDouble("auto-spawn.despawn-seconds", 60.0) * 20).toInt().coerceAtLeast(20)
}
