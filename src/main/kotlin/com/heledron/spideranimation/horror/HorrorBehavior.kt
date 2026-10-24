package com.heledron.spideranimation.horror

import com.heledron.spideranimation.spider.components.Cloak
import com.heledron.spideranimation.spider.components.Locomotion
import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.utilities.ecs.ECSComponent
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import com.heledron.spideranimation.utilities.horizontalLength
import com.heledron.spideranimation.utilities.overloads.playSound
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

enum class HorrorState {
    /** Marche lente vers le joueur. */
    STALK,

    /** Le joueur l'a vu : elle s'immobilise 1 à 10 s (aléatoire), puis charge. */
    FREEZE,

    /** Course vers le joueur. */
    CHARGE,
}

class HorrorBehavior(var autoSpawned: Boolean = false) : ECSComponent {
    var state = HorrorState.STALK; private set

    private var stateTicks = 0
    private var freezeDuration = 0
    private var calmTicks = 0
    private var idleTicks = 0
    private var targetId: UUID? = null

    // taille
    private var targetScale = 1.0
    private var scaleStepsLeft = 0
    private var scaleStepDelay = 0
    private var ticksUntilScaleChange = Horror.config.scaleInterval.randomTicks()

    // regard insistant
    private var lookCount = 0
    private var lookDecay = 0
    private var stareTicks = 0
    private var rushed = false

    // approche
    private var farMode = false
    private var speedPhaseTicks = 0
    private var speedFactor = 1.0
    private var ambientTicks = Horror.config.ambientInterval.randomTicks()

    // couleur
    var autoColor = true
    private var colorDirty = true
    private var ticksUntilColorChange = 0

    // -------------------------------------------------------------------------
    // API utilisée par les commandes
    // -------------------------------------------------------------------------

    /** Force la charge immédiate vers le joueur le plus proche. */
    fun forceCharge(spider: SpiderBody, options: SpiderOptions, player: Player?) {
        switchState(HorrorState.CHARGE, spider, options, player, Horror.config)
    }

    /** Applique tout de suite une taille (annule la transition en cours). */
    fun setScaleNow(spider: SpiderBody, options: SpiderOptions, scale: Double) {
        scaleStepsLeft = 0
        applyScale(spider, options, scale)
    }

    // -------------------------------------------------------------------------
    // Boucle principale (appelée à chaque tick)
    // -------------------------------------------------------------------------

