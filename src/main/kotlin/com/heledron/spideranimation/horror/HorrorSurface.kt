package com.heledron.spideranimation.horror

import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.utilities.isFiniteVector
import com.heledron.spideranimation.utilities.raycastGround
import com.heledron.spideranimation.utilities.safeNormalize
import org.bukkit.World
import org.bukkit.util.Vector
import kotlin.math.abs

/** Résultat d'un rayon contre les blocs. [normal] = normale de la face touchée (elle sort du bloc). */
internal class SurfaceHit(val point: Vector, val normal: Vector, val distance: Double)

internal fun castSurface(world: World, from: Vector, direction: Vector, length: Double): SurfaceHit? {
    if (!from.isFiniteVector() || !direction.isFiniteVector() || !length.isFinite() || length <= 1e-6) return null
    if (!world.isChunkLoaded(Math.floor(from.x).toInt() shr 4, Math.floor(from.z).toInt() shr 4)) return null

    val unit = direction.safeNormalize(Vector(0.0, 0.0, 0.0))
    if (unit.lengthSquared() < 1e-9) return null

    val ray = world.raycastGround(from, unit, length) ?: return null
    val face = ray.hitBlockFace ?: return null
    return SurfaceHit(ray.hitPosition, face.direction, ray.hitPosition.distance(from))
}

/**
 * Capteur de surface : décide sur quelle surface l'araignée doit être collée (sol, mur, plafond).
 *
 * Il écrit [SpiderBody.targetSurfaceUp] ; le corps tourne ensuite doucement vers cette normale
 * (voir SpiderBody.updateSurfaceUp), et les pattes / la gravité suivent le repère.
 *
 *  - obstacle devant        -> angle rentrant : elle monte sur la face touchée (mur, puis plafond...)
 *  - le sol s'arrête devant -> angle sortant : elle passe l'arête et suit la face de côté (ou le dessus d'un mur)
 *  - plus rien sous le corps -> elle se décroche et retombe au sol
 */
class SurfaceSensor {
    /** Facteur de vitesse (0.1 à 1) : elle ralentit pendant que son corps pivote vers une nouvelle surface. */
    var speedFactor = 1.0; private set

    private var lockTicks = 0
    private var senseDelay = 0
    private var missTicks = 0

    // garde-fou : trop de changements de surface en peu de temps = elle "roule" sur elle-même
    private var clock = 0
    private val recentSwitches = ArrayList<Int>()
    private var suppressTicks = 0

    private var pendingWall: Vector? = null

    /** Si elle vient de passer du sol à un mur vertical (obstacle), renvoie la normale de ce mur (une seule fois). */
    fun takeWallContact(): Vector? {
        val wall = pendingWall
        pendingWall = null
        return wall
    }

    /** Remet le capteur à zéro (nouvelle araignée, téléportation...). */
    fun reset() {
        lockTicks = 0
        senseDelay = 0
        missTicks = 0
        recentSwitches.clear()
        suppressTicks = 0
        pendingWall = null
    }

    /**
     * @param dir  direction de marche voulue, tangente à la surface (null = elle ne bouge pas)
     * @param goal point visé (le joueur), sert à savoir si elle doit redescendre le long d'une arête
     */
    fun update(spider: SpiderBody, options: SpiderOptions, dir: Vector?, goal: Vector?, cfg: HorrorConfig) {
        val target = spider.targetSurfaceUp

        if (!cfg.climbEnabled) {
            target.setX(0.0).setY(1.0).setZ(0.0)
            speedFactor = 1.0
            return
        }

        // elle ralentit tant que le repère n'a pas fini de tourner (sinon elle s'enfonce dans le mur)
        val alignment = spider.surfaceUp.dot(target).coerceIn(-1.0, 1.0)
        speedFactor = ((alignment - 0.3) / 0.7).coerceIn(0.1, 1.0)

        clock++

        // en plein saut : le repère revient au monde, rien à détecter
        if (spider.airborneTicks > 0) {
            target.setX(0.0).setY(1.0).setZ(0.0)
            return
        }

        // trop de bascules : elle reste au sol un moment
        if (suppressTicks > 0) {
            suppressTicks--
            target.setX(0.0).setY(1.0).setZ(0.0)
            return
        }

        if (lockTicks > 0) lockTicks--
        senseDelay--
        if (senseDelay > 0 || lockTicks > 0) return
        senseDelay = cfg.climbSenseTicks

        val world = spider.world
        val pos = spider.position
        if (!pos.isFiniteVector()) return

        val scale = options.bodyPlan.scale
        val bodyH = options.gait.stationary.bodyHeight.coerceAtLeast(0.2)
        val current = target.clone()
        val away = current.clone().multiply(-1.0)      // vers la surface
        val reach = bodyH * 2.5 + 0.8

        val moving = dir != null && dir.isFiniteVector() && dir.lengthSquared() > 1e-6
        val under = castSurface(world, pos, away, reach)

        // 1) obstacle devant : angle rentrant, on monte dessus
        if (moving && dir != null) {
            val aheadDistance = scale * 1.6 + 0.5
            val wall = castSurface(world, pos, dir, aheadDistance)
            if (under != null && wall != null && wall.normal.dot(current) < 0.5 &&
                !isLowStep(world, wall, dir, current, under, pos, bodyH, scale)
            ) {
                if (current.y > 0.7 && abs(wall.normal.y) < 0.3) pendingWall = wall.normal.clone()
                switchTo(spider, wall.normal, cfg)
                return
            }
        }

        if (under != null) {
            missTicks = 0

            // 2) le sol s'arrête devant : angle sortant, on passe l'arête
            if (moving && dir != null) {
                val frontDistance = scale * 1.3 + 0.25
                val front = pos.clone().add(dir.clone().multiply(frontDistance))
                if (castSurface(world, front, away, reach) == null) {
                    val side = findSideFace(world, front, dir, current, bodyH, frontDistance)
                    if (side != null && wantsWrap(world, front, away, reach, current, goal, pos, cfg)) {
                        switchTo(spider, side, cfg)
                        return
                    }
                }
            }
            return
        }

        // 3) plus rien sous le corps : elle a déjà dépassé l'arête, ou elle s'est décrochée
        if (moving && dir != null) {
            val side = findSideFace(world, pos, dir, current, bodyH, 0.0)
            if (side != null) {
                switchTo(spider, side, cfg)
                return
            }
        }

        missTicks++
        if (missTicks > 8) {
            target.setX(0.0).setY(1.0).setZ(0.0)
            missTicks = 0
        }
    }

