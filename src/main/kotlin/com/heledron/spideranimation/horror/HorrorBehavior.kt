package com.heledron.spideranimation.horror

import com.heledron.spideranimation.spider.components.Cloak
import com.heledron.spideranimation.spider.components.Locomotion
import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.utilities.ecs.ECSComponent
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import com.heledron.spideranimation.utilities.horizontalLength
import com.heledron.spideranimation.utilities.isFiniteVector
import com.heledron.spideranimation.utilities.overloads.playSound
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.GameMode
import org.bukkit.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.time.Duration
import org.bukkit.util.Vector
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import com.heledron.spideranimation.utilities.events.runLater
import kotlin.math.sin
import kotlin.random.Random

enum class HorrorState {
    /** Marche lente vers le joueur. */
    STALK,

    /** Le joueur l'a vu : elle s'immobilise 1 à 10 s (aléatoire), puis charge. */
    FREEZE,

    /** Course vers le joueur. */
    CHARGE,

    /** Elle recule (parfois en sautant) en gardant le joueur en face. */
    RETREAT,

    /** Elle court se cacher derrière des blocs, attend, puis ressort (ou bondit). */
    HIDE,
}

class HorrorBehavior(var autoSpawned: Boolean = false, val mini: Boolean = false) : ECSComponent {
    var state = HorrorState.STALK; private set

    private var stateTicks = 0
    private var freezeDuration = 0
    private var calmTicks = 0
    private var idleTicks = 0
    private var targetId: UUID? = null
    private var age = 0                  // ticks de vie de l'araignée
    private var targetSwitchAt = 0       // à partir de quel âge elle peut changer de victime

    // taille
    private var targetScale = 1.0
    private var scaleStepsLeft = 0
    private var scaleStepDelay = 0
    // premier changement de taille tardif : elle reste petite un moment après son apparition
    private var ticksUntilScaleChange = SecondsRange(15.0, 30.0).randomTicks()

    private var cloak: Cloak? = null

    // fuite / cachette / sauts
    private var evadeCooldown = 0
    private var retreatTicksLeft = 0
    private var hidePoint: Vector? = null
    private var hideWaiting = false
    private var hideWaitTicks = 0
    private var jumpCooldown = 0
    private var freeCheckTicks = 0
    private val dread = HorrorDread()

    // regard insistant
    private var lookCount = 0
    private var lookDecay = 0
    private var stareTicks = 0
    private var rushed = false

    // approche
    private var farMode = false
    private var speedPhaseTicks = 0
    private var speedFactor = 1.0
    private var burst = false
    private var unseenTicks = 0
    private var ambientTicks = Horror.config.ambientInterval.randomTicks()

    // murs / plafonds
    private val sensor = SurfaceSensor()
    private var intendedDir: Vector? = null      // direction de marche voulue ce tick (pour le capteur de surface)
    private var goalPoint: Vector? = null        // le joueur (pour savoir s'il est en contrebas)
    private var climbing = false
    private var climbTicks = 0
    private var climbStartY = 0.0
    private var climbGain = 2.5                  // hauteur à gagner avant que l'escalade soit "finie"
    private var climbWall: Vector? = null
    private var climbCooldown = 0
    private var pounceCooldown = 0
    private var scratchTicks = Random.nextInt(20, 60)
    private var threadAnchor: Vector? = null
    private var threadTicks = 0

    // écarts latéraux quand elle est figée
    private var skitterTicks = 0
    private var skitterDir: Vector? = null

    // anticipation de la trajectoire du joueur
    private var lastPlayerPos: Vector? = null
    private val playerVel = Vector(0.0, 0.0, 0.0)

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

    /** Force l'escalade d'un mur (commande /horror climb). Renvoie false si aucun mur n'est à portée. */
    fun forceClimb(spider: SpiderBody, player: Player?): Boolean {
        val cfg = Horror.config
        val toward = if (player != null) {
            val d = player.location.toVector().subtract(spider.position)
            Vector(d.x, 0.0, d.z).let { if (it.lengthSquared() < 1e-6) Vector(0.0, 0.0, 1.0) else it.normalize() }
        } else Vector(0.0, 0.0, 1.0)
        climbCooldown = 0
        return startClimb(spider, toward, cfg)
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
        intendedDir = null
        goalPoint = null

        // une erreur ici ne doit jamais bloquer la boucle de tout le plugin (plus de rendu, plus d'apparition...)
        try {
            tickBehavior(entity, spider, options, locomotion)
            if (entity.scheduledForRemoval) return

            // capteur de surface : sol, mur ou plafond ? (le corps pivote ensuite doucement)
            spider.surfaceTurnRate = cfg.climbTurnRate * (if (options.gallop) 1.8 else 1.0)
            sensor.update(spider, options, intendedDir, goalPoint, cfg)
            sensor.takeWallContact()?.let { beginAssistClimb(spider, it) }
            tickThread(spider, cfg)
        } catch (e: Exception) {
            recoverFromError(spider, e)
        }
    }

    private var errorCount = 0

    private fun recoverFromError(spider: SpiderBody, e: Exception) {
        if (errorCount++ < 5) Horror.plugin.logger.warning("Erreur dans l'IA de l'araignée : $e (remise au sol)")
        climbing = false
        climbWall = null
        threadAnchor = null
        skitterTicks = 0
        sensor.reset()
        spider.targetSurfaceUp.setX(0.0).setY(1.0).setZ(0.0)
    }

