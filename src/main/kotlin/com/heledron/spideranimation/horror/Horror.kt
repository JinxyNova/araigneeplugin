package com.heledron.spideranimation.horror

import com.heledron.spideranimation.AppState
import com.heledron.spideranimation.SpiderAnimationPlugin
import com.heledron.spideranimation.spider.components.Cloak
import com.heledron.spideranimation.spider.components.Locomotion
import com.heledron.spideranimation.spider.components.PointDetector
import com.heledron.spideranimation.spider.components.SoundsAndParticles
import com.heledron.spideranimation.spider.components.TridentHitDetector
import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.components.rendering.SpiderRenderer
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.utilities.ecs.ECS
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import com.heledron.spideranimation.utilities.events.interval
import com.heledron.spideranimation.utilities.maths.UP_VECTOR
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.util.Vector
import org.joml.Quaternionf
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object Horror {
    lateinit var plugin: SpiderAnimationPlugin
    lateinit var config: HorrorConfig

    /** (Re)charge config.yml et met à jour les araignées existantes. */
    fun reload() {
        plugin.reloadConfig()
        config = HorrorConfig(plugin.config)

        for ((_, _, options) in AppState.ecs.query<ECSEntity, SpiderBody, SpiderOptions>()) {
            HorrorSpiderFactory.configureGaits(options, config, options.bodyPlan.scale)
            options.cloak.lerpSpeed = config.colorFadeSpeed
            options.cloak.lerpRandomness = config.colorFadeSpeed * 1.2
        }
    }

    fun allSpiders(): List<ECSEntity> =
        AppState.ecs.query<ECSEntity, HorrorBehavior>().map { it.first }

    fun nearest(world: World, position: Vector): ECSEntity? =
        AppState.ecs.query<ECSEntity, SpiderBody, HorrorBehavior>()
            .filter { it.second.world == world }
            .minByOrNull { it.second.position.distanceSquared(position) }
            ?.first

    /**
     * Fait apparaître une araignée d'horreur.
     * @param ground point au sol où poser les pattes
     * @param facing position vers laquelle elle regarde au départ (le joueur, en général)
     */
    fun spawn(world: World, ground: Vector, facing: Vector?, scale: Double?, autoSpawned: Boolean = false): ECSEntity {
        val cfg = config
        val startScale = (scale ?: 1.0).coerceIn(0.2, 8.0)
        val options = HorrorSpiderFactory.createOptions(cfg, startScale)

        val position = ground.clone().add(UP_VECTOR.multiply(options.walkGait.stationary.bodyHeight))
        val yaw = if (facing == null) Random.nextDouble() * Math.PI * 2
        else atan2(facing.x - ground.x, facing.z - ground.z)
        val orientation = Quaternionf().rotationY(yaw.toFloat())

        val cloak = Cloak()
        cloak.tint = cfg.calmColors.random()
        cloak.tintVariation = cfg.colorVariation

        return AppState.ecs.spawn(
            SpiderBody.create(world, position, orientation),
            options,
            Locomotion(),
            TridentHitDetector(),
            cloak,
            SoundsAndParticles(),
            PointDetector(),   // requis par SpiderRenderer
            SpiderRenderer(),
            HorrorBehavior(autoSpawned),
        )
    }
}

/** À appeler depuis SpiderAnimationPlugin.onEnable(), après setupSpider(). */
fun setupHorror(plugin: SpiderAnimationPlugin, app: ECS) {
    Horror.plugin = plugin
    plugin.saveDefaultConfig()
    Horror.config = HorrorConfig(plugin.config)

    // cerveau des araignées : s'exécute avant Locomotion (qui tourne en post-tick)
    app.onTick {
        for ((entity, spider, options, locomotion, horror) in
            app.query<ECSEntity, SpiderBody, SpiderOptions, Locomotion, HorrorBehavior>()) {
            horror.tick(entity, spider, options, locomotion)
        }
    }

    plugin.getCommand("horror")?.let {
        it.setExecutor(HorrorCommand)
        it.tabCompleter = HorrorCommand
    }

    setupAutoSpawn()
}

// -----------------------------------------------------------------------------
// Apparition automatique (désactivée par défaut, voir config.yml)
// -----------------------------------------------------------------------------

private fun setupAutoSpawn() {
    val period = Horror.config.autoSpawnCheckTicks   // lu au démarrage : redémarre le serveur pour le changer
    interval(100L, period) { _ ->
        val cfg = Horror.config
        if (!cfg.autoSpawnEnabled) return@interval
        if (Random.nextDouble() > cfg.autoSpawnChance) return@interval

        val players = Bukkit.getOnlinePlayers().filter {
            it.gameMode != GameMode.SPECTATOR && !it.isDead &&
                    it.world.environment == World.Environment.NORMAL
        }
        val player = players.randomOrNull() ?: return@interval
        val world = player.world

        if (cfg.autoSpawnOnlyAtNight && world.time !in 13000L..23000L) return@interval

        // pas plus de N araignées autour de ce joueur
        val range = cfg.followRange
        val nearby = AppState.ecs.query<SpiderBody, HorrorBehavior>().count {
            it.first.world == world && it.first.position.distanceSquared(player.location.toVector()) < range * range
        }
        if (nearby >= cfg.autoSpawnMaxSpiders) return@interval

        // 1 à 15 chunks (16 à 240 blocs) du joueur, dans un chunk déjà chargé
        for (attempt in 0 until 10) {
            val angle = Random.nextDouble() * Math.PI * 2
            val chunks = cfg.autoSpawnMinChunks + Random.nextDouble() * (cfg.autoSpawnMaxChunks - cfg.autoSpawnMinChunks)
            val distance = chunks * 16.0
            val bx = Math.floor(player.location.x + cos(angle) * distance).toInt()
            val bz = Math.floor(player.location.z + sin(angle) * distance).toInt()
            if (!world.isChunkLoaded(bx shr 4, bz shr 4)) continue

            val y = world.getHighestBlockYAt(bx, bz)
            val top = world.getBlockAt(bx, y, bz)
            if (top.isLiquid || !top.type.isSolid) continue

            Horror.spawn(world, Vector(bx + 0.5, y + 1.0, bz + 0.5), player.location.toVector(), null, autoSpawned = true)
            break
        }
    }
}