    private fun switchTo(spider: SpiderBody, normal: Vector, cfg: HorrorConfig) {
        spider.targetSurfaceUp.copy(normal)
        lockTicks = cfg.climbLockTicks
        missTicks = 0

        recentSwitches.add(clock)
        recentSwitches.removeAll { clock - it > 120 }
        if (recentSwitches.size > 4) {
            // elle oscille entre deux surfaces : on arrête et on la remet au sol
            recentSwitches.clear()
            suppressTicks = 200
            pendingWall = null
            spider.targetSurfaceUp.setX(0.0).setY(1.0).setZ(0.0)
        }
    }

    /**
     * Vrai si l'obstacle devant n'est qu'une marche que les pattes enjambent (escalier, dalle...).
     * Seulement au sol : on ne veut pas faire pivoter tout le corps à chaque marche.
     */
    private fun isLowStep(
        world: World, wall: SurfaceHit, dir: Vector, current: Vector, under: SurfaceHit?,
        pos: Vector, bodyH: Double, scale: Double,
    ): Boolean {
        if (current.y < 0.7) return false

        val floorY = under?.point?.y ?: (pos.y - bodyH)
        val maxStep = if (scale >= 1.0) 1.25 else 1.25 * scale.coerceAtLeast(0.4)

        // on regarde depuis au-dessus de l'obstacle : où est son sommet ?
        val probeStart = Vector(
            wall.point.x + dir.x * 0.3,
            floorY + maxStep + 1.0,
            wall.point.z + dir.z * 0.3,
        )
        val top = castSurface(world, probeStart, Vector(0.0, -1.0, 0.0), 1.0 + maxStep + 0.3)
            ?: return false   // pas de dessus trouvé : mur haut (ou trou), pas une marche
        return top.normal.y > 0.9 && top.point.y <= floorY + maxStep + 0.05
    }

    /**
     * Face "de côté" juste derrière une arête. [from] est un point au-delà de l'arête ; on recule d'un cran
     * dans l'épaisseur du bloc et on tire vers l'arrière.
     */
    private fun findSideFace(
        world: World, from: Vector, dir: Vector, current: Vector, bodyH: Double, frontDistance: Double,
    ): Vector? {
        val probe = from.clone().subtract(current.clone().multiply(bodyH * 1.2))
        val hit = castSurface(world, probe, dir.clone().multiply(-1.0), frontDistance + bodyH * 0.5 + 0.8)
            ?: return null
        if (hit.normal.dot(current) > 0.5) return null   // ce n'est pas une nouvelle surface
        return hit.normal
    }

    /** Doit-elle suivre l'arête ? Toujours sur un mur / plafond ; au sol seulement si le joueur est en contrebas ou si c'est une falaise. */
    private fun wantsWrap(
        world: World, front: Vector, away: Vector, reach: Double, current: Vector,
        goal: Vector?, pos: Vector, cfg: HorrorConfig,
    ): Boolean {
        if (current.y < 0.7) return true
        if (goal != null && goal.y < pos.y - cfg.climbDescendHeight) return true
        return castSurface(world, front, away, reach + 3.0) == null
    }
}