    /** Elle a heurté un mur en marchant vers le joueur : elle l'escalade jusqu'en haut au lieu de rebondir dessus. */
    private fun beginAssistClimb(spider: SpiderBody, wallNormal: Vector) {
        if (climbing) return
        val into = Vector(-wallNormal.x, 0.0, -wallNormal.z)
        if (into.lengthSquared() < 1e-6) return
        climbing = true
        climbWall = into.normalize()
        climbTicks = 200
        climbStartY = spider.position.y
        climbGain = 0.8
    }

    private fun tickBehavior(entity: ECSEntity, spider: SpiderBody, options: SpiderOptions, locomotion: Locomotion) {
        val cfg = Horror.config

        // ne rien faire si le chunk sous l'araignée n'est pas chargé (évite de forcer son chargement)
        val chunkX = Math.floor(spider.position.x).toInt() shr 4
        val chunkZ = Math.floor(spider.position.z).toInt() shr 4
        if (!spider.world.isChunkLoaded(chunkX, chunkZ)) return

        if (lookCount > 0 && ++lookDecay >= cfg.looksDecayTicks) {
            lookCount--
            lookDecay = 0
        }

        if (evadeCooldown > 0) evadeCooldown--
        if (jumpCooldown > 0) jumpCooldown--
        if (climbCooldown > 0) climbCooldown--
        if (pounceCooldown > 0) pounceCooldown--

        // jamais dans un bloc : si elle s'y retrouve (apparition contre un mur, saut...), on la replace
        if (--freeCheckTicks <= 0) {
            freeCheckTicks = 5
            if (HorrorPlacement.ensureFree(entity, spider, options)) hidePoint = null
        }

        tickScale(spider, options, cfg)
        cloak = entity.query<Cloak>()
        tickColor(cloak, cfg)

        age++
        val player = findTarget(spider, cfg)
        if (player == null) {
            options.gallop = false
            lastPlayerPos = null
            if (state != HorrorState.STALK) switchState(HorrorState.STALK, spider, options, null, cfg)
            idleTicks++
            if (autoSpawned && idleTicks > cfg.autoDespawnTicks) entity.remove()
            return
        }
        idleTicks = 0
        stateTicks++

        val playerPos = player.location.toVector()
        // la mini-araignée vise le visage
        val chest = playerPos.clone().add(Vector(0.0, if (mini) 1.5 else 0.9, 0.0))
        goalPoint = chest
        trackPlayer(playerPos)

        val toPlayer = playerPos.clone().subtract(spider.position)
        val toChest = chest.clone().subtract(spider.position)
        val d3 = toChest.length()
        val onFloor = spider.targetSurfaceUp.y > 0.7
        val flatDistance = Vector(toPlayer.x, 0.0, toPlayer.z).length()
        // au sol : distance horizontale (comme avant) ; sur un mur ou au plafond : distance réelle
        val distance = if (onFloor) flatDistance else d3
        // direction vers le joueur, tangente à la surface sur laquelle elle marche
        val flat = surfaceDirection(toChest, spider)
        val moveDir = if (climbing) climbDirection(spider, flat) else flat

        val footprint = options.bodyPlan.legs.maxOfOrNull { it.restPosition.horizontalLength() } ?: 1.0

        if (cfg.dreadEnabled) dread.tick(player, spider, options, state, distance, flat, isSeenBy(player, spider, cfg), cfg)

        tickClimb(spider, cfg)
        if (!onFloor || mini) scratch(spider, options, cfg, distance)

        when (state) {
            HorrorState.STALK -> {
                if (calmTicks > 0) calmTicks--
                val closest = if (mini) cfg.miniClosest else cfg.closestApproach + footprint
                val seen = isSeenBy(player, spider, cfg)
                if (seen) unseenTicks = 0 else unseenTicks++

                // bond depuis le mur / le plafond : par surprise (elle n'est pas vue) ou quand elle est déjà tout près
                if (!onFloor && (!seen || d3 < (if (mini) cfg.miniPounceDistance else cfg.dropDistance)) && tryPounce(spider, options, chest, d3, cfg)) {
                    switchState(HorrorState.CHARGE, spider, options, player, cfg)
                    return
                }

                // hors de vue, elle peut décider d'aller escalader un mur pour t'approcher d'en haut
                if (cfg.climbEnabled && !climbing && climbCooldown <= 0 && onFloor && !seen &&
                    distance > cfg.climbSeekMin && distance < cfg.climbSeekMax &&
                    Random.nextDouble() < cfg.climbSeekChance / 20.0
                ) {
                    startClimb(spider, flat, cfg)
                }

                // de très loin : elle avance vite ; de près : lentement
                farMode = if (farMode) distance > cfg.farDistance * 0.8 else distance > cfg.farDistance
                if (farMode) {
                    options.gallop = true
                    go(locomotion, moveDir, minOf(options.gait.maxSpeed, cfg.farSpeed))
                } else if (burst && !seen && distance > closest + 3.0) {
                    // petit sprint quand le joueur ne regarde pas
                    options.gallop = true
                    go(locomotion, moveDir, minOf(options.gait.maxSpeed, cfg.burstSpeed))
                } else {
                    options.gallop = false

                    // phases aléatoires : elle ralentit, parfois s'arrête net, puis repart
                    if (--speedPhaseTicks <= 0) {
                        speedPhaseTicks = cfg.stalkPhase.randomTicks()
                        burst = Random.nextDouble() < (if (mini) 0.6 else cfg.burstChance)
                        speedFactor = if (Random.nextDouble() < cfg.stalkStopChance) 0.0
                        else cfg.stalkMinFactor + Random.nextDouble() * (1.0 - cfg.stalkMinFactor)
                    }

                    val speed = options.gait.maxSpeed * speedFactor
                    when {
                        distance > closest + 0.5 -> go(locomotion, moveDir, speed)
                        distance < closest - 1.0 -> go(locomotion, moveDir, -options.gait.maxSpeed * 0.5) // recule
                    }
                }
                locomotion.face(moveDir)

                // bruits d'ambiance : sifflements qui viennent de sa direction
                if (cfg.soundsEnabled && --ambientTicks <= 0) {
                    ambientTicks = cfg.ambientInterval.randomTicks()
                    if (distance < 120.0) {
                        val where = player.location.clone().add(flat.clone().multiply(minOf(distance, 14.0)))
                        player.playSound(where, Sound.ENTITY_SPIDER_AMBIENT, 1.0f, 0.5f + Random.nextFloat() * 0.5f)
                    }
                }

                // embuscade : si le joueur ne la voit plus depuis un moment, elle réapparaît dans son dos (parfois au plafond)
                if (!seen && distance > 25.0 && unseenTicks > cfg.ambushUnseenTicks &&
                    Random.nextDouble() < cfg.ambushChancePerSecond / 20.0 &&
                    ambush(entity, spider, options, player, cfg)
                ) {
                    unseenTicks = 0
                    return
                }

                if (calmTicks <= 0 && seen) {
                    val roll = Random.nextDouble()
                    val canEvade = cfg.evadeEnabled && evadeCooldown <= 0 && distance > 4.0 && onFloor
                    when {
                        canEvade && roll < cfg.hideChance ->
                            switchState(HorrorState.HIDE, spider, options, player, cfg)
                        canEvade && roll < cfg.hideChance + cfg.retreatChance ->
                            switchState(HorrorState.RETREAT, spider, options, player, cfg)
                        else -> switchState(HorrorState.FREEZE, spider, options, player, cfg)
                    }
                }
            }

            HorrorState.FREEZE -> {
                options.gallop = false
                locomotion.face(flat) // reste figée, le "regard" fixé sur le joueur

                // de brusques écarts latéraux, sans jamais cesser de te fixer
                if (skitterTicks > 0) {
                    skitterTicks--
                    options.gallop = true
                    val side = skitterDir
                    if (side != null) go(locomotion, side, minOf(options.gallopGait.maxSpeed, 0.2 * options.bodyPlan.scale))
                } else if (cfg.skitterChance > 0.0 &&
                    Random.nextDouble() < cfg.skitterChance * (if (mini) 3.0 else 1.0) / 20.0
                ) {
                    startSkitter(spider, flat)
                }

                // si le joueur continue de la fixer, elle perd patience : charge dans 1 à 3 s
                if (isSeenBy(player, spider, cfg)) stareTicks++ else stareTicks = 0
                if (!rushed && stareTicks >= cfg.stareTicks) {
                    rushed = true
                    freezeDuration = minOf(freezeDuration, stateTicks + cfg.rushTime.randomTicks())
                }
                if (stateTicks >= freezeDuration) switchState(HorrorState.CHARGE, spider, options, player, cfg)
            }

            HorrorState.RETREAT -> {
                options.gallop = true
                go(locomotion, flat, -minOf(options.gait.maxSpeed, cfg.retreatSpeed))
                locomotion.face(flat) // recule sans quitter le joueur des yeux
                if (--retreatTicksLeft <= 0 || distance > cfg.followRange * 0.9) {
                    calmTicks = cfg.evadeCalmTicks
                    switchState(HorrorState.STALK, spider, options, player, cfg)
                }
            }

            HorrorState.HIDE -> tickHide(entity, spider, options, locomotion, player, flat, distance, footprint, cfg)

            HorrorState.CHARGE -> {
                options.gallop = true
                climbing = false

                // elle peut disparaître en pleine course (puis une autre réapparaît plus tard, voir auto-spawn)
                if (stateTicks > 20 && Random.nextDouble() < cfg.vanishChancePerSecond / 20.0) {
                    spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_DEATH, 2.0f, 0.6f)
                    entity.remove()
                    return
                }

                // elle vise où tu vas, pas où tu es
                val aim = chargeAim(chest, d3, options, cfg)
                val chargeDir = surfaceDirection(aim.clone().subtract(spider.position), spider)
                val speed = minOf(options.gait.maxSpeed, cfg.chargeSpeedCap)
                go(locomotion, chargeDir, speed)
                locomotion.face(chargeDir)

                if (!onFloor) {
                    // sur un mur / au plafond : elle se jette sur le joueur dès qu'il est à portée
                    tryPounce(spider, options, aim, d3, cfg)
                } else if (cfg.jumpEnabled && jumpCooldown <= 0 && distance < cfg.lungeDistance && distance > (if (mini) 1.2 else 2.5) &&
                    Random.nextDouble() < cfg.lungeChance
                ) {
                    // bond final sur le joueur
                    hop(spider, options, cfg, flat, 1.3)
                }

                val reach = if (mini) cfg.miniAttackRange else cfg.attackRange + footprint * 0.5
                val closeEnough = if (onFloor && spider.airborneTicks <= 0) {
                    val heightGap = abs((playerPos.y + 0.9) - spider.position.y)
                    distance <= reach && heightGap <= 1.8 + options.bodyPlan.scale
                } else {
                    d3 <= reach + 0.6
                }

                if (closeEnough) {
                    attack(entity, player, spider, options, flat, cfg)
                    // en multijoueur : elle passe souvent à une autre victime après avoir attaqué
                    if (Random.nextDouble() < cfg.switchAfterAttackChance) targetSwitchAt = 0
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
    // Murs, plafonds, bonds
    // -------------------------------------------------------------------------

    /** Direction vers [toTarget] projetée sur la surface où elle marche (au sol : l'horizontale, comme avant). */
    private fun surfaceDirection(toTarget: Vector, spider: SpiderBody): Vector {
        val up = spider.surfaceUp
        val tangent = toTarget.clone().subtract(up.clone().multiply(toTarget.dot(up)))
        if (tangent.isFiniteVector() && tangent.lengthSquared() > 1e-6) return tangent.normalize()

        // cible pile au-dessus / en dessous : on garde le cap actuel
        val forward = spider.forwardDirection()
        val heading = forward.subtract(up.clone().multiply(forward.dot(up)))
        if (heading.isFiniteVector() && heading.lengthSquared() > 1e-6) return heading.normalize()
        return Vector(0.0, 0.0, 1.0)
    }

    /** Fait marcher l'araignée dans [dir] (négatif = recule) et prévient le capteur de surface de la direction voulue. */
    private fun go(locomotion: Locomotion, dir: Vector, speed: Double) {
        if (!dir.isFiniteVector() || !speed.isFinite()) return
        locomotion.walk(dir.clone().multiply(speed * sensor.speedFactor))
        if (abs(speed) > 0.005) {
            intendedDir = if (speed >= 0.0) dir.clone() else dir.clone().multiply(-1.0)
        }
    }

    private fun trackPlayer(pos: Vector) {
        val last = lastPlayerPos
        if (last != null && last.distanceSquared(pos) < 25.0) {
            val step = pos.clone().subtract(last)
            playerVel.x = playerVel.x * 0.7 + step.x * 0.3
            playerVel.y = playerVel.y * 0.7 + step.y * 0.3
            playerVel.z = playerVel.z * 0.7 + step.z * 0.3
        } else {
            playerVel.setX(0.0).setY(0.0).setZ(0.0)
        }
        lastPlayerPos = pos.clone()
    }

    /** Point visé pendant la charge : légèrement devant le joueur s'il court, pour lui couper la route. */
    private fun chargeAim(chest: Vector, d3: Double, options: SpiderOptions, cfg: HorrorConfig): Vector {
        if (!cfg.predictiveCharge) return chest
        val horizontal = Vector(playerVel.x, 0.0, playerVel.z)
        if (horizontal.length() < 0.03) return chest

        val speedPerTick = maxOf(minOf(options.gait.maxSpeed, cfg.chargeSpeedCap), 0.05)
        val leadTicks = minOf(d3 / speedPerTick, 30.0) * 0.6
        val offset = horizontal.multiply(leadTicks)
        if (offset.length() > 8.0) offset.normalize().multiply(8.0)
        return chest.clone().add(offset)
    }

    private fun startSkitter(spider: SpiderBody, towardPlayer: Vector) {
        val side = spider.surfaceUp.clone().crossProduct(towardPlayer)   // perpendiculaire, dans le plan de la surface
        if (!side.isFiniteVector() || side.lengthSquared() < 1e-6) return
        side.normalize()
        if (Random.nextBoolean()) side.multiply(-1.0)
        skitterDir = side
        skitterTicks = Random.nextInt(3, 7)
    }

    private fun scratch(spider: SpiderBody, options: SpiderOptions, cfg: HorrorConfig, distance: Double) {
        if (!cfg.scratchSounds) return
        if (--scratchTicks > 0) return
        if (mini) {
            // petits trottinements rapides, seulement quand elle est assez près pour les entendre
            scratchTicks = Random.nextInt(6, 24)
            if (distance < 18.0) spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_STEP, 0.9f, 1.3f)
            return
        }
        scratchTicks = Random.nextInt(25, 70)
        spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_STEP, 1.2f, 0.7f)
    }

    // --- escalade volontaire -------------------------------------------------

    private fun startClimb(spider: SpiderBody, toward: Vector, cfg: HorrorConfig): Boolean {
        if (!cfg.climbEnabled) return false
        val wall = findWallDirection(spider, toward, cfg) ?: return false
        climbing = true
        climbWall = wall
        climbTicks = cfg.climbSeekTicks
        climbStartY = spider.position.y
        climbGain = 2.5
        return true
    }

    private fun stopClimb(cfg: HorrorConfig) {
        climbing = false
        climbWall = null
        climbCooldown = cfg.climbCooldownTicks
    }

    private fun tickClimb(spider: SpiderBody, cfg: HorrorConfig) {
        if (!climbing) return
        climbTicks--
        val up = spider.targetSurfaceUp
        val onCeiling = up.y < -0.7
        val arrivedHigh = up.y > 0.7 && spider.position.y > climbStartY + climbGain
        if (climbTicks <= 0 || onCeiling || arrivedHigh) stopClimb(cfg)
    }

    /** Direction de marche pendant l'escalade : vers le mur, puis tout droit vers le haut, puis vers le joueur au plafond. */
    private fun climbDirection(spider: SpiderBody, toward: Vector): Vector {
        val up = spider.surfaceUp
        return when {
            // au sol : vers le mur, mais une fois en haut (déjà monté un peu) vers le joueur
            up.y > 0.7 -> if (spider.position.y > climbStartY + 0.4) toward else (climbWall?.clone() ?: toward)
            up.y < -0.7 -> toward
            else -> {
                // le haut du monde projeté sur le mur
                val along = Vector(0.0, 1.0, 0.0).subtract(up.clone().multiply(up.y))
                if (along.lengthSquared() < 1e-6) toward else along.normalize()
            }
        }
    }

    /** Mur vertical le plus proche (de préférence dans la direction du joueur). */
    private fun findWallDirection(spider: SpiderBody, toward: Vector, cfg: HorrorConfig): Vector? {
        val world = spider.world
        val from = spider.position.clone()
        var best: Vector? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until 12) {
            val angle = i * Math.PI / 6
            val dir = Vector(cos(angle), 0.0, sin(angle))
            val hit = castSurface(world, from, dir, cfg.climbWallRange) ?: continue
            if (abs(hit.normal.y) > 0.1) continue
            val score = hit.distance - 6.0 * dir.dot(toward)
            if (score < bestScore) {
                bestScore = score
                best = dir
            }
        }
        return best
    }

