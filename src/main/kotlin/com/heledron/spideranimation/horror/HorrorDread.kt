package com.heledron.spideranimation.horror

import com.heledron.spideranimation.spider.components.body.SpiderBody
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.random.Random

/**
 * Effets d'angoisse ajoutés autour de l'araignée : battements de cœur, faux bruits dans le dos du joueur,
 * pulsations de noir, chuchotements, silence soudain, particules, séquelles après une attaque.
 * Tout se règle dans la section "dread" de config.yml.
 */
class HorrorDread {
    private var heartTicks = 0
    private var noiseTicks = 0
    private var darkTicks = 0
    private var whisperTicks = 0
    private var particleTicks = 0

    private val noises = listOf(
        Sound.AMBIENT_CAVE,
        Sound.BLOCK_WOODEN_DOOR_OPEN,
        Sound.BLOCK_CHEST_CLOSE,
        Sound.ENTITY_WARDEN_NEARBY_CLOSE,
        Sound.ENTITY_ENDERMAN_STARE,
        Sound.BLOCK_GRAVEL_STEP,
        Sound.BLOCK_SCULK_SHRIEKER_SHRIEK,
        Sound.ENTITY_GHAST_AMBIENT,
        Sound.ENTITY_SPIDER_STEP,
        Sound.ENTITY_SPIDER_AMBIENT,
    )

    private val whispers = listOf(
        "elle est là", "ne te retourne pas", "derrière toi", "elle te regarde",
        "tu l'as vue", "elle approche", "ne cours pas", "huit yeux",
    )

    private fun effect(player: Player, type: PotionEffectType, ticks: Int, amplifier: Int = 0) {
        player.addPotionEffect(PotionEffect(type, ticks, amplifier, false, false, false))
    }

    /** Appelée à chaque tick tant qu'un joueur est ciblé. */
    fun tick(
        player: Player, spider: SpiderBody, options: SpiderOptions,
        state: HorrorState, distance: Double, flat: Vector, seen: Boolean, cfg: HorrorConfig,
    ) {
        val charging = state == HorrorState.CHARGE

        // --- battements de cœur : plus rapides quand elle est proche ---
        if (cfg.dreadHeartbeat && distance < cfg.dreadHeartDistance) {
            if (--heartTicks <= 0) {
                val closeness = (1.0 - distance / cfg.dreadHeartDistance).coerceIn(0.0, 1.0)
                heartTicks = if (charging) 8 else (40 - 32 * closeness).toInt().coerceAtLeast(8)
                player.playSound(player.location, Sound.ENTITY_WARDEN_HEARTBEAT, (0.6 + closeness * 1.4).toFloat(), (0.8 + closeness * 0.5).toFloat())
            }
        }

        // --- faux bruits dans le dos du joueur quand il ne la voit pas ---
        if (cfg.dreadNoises && state == HorrorState.STALK && !seen && distance < cfg.dreadNoiseDistance) {
            if (--noiseTicks <= 0) {
                noiseTicks = cfg.dreadNoiseInterval.randomTicks()
                val look = player.location.direction.clone().setY(0.0)
                if (look.lengthSquared() > 1e-6) {
                    look.normalize()
                    val side = Vector(-look.z, 0.0, look.x).multiply((Random.nextDouble() - 0.5) * 4.0)
                    val back = look.clone().multiply(-(3.0 + Random.nextDouble() * 6.0))
                    val at: Location = player.location.clone().add(back).add(side)
                    player.playSound(at, noises.random(), 1.0f + Random.nextFloat(), 0.5f + Random.nextFloat() * 0.8f)
                }
            }
        }

        // --- pulsations de noir quand elle est proche ---
        if (cfg.dreadDarkness && !charging && distance < cfg.dreadDarkDistance) {
            if (--darkTicks <= 0) {
                darkTicks = cfg.dreadDarkInterval.randomTicks()
                effect(player, PotionEffectType.DARKNESS, 50 + Random.nextInt(50))
            }
        }

        // --- chuchotements dans la barre d'action ---
        if (cfg.dreadWhispers && !charging && distance < cfg.dreadNoiseDistance) {
            if (--whisperTicks <= 0) {
                whisperTicks = cfg.dreadWhisperInterval.randomTicks()
                player.sendActionBar(glitchText(whispers.random()))
            }
        }

        // --- particules : cendres et âmes qui flottent autour d'elle, poussière rouge pendant la charge ---
        if (cfg.dreadParticles && ++particleTicks >= 2) {
            particleTicks = 0
            val scale = options.bodyPlan.scale.coerceIn(0.4, 4.0)
            val loc = spider.location()
            val spread = 0.6 * scale
            if (charging) {
                spider.world.spawnParticle(Particle.DUST, loc, 8, spread, spread * 0.5, spread, 0.0, Particle.DustOptions(Color.fromRGB(170, 0, 0), 1.6f))
            } else {
                spider.world.spawnParticle(Particle.ASH, loc, 6, spread, spread * 0.6, spread, 0.0)
                if (Random.nextInt(6) == 0) spider.world.spawnParticle(Particle.SCULK_SOUL, loc, 1, spread * 0.5, spread * 0.3, spread * 0.5, 0.01)
            }
        }
    }

    /** Elle se fige parce qu'elle a été vue : silence total, puis seulement le cœur. Le joueur est pétrifié. */
    fun onFreeze(player: Player, cfg: HorrorConfig) {
        if (!cfg.dreadEnabled) return
        if (cfg.dreadSilence) player.stopAllSounds()
        if (cfg.dreadFear) {
            effect(player, PotionEffectType.SLOWNESS, 50, 1)
            effect(player, PotionEffectType.DARKNESS, 60)
        }
    }

    /** Début de la charge : cri strident, vision qui tourne, noir. */
    fun onCharge(player: Player, cfg: HorrorConfig) {
        if (!cfg.dreadEnabled || !cfg.dreadChargeEffects) return
        player.playSound(player.location, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.5f, 0.7f)
        effect(player, PotionEffectType.NAUSEA, 100)
        effect(player, PotionEffectType.DARKNESS, 80)
    }

    /** Après l'attaque : le joueur reste sonné, dans le noir. */
    fun onAttack(player: Player, cfg: HorrorConfig) {
        if (!cfg.dreadEnabled || !cfg.dreadAftermath) return
        effect(player, PotionEffectType.DARKNESS, 200)
        effect(player, PotionEffectType.NAUSEA, 140)
    }

    /** Texte rouge avec une partie illisible (obfusquée), comme un murmure qui se brouille. */
    private fun glitchText(text: String): Component {
        val cut = (text.length / 3).coerceAtLeast(1)
        val start = Random.nextInt(0, (text.length - cut).coerceAtLeast(1))
        return Component.text(text.substring(0, start), NamedTextColor.DARK_RED)
            .append(Component.text(text.substring(start, start + cut), NamedTextColor.DARK_RED).decorate(TextDecoration.OBFUSCATED))
            .append(Component.text(text.substring(start + cut), NamedTextColor.DARK_RED))
    }
}
