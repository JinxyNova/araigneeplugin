package com.heledron.spideranimation.horror

import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.spider.presets.AnimatedPalettes
import com.heledron.spideranimation.spider.presets.octopod
import com.heledron.spideranimation.utilities.BlockDisplayModelPiece
import com.heledron.spideranimation.utilities.DisplayModel
import org.bukkit.Material
import org.bukkit.entity.Display
import org.joml.Matrix4f

/** Change l'échelle absolue de toutes les options (allures + plan du corps). N'appelle PAS updateBodyPlan(). */
fun SpiderOptions.setScale(newScale: Double) {
    val ratio = newScale / bodyPlan.scale
    if (ratio == 1.0) return
    walkGait.scale(ratio)
    gallopGait.scale(ratio)
    bodyPlan.scale(ratio)
}

object HorrorSpiderFactory {
    private const val CLOAK = "cloak"
    private val LIT = Display.Brightness(0, 15)
    private val FULL = Display.Brightness(15, 15)

    /** Options d'une araignée d'horreur : 8 pattes, corps sombre, yeux rouges. */
    fun createOptions(cfg: HorrorConfig, scale: Double): SpiderOptions {
        // 8 pattes, 3 segments (preset "octopod" du dépôt d'origine)
        val options = octopod(3, 1.0)

        options.bodyPlan.bodyModel = createBodyModel()
        options.bodyPlan.eyePalette = AnimatedPalettes.RED_EYES.palette
        options.bodyPlan.blinkingPalette = AnimatedPalettes.RED_BLINKING_LIGHTS.palette

        // allures à l'échelle 1 (la mise à l'échelle les ajuste ensuite)
        configureGaits(options, cfg, 1.0)
        options.gallopGait.moveAcceleration = .15 / 4 * 2   // démarrage de la charge plus sec

        // changement de couleur en fondu lent
        options.cloak.lerpSpeed = cfg.colorFadeSpeed
        options.cloak.lerpRandomness = cfg.colorFadeSpeed * 1.2

        options.setScale(scale)
        return options
    }

    /** Vitesses de marche / de charge. [scale] = échelle actuelle de l'araignée. */
    fun configureGaits(options: SpiderOptions, cfg: HorrorConfig, scale: Double) {
        options.walkGait.maxSpeed = cfg.stalkSpeed * scale
        options.walkGait.legMoveSpeed = maxOf(cfg.stalkSpeed * 2.5, 0.2) * scale

        options.gallopGait.maxSpeed = cfg.chargeSpeed * scale
        options.gallopGait.legMoveSpeed = cfg.chargeSpeed * 1.25 * scale
    }

    // -------------------------------------------------------------------------
    // Modèle du corps (repère local : +Z = avant, +Y = haut, centré sur le corps)
    // -------------------------------------------------------------------------

    private fun box(
        material: Material,
        cx: Float, cy: Float, cz: Float,
        sx: Float, sy: Float, sz: Float,
        tags: List<String> = emptyList(),
        brightness: Display.Brightness? = null,
    ) = BlockDisplayModelPiece(
        block = material.createBlockData(),
        // cube unité [0,1]³ -> boîte centrée en (cx, cy, cz) de taille (sx, sy, sz)
        transform = Matrix4f().translate(cx, cy, cz).scale(sx, sy, sz).translate(-.5f, -.5f, -.5f),
        brightness = brightness,
        tags = tags,
    )

    /** Pièce + son symétrique par rapport au plan X = 0. */
    private fun pair(
        material: Material,
        x: Float, cy: Float, cz: Float,
        sx: Float, sy: Float, sz: Float,
        tags: List<String> = emptyList(),
        brightness: Display.Brightness? = null,
    ) = listOf(
        box(material, x, cy, cz, sx, sy, sz, tags, brightness),
        box(material, -x, cy, cz, sx, sy, sz, tags, brightness),
    )

    private fun createBodyModel(): DisplayModel {
        val shell = Material.BLACK_CONCRETE
        val pieces = mutableListOf<BlockDisplayModelPiece>()

        // céphalothorax, tête, pédicelle, abdomen, bosse dorsale, filières
        pieces += box(shell, 0f, -0.02f, 0.15f, 0.75f, 0.50f, 0.85f, listOf(CLOAK), LIT)
        pieces += box(shell, 0f, -0.08f, 0.72f, 0.55f, 0.42f, 0.40f, listOf(CLOAK), LIT)
        pieces += box(shell, 0f, 0.00f, -0.35f, 0.35f, 0.30f, 0.20f, listOf(CLOAK), LIT)
        pieces += box(shell, 0f, 0.10f, -0.95f, 1.00f, 0.85f, 1.25f, listOf(CLOAK), LIT)
        pieces += box(shell, 0f, 0.38f, -1.05f, 0.78f, 0.50f, 0.95f, listOf(CLOAK), LIT)
        pieces += box(shell, 0f, -0.05f, -1.70f, 0.30f, 0.30f, 0.25f, listOf(CLOAK), LIT)

        // pédipalpes
        pieces += pair(shell, 0.30f, -0.20f, 0.85f, 0.10f, 0.10f, 0.30f, listOf(CLOAK), LIT)

        // crochets (blancs)
        pieces += pair(Material.BONE_BLOCK, 0.14f, -0.36f, 0.88f, 0.08f, 0.28f, 0.08f, brightness = Display.Brightness(4, 15))

        // 8 yeux (tag "eye" : scintillement géré par SpiderRenderer)
        val eye = Material.REDSTONE_BLOCK
        pieces += pair(eye, 0.13f, 0.04f, 0.93f, 0.12f, 0.12f, 0.12f, listOf("eye"), FULL)
        pieces += pair(eye, 0.22f, 0.12f, 0.90f, 0.09f, 0.09f, 0.09f, listOf("eye"), FULL)
        pieces += pair(eye, 0.07f, 0.15f, 0.91f, 0.07f, 0.07f, 0.07f, listOf("eye"), FULL)
        pieces += pair(eye, 0.25f, 0.00f, 0.88f, 0.07f, 0.07f, 0.07f, listOf("eye"), FULL)

        // sablier rouge clignotant sur l'abdomen (tag "blinking_lights")
        pieces += box(Material.REDSTONE_BLOCK, 0f, 0.64f, -0.85f, 0.26f, 0.05f, 0.20f, listOf("blinking_lights"), FULL)
        pieces += box(Material.REDSTONE_BLOCK, 0f, 0.65f, -1.10f, 0.20f, 0.05f, 0.16f, listOf("blinking_lights"), FULL)

        for (piece in pieces) piece.tags = piece.tags + "torso"
        return DisplayModel(pieces)
    }
}