    // --- bond depuis un mur / le plafond --------------------------------------

    /**
     * Se jette sur [aim] (trajectoire balistique calculée pour y arriver). Renvoie true si le bond est parti.
     * Depuis le plafond, un fil de soie reste accroché derrière elle.
     */
    private fun tryPounce(spider: SpiderBody, options: SpiderOptions, aim: Vector, d3: Double, cfg: HorrorConfig): Boolean {
        if (!cfg.pounceEnabled || pounceCooldown > 0) return false
        if (d3 < (if (mini) 0.8 else 1.5) || d3 > (if (mini) cfg.miniPounceDistance else cfg.pounceDistance)) return false
        if (spider.airborneTicks > 0 || spider.legs.none { it.isGrounded() }) return false

        val from = spider.position
        val delta = aim.clone().subtract(from)
        val dist = delta.length()
        if (dist < 1e-6 || !dist.isFinite()) return false

        // ligne de vue dégagée jusqu'à la cible
        val blocked = spider.world.rayTraceBlocks(
            from.toLocation(spider.world), delta.clone().multiply(1.0 / dist), dist, FluidCollisionMode.NEVER, true,
        )
        if (blocked != null && blocked.hitPosition.distance(from) < dist - 0.6) return false

        val flightTicks = ceil(dist / 0.55).toInt().coerceIn(4, 30)
        val gravity = options.gait.gravityAcceleration
        val dragCompensation = 1.0 / (1.0 - options.gait.airDragCoefficient * (flightTicks + 1) / 2.0).coerceAtLeast(0.5)
        val velocity = Vector(
            delta.x / flightTicks,
            delta.y / flightTicks + gravity * (flightTicks + 1) / 2.0,
            delta.z / flightTicks,
        ).multiply(dragCompensation)

        val fromCeiling = spider.surfaceUp.y < -0.6
        if (!spider.leap(velocity, flightTicks + 8)) return false

        pounceCooldown = cfg.pounceCooldownTicks
        if (fromCeiling) {
            threadAnchor = from.clone()
            threadTicks = flightTicks + 40
        }
        spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_HURT, 1.6f, 0.5f)
        return true
    }

    /** Fil de soie tendu entre le point de départ du bond (au plafond) et l'araignée. */
    private fun tickThread(spider: SpiderBody, cfg: HorrorConfig) {
        val anchor = threadAnchor ?: return
        if (!cfg.threadEnabled || threadTicks-- <= 0) {
            threadAnchor = null
            return
        }
        val to = spider.position
        val length = anchor.distance(to)
        if (length < 0.5 || length > 40.0) return

        val world = spider.world
        val steps = (length / 0.35).toInt().coerceIn(2, 60)
        val dust = Particle.DustOptions(Color.fromRGB(235, 235, 235), 0.5f)
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val point = Location(
                world,
                anchor.x + (to.x - anchor.x) * t,
                anchor.y + (to.y - anchor.y) * t,
                anchor.z + (to.z - anchor.z) * t,
            )
            world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust)
        }
    }

    // -------------------------------------------------------------------------
    // Cachette et sauts
    // -------------------------------------------------------------------------

    /** Saut dans la direction [horizontal] (plan horizontal). [strength] multiplie la puissance. */
    private fun hop(spider: SpiderBody, options: SpiderOptions, cfg: HorrorConfig, horizontal: Vector, strength: Double = 1.0) {
        if (!cfg.jumpEnabled) return
        if (spider.targetSurfaceUp.y < 0.7) return   // sur un mur / au plafond : voir tryPounce
        if (spider.legs.none { it.isGrounded() }) return
        val scale = options.bodyPlan.scale.coerceIn(0.4, 4.0)
        val h = Vector(horizontal.x, 0.0, horizontal.z)
        if (h.lengthSquared() > 1e-8) h.normalize().multiply(cfg.jumpForward * strength)
        val impulse = Vector(h.x, cfg.jumpPower * sqrt(scale) * strength, h.z)
        if (spider.jump(impulse)) {
            jumpCooldown = cfg.jumpCooldownTicks
            spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_AMBIENT, 1.5f, 1.8f)
        }
    }

    private fun tickHide(
        entity: ECSEntity, spider: SpiderBody, options: SpiderOptions, locomotion: Locomotion,
        player: Player, flat: Vector, distance: Double, footprint: Double, cfg: HorrorConfig,
    ) {
        val point = hidePoint
        if (point == null) {
            calmTicks = cfg.evadeCalmTicks
            switchState(HorrorState.STALK, spider, options, player, cfg)
            return
        }

        if (!hideWaiting) {
            // course vers la cachette
            options.gallop = true
            val toPoint = point.clone().subtract(spider.position)
            toPoint.y = 0.0
            val dp = toPoint.length()
            if (dp > 1e-6) {
                val dir = toPoint.multiply(1.0 / dp)
                locomotion.walk(dir.clone().multiply(minOf(options.gait.maxSpeed, cfg.hideSpeed)))
                locomotion.face(dir)
            }
            if (dp < 0.8 + footprint * 0.3 || stateTicks > cfg.hideMaxTravelTicks) {
                hideWaiting = true
                hideWaitTicks = cfg.hideWait.randomTicks()
                options.gallop = false
            }
            return
        }

        // cachée : immobile, elle guette
        options.gallop = false
        locomotion.face(flat)

        if (isSeenBy(player, spider, cfg)) {
            // repérée dans sa cachette : elle se fige (ou bondit)
            hidePoint = null
            val next = if (Random.nextDouble() < cfg.hidePounceWhenSeenChance) HorrorState.CHARGE else HorrorState.FREEZE
            switchState(next, spider, options, player, cfg)
            return
        }

        // le joueur passe près de la cachette : elle bondit
        if (distance < cfg.hideAmbushDistance && Random.nextDouble() < cfg.hideAmbushChancePerSecond / 20.0) {
            hidePoint = null
            switchState(HorrorState.CHARGE, spider, options, player, cfg)
            return
        }

        if (--hideWaitTicks <= 0) {
            hidePoint = null
            calmTicks = cfg.evadeCalmTicks
            switchState(HorrorState.STALK, spider, options, player, cfg)
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

        val valid = spider.world.players.filter { isValidTarget(it, spider, cfg) }
        if (valid.isEmpty()) {
            targetId = null
            return null
        }

        // on garde la victime actuelle tant que son temps n'est pas écoulé (ou s'il n'y a qu'un joueur)
        val current = targetId?.let { id -> valid.firstOrNull { it.uniqueId == id } }
        val canSwitch = age >= targetSwitchAt && valid.size > 1 && state == HorrorState.STALK
        if (current != null && !canSwitch) return current

        // nouvelle victime : tirage pondéré, en évitant la précédente et ceux visés récemment
        val next = HorrorVictims.pick(valid, targetId, cfg.victimMemorySeconds, spider.position) ?: return current
        if (next.uniqueId != targetId) {
            unseenTicks = 0
            stareTicks = 0
            lookCount = 0
        }
        targetId = next.uniqueId
        targetSwitchAt = age + Random.nextInt(cfg.targetSwitchMinTicks, cfg.targetSwitchMaxTicks + 1)
        HorrorVictims.mark(next)
        return next
    }

    /** Le joueur "voit" l'araignée : elle est dans son champ de vision et sans bloc entre eux. */
    private fun isSeenBy(player: Player, spider: SpiderBody, cfg: HorrorConfig): Boolean {
        val eye = player.eyeLocation
        val toSpider = spider.position.clone().subtract(eye.toVector())
        val distance = toSpider.length()
        if (distance < 1e-6) return true
        // une toute petite araignée se repère de bien moins loin
        if (distance > cfg.seeRange * (if (mini) cfg.miniSeeRangeFactor else 1.0)) return false

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
        if (new == HorrorState.HIDE) {
            // pas de cachette trouvée -> elle recule à la place
            val cover = if (player == null || spider.targetSurfaceUp.y < 0.7) null else HorrorPlacement.findCover(
                spider.world, spider.position, player.eyeLocation.toVector(), options.bodyPlan.scale,
                cfg.hideMinPlayerDistance, cfg.hideSearchRadius,
            )
            if (cover == null) {
                switchState(HorrorState.RETREAT, spider, options, player, cfg)
                return
            }
            hidePoint = cover
            hideWaiting = false
        }

        state = new
        stateTicks = 0
        colorDirty = true

        // camouflage : elle reste fondue dans le décor dans TOUS les états (y compris la charge).
        // Les couleurs d'état (calme / alerte / charge) ne servent que si le camouflage est coupé.
        val c = cloak
        if (c != null && cfg.camouflageEnabled) c.active = true

        when (new) {
            HorrorState.FREEZE -> {
                if (player != null) dread.onFreeze(player, cfg)
                freezeDuration = cfg.freezeTime.randomTicks()
                stareTicks = 0
                rushed = false

                // quand on la regarde, elle peut gonfler sous tes yeux
                if (cfg.scaleEnabled && Random.nextDouble() < cfg.growWhenSeenChance) {
                    startScaleChange(spider, options, cfg, preferBigger = true)
                }

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
                climbing = false
                if (player != null) dread.onCharge(player, cfg)
                spiderSound(spider, options, cfg, Sound.ENTITY_RAVAGER_ROAR, 3.0f, 1.0f)
                if (cfg.scaleEnabled && cfg.scaleOnCharge) startScaleChange(spider, options, cfg, preferBigger = true)
            }

            HorrorState.RETREAT -> {
                evadeCooldown = cfg.evadeCooldown.randomTicks()
                retreatTicksLeft = cfg.retreatTime.randomTicks()
                spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_HURT, 1.2f, 1.4f)
                if (Random.nextDouble() < cfg.retreatHopChance) {
                    val away = Vector(0.0, 0.0, 0.0)
                    if (player != null) away.copy(spider.position).subtract(player.location.toVector())
                    hop(spider, options, cfg, away, 0.9)   // saute en arrière
                }
            }

            HorrorState.HIDE -> {
                evadeCooldown = cfg.evadeCooldown.randomTicks()
                spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_AMBIENT, 1.5f, 1.5f)
                val p = hidePoint
                if (p != null && Random.nextDouble() < cfg.hideLeapChance) {
                    hop(spider, options, cfg, p.clone().subtract(spider.position), 1.1)   // bond vers la cachette
                }
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

    private fun attack(entity: ECSEntity, player: Player, spider: SpiderBody, options: SpiderOptions, direction: Vector, cfg: HorrorConfig) {
        spiderSound(spider, options, cfg, Sound.ENTITY_SPIDER_HURT, 2.0f, 0.7f)
        if (cfg.screamerEnabled) screamer(entity, player, spider, options, cfg)
        dread.onAttack(player, cfg)   // après : le noir et la nausée durent plus longtemps que ceux du screamer

        if (cfg.attackDamage > 0.0) player.damage(cfg.attackDamage * (if (mini) cfg.miniDamageFactor else 1.0))

        if (cfg.attackKnockback > 0.0) {
            // recul horizontal, même si elle arrive d'en haut
            val horizontal = Vector(direction.x, 0.0, direction.z)
            if (horizontal.lengthSquared() < 1e-6) horizontal.copy(player.location.direction.setY(0.0).multiply(-1.0))
            if (horizontal.lengthSquared() > 1e-6) horizontal.normalize()
            val push = horizontal.multiply(cfg.attackKnockback)
            push.y = 0.3
            player.velocity = player.velocity.add(push)
        }
    }

    /** Se téléporte dans le dos du joueur (20 à 35 blocs), là où il ne regarde pas. */
    private fun ambush(entity: ECSEntity, spider: SpiderBody, options: SpiderOptions, player: Player, cfg: HorrorConfig): Boolean {
        val look = player.location.direction.clone()
        look.y = 0.0
        if (look.lengthSquared() < 1e-6) return false
        look.normalize()

        // parfois elle réapparaît accrochée au plafond, derrière toi
        if (cfg.climbEnabled && Random.nextDouble() < cfg.ceilingAmbushChance &&
            ambushOnCeiling(entity, spider, options, player, look)
        ) return true

        // derrière lui, avec +/- 40 degrés
        val angle = Math.PI + (Random.nextDouble() - 0.5) * Math.toRadians(80.0)
        val dx = look.x * cos(angle) - look.z * sin(angle)
        val dz = look.x * sin(angle) + look.z * cos(angle)
        val distance = cfg.ambushMinDistance + Random.nextDouble() * (cfg.ambushMaxDistance - cfg.ambushMinDistance)

        val bx = Math.floor(player.location.x + dx * distance).toInt()
        val bz = Math.floor(player.location.z + dz * distance).toInt()
        val world = spider.world
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) return false

        val ground = HorrorPlacement.findSurface(world, bx + 0.5, bz + 0.5, options.bodyPlan.scale) ?: return false
        val target = ground.clone().add(Vector(0.0, options.walkGait.stationary.bodyHeight, 0.0))
        spider.teleport(entity, target)
        return true
    }

    /** Apparaît au plafond (5 à 14 blocs derrière le joueur, plafond à 2,5 blocs de haut minimum). */
    private fun ambushOnCeiling(entity: ECSEntity, spider: SpiderBody, options: SpiderOptions, player: Player, look: Vector): Boolean {
        val world = spider.world
        val bodyHeight = options.gait.stationary.bodyHeight
        val down = Vector(0.0, -1.0, 0.0)
        val upward = Vector(0.0, 1.0, 0.0)

        repeat(8) {
            val angle = Math.PI + (Random.nextDouble() - 0.5) * Math.toRadians(100.0)
            val dx = look.x * cos(angle) - look.z * sin(angle)
            val dz = look.x * sin(angle) + look.z * cos(angle)
            val distance = 5.0 + Random.nextDouble() * 9.0
            val x = player.location.x + dx * distance
            val z = player.location.z + dz * distance
            if (!world.isChunkLoaded(Math.floor(x).toInt() shr 4, Math.floor(z).toInt() shr 4)) return@repeat

            val start = Vector(x, player.location.y + 1.0, z)
            if (!world.getBlockAt(Math.floor(x).toInt(), Math.floor(start.y).toInt(), Math.floor(z).toInt()).isPassable) return@repeat

            val ceiling = castSurface(world, start, upward, 10.0) ?: return@repeat
            if (ceiling.normal.y > -0.9) return@repeat
            if (ceiling.distance < 2.5 + bodyHeight) return@repeat

            val spot = Vector(x, ceiling.point.y - bodyHeight, z)
            val toPlayer = player.location.toVector().subtract(spot)
            spider.teleportToSurface(spot, down, toPlayer)
            return true
        }
        return false
    }

    /** Tourne la caméra du joueur vers l'araignée (il ne peut pas détourner les yeux). */
    private fun forceLook(player: Player, spider: SpiderBody, options: SpiderOptions) {
        val target = spider.position.clone().add(Vector(0.0, options.bodyPlan.scale * 0.2, 0.0))
        val d = target.subtract(player.eyeLocation.toVector())
        if (d.lengthSquared() < 1e-6) return
        val aim = player.eyeLocation.clone()
        aim.direction = d
        player.setRotation(aim.yaw, aim.pitch)
    }

    private val glitchChars = "▓▒░█▌▐╬☠ᛉ¤§Ж"

    private fun glitchString(length: Int): String = buildString { repeat(length) { append(glitchChars.random()) } }

    /**
     * Screamer en plusieurs temps :
     *  0 tick  : silence total, caméra forcée sur elle, joueur figé, elle devient énorme
     *  2 ticks : hurlements superposés + flash blanc/rouge qui clignote
     *  12 ticks: second cri, nausée
     *  20-36   : grattements et cœur qui s'emballe
     *  45      : murmure brouillé
     *  puis, parfois, elle disparaît dans le noir.
     */
    private fun screamer(entity: ECSEntity, player: Player, spider: SpiderBody, options: SpiderOptions, cfg: HorrorConfig) {
        val sounds = cfg.soundsEnabled
        val world = spider.world

        fun later(delay: Long, task: () -> Unit) {
            runLater(delay) { if (player.isOnline && !player.isDead) task() }
        }
        fun effect(type: PotionEffectType, ticks: Int, amplifier: Int = 0) {
            player.addPotionEffect(PotionEffect(type, ticks, amplifier, false, false, false))
        }
        fun flash(color: NamedTextColor, text: String, subtitle: Component = Component.empty()) {
            player.showTitle(
                Title.title(
                    Component.text(text, color),
                    subtitle,
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(250), Duration.ofMillis(150)),
                )
            )
        }

        // --- t = 0 : le silence, puis elle est là ---
        if (sounds && cfg.screamerSilence) player.stopAllSounds()
        if (cfg.screamerForceLook) forceLook(player, spider, options)
        if (cfg.screamerFreezeTicks > 0) effect(PotionEffectType.SLOWNESS, cfg.screamerFreezeTicks, 4)
        effect(PotionEffectType.DARKNESS, cfg.screamerDarkTicks)

        if (cfg.screamerGrow && cfg.scaleEnabled) {
            setScaleNow(spider, options, cfg.scaleMax)
            ticksUntilScaleChange = SecondsRange(3.0, 6.0).randomTicks() // elle rétrécit de nouveau peu après
        }

        // --- t = 2 : le cri ---
        later(2) {
            if (sounds) {
                player.playSound(player.location, Sound.ENTITY_GHAST_SCREAM, 2.0f, 0.5f)
                player.playSound(player.location, Sound.ENTITY_WARDEN_ROAR, 2.0f, 1.0f)
                player.playSound(player.location, Sound.ENTITY_ENDERMAN_SCREAM, 2.0f, 0.6f)
                player.playSound(player.location, Sound.ENTITY_RAVAGER_ROAR, 2.0f, 0.7f)
                player.playSound(player.location, Sound.ENTITY_SPIDER_AMBIENT, 2.0f, 0.5f)
            }
            effect(PotionEffectType.BLINDNESS, cfg.screamerBlindTicks)
            flash(NamedTextColor.WHITE, "☠")
        }

        // --- flash qui clignote ---
        if (cfg.screamerFlicker) {
            val colors = listOf(NamedTextColor.DARK_RED, NamedTextColor.WHITE, NamedTextColor.BLACK, NamedTextColor.RED, NamedTextColor.WHITE)
            for ((i, color) in colors.withIndex()) {
                later(4L + i * 2L) {
                    flash(color, if (i % 2 == 0) "☠" else glitchString(5), Component.text(glitchString(12), NamedTextColor.DARK_RED).decorate(TextDecoration.OBFUSCATED))
                }
            }
        }

        // --- t = 12 : second cri ---
        later(12) {
            if (sounds) {
                player.playSound(player.location, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 2.0f, 0.5f)
                player.playSound(player.location, Sound.ENTITY_SPIDER_HURT, 2.0f, 0.5f)
            }
            effect(PotionEffectType.NAUSEA, 160)
        }

        // --- grattements et cœur qui s'emballe ---
        for ((i, t) in listOf(20L, 28L, 36L).withIndex()) {
            later(t) {
                if (!sounds) return@later
                player.playSound(player.location, Sound.ENTITY_SPIDER_AMBIENT, 1.5f, 0.5f + i * 0.1f)
                player.playSound(player.location, Sound.ENTITY_WARDEN_HEARTBEAT, 2.0f, 1.0f + i * 0.2f)
            }
        }

        // --- murmure brouillé ---
        later(45) {
            player.sendActionBar(Component.text("elle est toujours là", NamedTextColor.DARK_RED).decorate(TextDecoration.OBFUSCATED))
        }

        // --- elle peut disparaître dans le noir ---
        if (Random.nextDouble() < cfg.screamerVanishChance) {
            runLater(cfg.screamerVanishDelayTicks.toLong()) {
                if (!entity.scheduledForRemoval) {
                    if (sounds) world.playSound(spider.position, Sound.ENTITY_SPIDER_DEATH, 1.5f, 0.6f)
                    entity.remove()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Taille aléatoire
    // -------------------------------------------------------------------------

    private fun applyScale(spider: SpiderBody, options: SpiderOptions, scale: Double) {
        if (abs(scale - options.bodyPlan.scale) < 1e-6) return
        options.setScale(scale)
        // recalcule les allures pour que les vitesses restent celles de la config (x échelle)
        HorrorSpiderFactory.configureGaits(
            options, Horror.config, options.bodyPlan.scale, if (mini) Horror.config.miniSpeedBoost else 1.0,
        )
        spider.updateBodyPlan() // reconstruit les pattes avec les nouvelles longueurs
    }

    private fun tickScale(spider: SpiderBody, options: SpiderOptions, cfg: HorrorConfig) {
        if (!cfg.scaleEnabled || mini) return

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
        if (mini) return   // la mini-araignée garde sa taille
        val current = options.bodyPlan.scale
        // calme : reste petite ; charge : grossit nettement
        val hi = if (preferBigger) cfg.scaleMax else minOf(cfg.scaleMax, cfg.scaleCalmMax)
        val lo = if (preferBigger) minOf(maxOf(current * 2.0, 1.5), hi) else cfg.scaleMin

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
        // le camouflage a la priorité sur les couleurs d'état
        if (cloak.active && cfg.camouflageEnabled) return

        ticksUntilColorChange--
        if (!colorDirty && ticksUntilColorChange > 0) return
        colorDirty = false

        val palette = when (state) {
            HorrorState.STALK -> cfg.calmColors
            HorrorState.FREEZE -> cfg.alertColors
            HorrorState.CHARGE -> cfg.chargeColors
            HorrorState.RETREAT -> cfg.alertColors
            HorrorState.HIDE -> cfg.calmColors
        }
        cloak.tint = palette.random()
        cloak.tintVariation = cfg.colorVariation

        // pendant la charge la couleur "clignote" plus vite
        ticksUntilColorChange = if (state == HorrorState.CHARGE) Random.nextInt(8, 25) else cfg.colorInterval.randomTicks()
    }
}