    fun tick(entity: ECSEntity, spider: SpiderBody, options: SpiderOptions, locomotion: Locomotion) {
        val cfg = Horror.config

        // ne rien faire si le chunk sous l'araignée n'est pas chargé (évite de forcer son chargement)
        val chunkX = Math.floor(spider.position.x).toInt() shr 4
        val chunkZ = Math.floor(spider.position.z).toInt() shr 4
        if (!spider.world.isChunkLoaded(chunkX, chunkZ)) return

        if (lookCount > 0 && ++lookDecay >= cfg.looksDecayTicks) {
            lookCount--
            lookDecay = 0
        }

        tickScale(spider, options, cfg)
        tickColor(entity.query<Cloak>(), cfg)

        val player = findTarget(spider, cfg)
        if (player == null) {
            options.gallop = false
            if (state != HorrorState.STALK) switchState(HorrorState.STALK, spider, options, null, cfg)
            idleTicks++
            if (autoSpawned && idleTicks > cfg.autoDespawnTicks) entity.remove()
            return
        }
        idleTicks = 0
        stateTicks++

        val playerPos = player.location.toVector()
        val toPlayer = playerPos.clone().subtract(spider.position)
        val distance = Vector(toPlayer.x, 0.0, toPlayer.z).length()
        val flat = if (distance < 1e-6) Vector(0.0, 0.0, 1.0) else Vector(toPlayer.x / distance, 0.0, toPlayer.z / distance)

        val footprint = options.bodyPlan.legs.maxOfOrNull { it.restPosition.horizontalLength() } ?: 1.0

        when (state) {
            HorrorState.STALK -> {
                if (calmTicks > 0) calmTicks--
                val closest = cfg.closestApproach + footprint

                // de très loin : elle avance vite ; de près : lentement
                farMode = if (farMode) distance > cfg.farDistance * 0.8 else distance > cfg.farDistance
                if (farMode) {
                    options.gallop = true
                    locomotion.walk(flat.clone().multiply(minOf(options.gait.maxSpeed, cfg.farSpeed)))
                } else {
                    options.gallop = false

                    // phases aléatoires : elle ralentit, parfois s'arrête net, puis repart
                    if (--speedPhaseTicks <= 0) {
                        speedPhaseTicks = cfg.stalkPhase.randomTicks()
                        speedFactor = if (Random.nextDouble() < cfg.stalkStopChance) 0.0
                        else cfg.stalkMinFactor + Random.nextDouble() * (1.0 - cfg.stalkMinFactor)
                    }

                    val speed = options.gait.maxSpeed * speedFactor
                    when {
                        distance > closest + 0.5 -> locomotion.walk(flat.clone().multiply(speed))
                        distance < closest - 1.0 -> locomotion.walk(flat.clone().multiply(-options.gait.maxSpeed * 0.5)) // recule
                    }
                }
                locomotion.face(flat)

                // bruits d'ambiance : sifflements qui viennent de sa direction
                if (cfg.soundsEnabled && --ambientTicks <= 0) {
                    ambientTicks = cfg.ambientInterval.randomTicks()
                    if (distance < 120.0) {
                        val where = player.location.clone().add(flat.clone().multiply(minOf(distance, 14.0)))
                        player.playSound(where, Sound.ENTITY_SPIDER_AMBIENT, 1.0f, 0.5f + Random.nextFloat() * 0.5f)
                    }
                }

                if (calmTicks <= 0 && isSeenBy(player, spider, cfg)) {
                    switchState(HorrorState.FREEZE, spider, options, player, cfg)
                }
            }

            HorrorState.FREEZE -> {
                options.gallop = false
                locomotion.face(flat) // reste figée, le "regard" fixé sur le joueur

                // si le joueur continue de la fixer, elle perd patience : charge dans 1 à 3 s
                if (isSeenBy(player, spider, cfg)) stareTicks++ else stareTicks = 0
                if (!rushed && stareTicks >= cfg.stareTicks) {
                    rushed = true
                    freezeDuration = minOf(freezeDuration, stateTicks + cfg.rushTime.randomTicks())
                }
                if (stateTicks >= freezeDuration) switchState(HorrorState.CHARGE, spider, options, player, cfg)
            }

            HorrorState.CHARGE -> {
                options.gallop = true

                // elle peut disparaître en pleine course (puis une autre réapparaît plus tard, voir auto-spawn)
                if (stateTicks > 20 && Random.nextDouble() < cfg.vanishChancePerSecond / 20.0) {
                    spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_DEATH, 2.0f, 0.6f)
                    entity.remove()
                    return
                }

                val speed = minOf(options.gait.maxSpeed, cfg.chargeSpeedCap)
                locomotion.walk(flat.clone().multiply(speed))
                locomotion.face(flat)

                val reach = cfg.attackRange + footprint * 0.5
                val heightGap = abs((playerPos.y + 0.9) - spider.position.y)
                val reachable = heightGap <= 1.8 + options.bodyPlan.scale

                if (distance <= reach && reachable) {
                    attack(player, spider, options, flat, cfg)
                    lookCount = 0
                    calmTicks = cfg.attackCalmTicks
                    switchState(HorrorState.STALK, spider, options, player, cfg)
                } else if (stateTicks > cfg.chargeMaxTicks) {
                    switchState(HorrorState.STALK, spider, options, player, cfg)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cible et détection
    // -------------------------------------------------------------------------

    private fun isValidTarget(player: Player, spider: SpiderBody, cfg: HorrorConfig): Boolean {
        if (!player.isOnline || player.isDead) return false
        if (player.world != spider.world) return false
        if (player.gameMode == GameMode.SPECTATOR) return false
        if (cfg.ignoreCreative && player.gameMode == GameMode.CREATIVE) return false
        return player.location.toVector().distanceSquared(spider.position) <= cfg.followRange * cfg.followRange
    }

    private fun findTarget(spider: SpiderBody, cfg: HorrorConfig): Player? {
        // pendant la charge on garde la même cible
        if (state == HorrorState.CHARGE) {
            val locked = targetId?.let { Bukkit.getPlayer(it) }
            if (locked != null && isValidTarget(locked, spider, cfg)) return locked
        }

        val best = spider.world.players
            .filter { isValidTarget(it, spider, cfg) }
            .minByOrNull { it.location.toVector().distanceSquared(spider.position) }

        targetId = best?.uniqueId
        return best
    }

    /** Le joueur "voit" l'araignée : elle est dans son champ de vision et sans bloc entre eux. */
    private fun isSeenBy(player: Player, spider: SpiderBody, cfg: HorrorConfig): Boolean {
        val eye = player.eyeLocation
        val toSpider = spider.position.clone().subtract(eye.toVector())
        val distance = toSpider.length()
        if (distance < 1e-6) return true
        if (distance > cfg.seeRange) return false

        val direction = toSpider.clone().multiply(1.0 / distance)
        val cosLimit = cos(Math.toRadians(cfg.seeAngle))
        if (eye.direction.dot(direction) < cosLimit) return false

        if (!cfg.requireLineOfSight) return true
        val blocked = spider.world.rayTraceBlocks(eye, direction, distance, FluidCollisionMode.NEVER, true)
        return blocked == null
    }

    // -------------------------------------------------------------------------
    // Changements d'état
    // -------------------------------------------------------------------------

    private fun switchState(new: HorrorState, spider: SpiderBody, options: SpiderOptions, player: Player?, cfg: HorrorConfig) {
        state = new
        stateTicks = 0
        colorDirty = true

        when (new) {
            HorrorState.FREEZE -> {
                freezeDuration = cfg.freezeTime.randomTicks()
                stareTicks = 0
                rushed = false

                // trop de regards récents : elle ne patiente plus, charge dans 1 à 3 s
                lookCount++
                if (lookCount > cfg.looksLimit) {
                    freezeDuration = cfg.rushTime.randomTicks()
                    rushed = true
                }
                spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_AMBIENT, 2.0f, 0.8f)
                if (cfg.soundsEnabled) player?.playSound(player.location, Sound.ENTITY_WARDEN_HEARTBEAT, 1.0f, 1.0f)
            }

            HorrorState.CHARGE -> {
                spiderSound(spider, options, cfg, Sound.ENTITY_RAVAGER_ROAR, 3.0f, 1.0f)
                if (cfg.scaleEnabled && cfg.scaleOnCharge) startScaleChange(spider, options, cfg, preferBigger = true)
            }

            HorrorState.STALK -> options.gallop = false
        }
    }

    private fun spiderSound(spider: SpiderBody, options: SpiderOptions, cfg: HorrorConfig, sound: Sound, volume: Float, basePitch: Float) {
        if (!cfg.soundsEnabled) return
        // grosse araignée = voix grave, petite = voix aiguë
        val scale = options.bodyPlan.scale.toFloat().coerceIn(0.4f, 4f)
        val pitch = (basePitch / scale).coerceIn(0.5f, 2.0f)
        spider.world.playSound(spider.position, sound, volume, pitch)
    }

    private fun attack(player: Player, spider: SpiderBody, options: SpiderOptions, direction: Vector, cfg: HorrorConfig) {
        spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_HURT, 2.0f, 0.7f)

        if (cfg.attackDamage > 0.0) player.damage(cfg.attackDamage)

        if (cfg.attackKnockback > 0.0) {
            val push = direction.clone().multiply(cfg.attackKnockback)
            push.y = 0.3
            player.velocity = player.velocity.add(push)
        }
    }

    // -------------------------------------------------------------------------
    // Taille aléatoire
    // -------------------------------------------------------------------------

    private fun applyScale(spider: SpiderBody, options: SpiderOptions, scale: Double) {
        if (abs(scale - options.bodyPlan.scale) < 1e-6) return
        options.setScale(scale)
        // recalcule les allures pour que les vitesses restent celles de la config (x échelle)
        HorrorSpiderFactory.configureGaits(options, Horror.config, options.bodyPlan.scale)
        spider.updateBodyPlan() // reconstruit les pattes avec les nouvelles longueurs
    }

    private fun tickScale(spider: SpiderBody, options: SpiderOptions, cfg: HorrorConfig) {
        if (!cfg.scaleEnabled) return

        if (scaleStepsLeft > 0) {
            if (--scaleStepDelay > 0) return

            val current = options.bodyPlan.scale
            // interpolation géométrique : chaque pas multiplie la taille par le même facteur
            val next = current * (targetScale / current).pow(1.0 / scaleStepsLeft)
            applyScale(spider, options, next)

            scaleStepsLeft--
            scaleStepDelay = cfg.scaleStepTicks
            return
        }

        if (--ticksUntilScaleChange <= 0) {
            startScaleChange(spider, options, cfg, preferBigger = false)
            ticksUntilScaleChange = cfg.scaleInterval.randomTicks()
        }
    }

    private fun startScaleChange(spider: SpiderBody, options: SpiderOptions, cfg: HorrorConfig, preferBigger: Boolean) {
        val current = options.bodyPlan.scale
        val hi = cfg.scaleMax
        val lo = if (preferBigger) maxOf(cfg.scaleMin, minOf(current * 1.3, hi)) else cfg.scaleMin

        // tirage log-uniforme : autant de chances de grandir que de rétrécir, en proportion
        var target = current
        for (attempt in 0 until 6) {
            val candidate = exp(ln(lo) + Random.nextDouble() * (ln(hi) - ln(lo)))
            target = candidate
            if (abs(ln(candidate / current)) >= 0.25) break // assez différent pour être remarqué
        }

        targetScale = target
        scaleStepsLeft = cfg.scaleSteps
        scaleStepDelay = 1

        if (cfg.soundsEnabled && !preferBigger) {
            // grave quand elle grandit, aigu quand elle rétrécit
            val pitch = if (target > current) 0.6f else 1.6f
            spider.world.playSound(spider.position, Sound.ENTITY_SPIDER_AMBIENT, 1.2f, pitch)
        }
    }

    // -------------------------------------------------------------------------
    // Couleurs
    // -------------------------------------------------------------------------

    private fun tickColor(cloak: Cloak?, cfg: HorrorConfig) {
        if (cloak == null || !cfg.colorsEnabled || !autoColor) return

        ticksUntilColorChange--
        if (!colorDirty && ticksUntilColorChange > 0) return
        colorDirty = false

        val palette = when (state) {
            HorrorState.STALK -> cfg.calmColors
            HorrorState.FREEZE -> cfg.alertColors
            HorrorState.CHARGE -> cfg.chargeColors
        }
        cloak.tint = palette.random()
        cloak.tintVariation = cfg.colorVariation

        // pendant la charge la couleur "clignote" plus vite
        ticksUntilColorChange = if (state == HorrorState.CHARGE) Random.nextInt(8, 25) else cfg.colorInterval.randomTicks()
    }
}
