package com.heledron.spideranimation.horror

import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import org.bukkit.FluidCollisionMode
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Placement sûr : jamais dans un bloc, jamais coincée sous un plafond, jamais dans l'eau.
 * Sert pour les apparitions, les embuscades, les cachettes et le "déblocage" automatique.
 */
object HorrorPlacement {
    private fun passable(block: Block) = block.isPassable && !block.isLiquid

    /** Hauteur libre (en blocs) nécessaire au-dessus du sol pour une araignée de cette taille. */
    fun clearanceHeight(scale: Double): Int = ceil(1.0 + scale * 1.2).toInt().coerceIn(2, 9)

    private fun clearanceRadius(scale: Double): Int = if (scale >= 1.5) 1 else 0

    /** Les blocs au-dessus du sol (groundY = bloc solide) sont tous libres sur toute l'empreinte du corps. */
    private fun hasClearance(world: World, bx: Int, groundY: Int, bz: Int, height: Int, radius: Int): Boolean {
        if (groundY + height >= world.maxHeight) return false
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val x = bx + dx
            val z = bz + dz
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
            for (h in 1..height) {
                if (!passable(world.getBlockAt(x, groundY + h, z))) return false
            }
        }
        return true
    }

    /**
     * Cherche le sol praticable à (x, z) autour de la hauteur [aroundY] : un bloc solide (ni liquide ni feuillage)
     * avec assez de place libre au-dessus. Renvoie la position sur le dessus du bloc, ou null.
     */
    fun findGround(world: World, x: Double, z: Double, aroundY: Int, scale: Double): Vector? {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) return null

        val height = clearanceHeight(scale)
        val radius = clearanceRadius(scale)
        val maxY = minOf(world.maxHeight - 1 - height, aroundY + 8)
        val minY = maxOf(world.minHeight, aroundY - 10)

        for (y in maxY downTo minY) {
            val block = world.getBlockAt(bx, y, bz)
            if (block.isLiquid || block.isPassable || !block.type.isSolid) continue
            if (Tag.LEAVES.isTagged(block.type)) continue
            if (hasClearance(world, bx, y, bz, height, radius)) return Vector(bx + 0.5, y + 1.0, bz + 0.5)
        }
        return null
    }

    /** Variante pour un point lointain dont on ne connaît pas la hauteur : part de la surface. */
    fun findSurface(world: World, x: Double, z: Double, scale: Double): Vector? {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) return null
        return findGround(world, x, z, world.getHighestBlockYAt(bx, bz), scale)
    }

    /**
     * Cherche un endroit proche de [from] où le joueur (yeux en [playerEye]) ne peut PAS voir l'araignée :
     * un bloc se trouve sur la ligne de mire, au niveau du corps comme de la tête. Renvoie le sol, ou null.
     */
    fun findCover(
        world: World, from: Vector, playerEye: Vector, scale: Double,
        minPlayerDistance: Double, searchRadius: Double, tries: Int = 28,
    ): Vector? {
        var best: Vector? = null
        var bestTravel = Double.MAX_VALUE
        val bodyY = 0.5 * scale + 0.4

        fun blocked(point: Vector): Boolean {
            val toPoint = point.clone().subtract(playerEye)
            val dist = toPoint.length()
            if (dist < 1e-6) return false
            val hit = world.rayTraceBlocks(
                playerEye.toLocation(world), toPoint.multiply(1.0 / dist), dist, FluidCollisionMode.NEVER, true,
            ) ?: return false
            // le bloc touché doit être franchement avant l'araignée (pas juste le sol sous elle)
            return hit.hitPosition.distance(playerEye) < dist - 0.8
        }

        repeat(tries) {
            val angle = Random.nextDouble() * Math.PI * 2
            val d = 3.0 + Random.nextDouble() * (searchRadius - 3.0).coerceAtLeast(0.5)
            val ground = findGround(world, from.x + cos(angle) * d, from.z + sin(angle) * d, floor(from.y).toInt(), scale)
                ?: return@repeat
            if (abs(ground.y - from.y) > 4.0) return@repeat
            if (ground.distance(playerEye) < minPlayerDistance) return@repeat

            val body = ground.clone().add(Vector(0.0, bodyY, 0.0))
            val head = ground.clone().add(Vector(0.0, bodyY * 2.0, 0.0))
            if (!blocked(body) || !blocked(head)) return@repeat

            val travel = ground.distance(from)
            if (travel < bestTravel) {
                best = ground
                bestTravel = travel
            }
        }
        return best
    }

    /** true si le centre du corps est dans un bloc plein (ou dans un chunk non chargé : on ne touche à rien). */
    private fun isEmbedded(world: World, p: Vector): Boolean {
        val bx = floor(p.x).toInt()
        val by = floor(p.y).toInt()
        val bz = floor(p.z).toInt()
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) return false
        if (by < world.minHeight || by >= world.maxHeight) return false
        return !passable(world.getBlockAt(bx, by, bz))
    }

    /** Si l'araignée est coincée dans un bloc, la replace sur le sol libre le plus proche. Renvoie true si déplacée. */
    fun ensureFree(entity: ECSEntity, spider: SpiderBody, options: SpiderOptions): Boolean {
        val world = spider.world
        if (!isEmbedded(world, spider.position)) return false

        val scale = options.bodyPlan.scale
        val aroundY = floor(spider.position.y).toInt()
        var ground = findGround(world, spider.position.x, spider.position.z, aroundY + 4, scale)
        if (ground == null) {
            search@ for (r in 1..5) for (i in 0 until 8) {
                val a = i * Math.PI / 4
                ground = findGround(world, spider.position.x + cos(a) * r, spider.position.z + sin(a) * r, aroundY + 4, scale)
                if (ground != null) break@search
            }
        }
        if (ground == null) return false

        val target = ground.clone().add(Vector(0.0, options.gait.stationary.bodyHeight, 0.0))
        spider.teleport(entity, target)
        spider.velocity.setX(0.0).setY(0.0).setZ(0.0)
        return true
    }
}
