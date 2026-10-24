package com.heledron.spideranimation.spider.components.body

import com.heledron.spideranimation.spider.configuration.LegPlan
import com.heledron.spideranimation.spider.configuration.SpiderOptions
import com.heledron.spideranimation.utilities.*
import com.heledron.spideranimation.utilities.ecs.ECS
import com.heledron.spideranimation.utilities.ecs.ECSEntity
import com.heledron.spideranimation.utilities.raycastGround
import com.heledron.spideranimation.utilities.resolveCollision
import com.heledron.spideranimation.utilities.maths.DOWN_VECTOR
import com.heledron.spideranimation.utilities.maths.UP_VECTOR
import com.heledron.spideranimation.utilities.maths.lerp
import com.heledron.spideranimation.utilities.maths.moveTowards
import com.heledron.spideranimation.utilities.maths.rotate
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin


class LegStepEvent(val entity: ECSEntity, val spider: SpiderBody, val leg: Leg)

class Leg(
    val ecs: ECS,
    val entity: ECSEntity,
    val spider: SpiderBody,
    val legPlan: LegPlan,
    val options: SpiderOptions,
) {
    // memo
    lateinit var triggerZone: Capsule; private set
    lateinit var comfortZone: Capsule; private set

    lateinit var restPosition: Vector; private set
    lateinit var lookAheadPosition: Vector; private set
    lateinit var scanLine: LineSegment; private set

    init {
        updateMemo()
    }

    // state
    var groundTarget = locateGroundTarget(); private set
    var target = groundTarget ?: strandedTarget()
    var endEffector = target.position.clone()
    var previousEndEffector = endEffector.clone()

    var ik = createSpiderLegIK(legPlan); private set

    var touchingGround = true; private set
    var isMoving = false; private set
    var timeSinceBeginMove = 0; private set
    var timeSinceStopMove = 0; private set

    private var stepStart = Vector(0, 0, 0)
    private var stepProgress = 0.0 // separate from timeSinceBeginMove since progress can soft-reset on re-target

    var isDisabled = false
    var isPrimary = false
    var canMove = false

    // utils
    val isOutsideTriggerZone: Boolean; get () { return !triggerZone.contains(endEffector) }
    val isUncomfortable: Boolean; get () { return !comfortZone.contains(endEffector) }

    fun isGrounded(): Boolean {
        return touchingGround && !isMoving && !isDisabled
    }

    fun updateMemo() {
        val posture = spider.posture(options.gait)
        val scanOrientation = options.gait.scanOrientation.get(spider)


        // rest position
        restPosition = legPlan.restPosition.clone()
        restPosition.add(UP_VECTOR.clone().multiply(-posture.bodyHeight))
        restPosition.rotate(scanOrientation).add(spider.position)

        // lookahead
        lookAheadPosition = lookAheadPosition(restPosition, posture.triggerZoneRadius)

        // scan (from lookahead position)
        val upVector = UP_VECTOR.rotate(scanOrientation)
        val scanStartAxis = upVector.clone().multiply(posture.bodyHeight * 1.6)
        val scanAxis = upVector.clone().multiply(-posture.bodyHeight * 2.5)
        scanLine = LineSegment.fromOffset(lookAheadPosition.clone().add(scanStartAxis), scanAxis)

        // trigger/comfort zone capsules (from rest position; same axis as scan)
        val zoneStart = restPosition.clone().add(scanStartAxis)
        val zoneEnd = zoneStart.clone().add(scanAxis)
        triggerZone = Capsule(zoneStart, zoneEnd, posture.triggerZoneRadius)
        comfortZone = Capsule(zoneStart, zoneEnd, options.gait.comfortZoneRadius)
    }

    private fun stateIsFinite(): Boolean =
        endEffector.isFiniteVector() && previousEndEffector.isFiniteVector() &&
        stepStart.isFiniteVector() && target.position.isFiniteVector() && stepProgress.isFinite()

    /** Remet la patte dans un état sain (NaN / infini) : posée au repos, immobile, chaîne IK recréée. */
    fun resetState() {
        val fallback = if (::restPosition.isInitialized && restPosition.isFiniteVector()) restPosition else spider.position
        endEffector = fallback.clone()
        previousEndEffector = fallback.clone()
        stepStart = fallback.clone()
        stepProgress = 0.0
        isMoving = false
        touchingGround = false
        groundTarget = null
        target = LegTarget(position = fallback.clone(), isGrounded = false, id = -1)
        ik = createSpiderLegIK(legPlan)
    }

    /** Pose immédiatement la patte sur sa cible (après une téléportation sur une autre surface). */
    fun snapToTarget() {
        updateMemo()
        val ground = locateGroundTarget()
        groundTarget = ground
        val newTarget = ground ?: strandedTarget()
        target = newTarget
        endEffector = newTarget.position.clone()
        previousEndEffector = endEffector.clone()
        stepStart = endEffector.clone()
        stepProgress = 0.0
        isMoving = false
        touchingGround = newTarget.isGrounded
        ik = createSpiderLegIK(legPlan)
    }

    fun update() {
        if (!spider.position.isFiniteVector()) return
        if (!stateIsFinite()) resetState()

        updateMovement()
        if (!stateIsFinite()) resetState()

        ik.solve(spider.position, spider.orientation, endEffector)
        if (!ik.isFinite()) {
            resetState()
            ik.solve(spider.position, spider.orientation, endEffector)
        }
    }

    private fun updateMovement() {
        previousEndEffector = endEffector.clone()

        timeSinceBeginMove += 1
        timeSinceStopMove += 1

        // update target
        val oldTargetPosition = target.position.clone()
        val ground = locateGroundTarget()
        groundTarget = ground

        if (isDisabled) {
            target = disabledTarget(ground?.position)
        } else {
            if (ground != null) target = ground

            if (!target.isGrounded || !comfortZone.contains(target.position)) {
                target = strandedTarget()
            }
        }

        // reset step if target has changed
        val targetChanged = oldTargetPosition.distanceSquared(target.position) > 1e-6
        if (targetChanged) {
            softResetStep()
        }

        // inherit parent motion while free
        if (!isGrounded()) {
            applyBodyMotion(endEffector)
            if (isMoving) applyBodyMotion(stepStart)
        }

        var didStep = false
        if (isMoving) {
            didStep = updateMove()
        } else {
            canMove = options.gait.type.canMoveLeg(this)
            if (canMove) beginMove()
        }


        // resolve ground collision
        val scanOrientation = options.gait.scanOrientation.get(spider)
        val down = DOWN_VECTOR.rotate(scanOrientation)
        val collision = spider.world.resolveCollision(endEffector, down)
        if (collision != null) {
            // ignore collision if it would push the end effector further from the target
            // e.g. when grazing an obstacle
            val isFurtherFromTarget = collision.position.distance(target.position) > endEffector.distance(target.position)
            if (!isFurtherFromTarget) {
                didStep = true
                touchingGround = true
                endEffector.copy(collision.position)
            }
        }

        if (didStep) ecs.emit(LegStepEvent(entity = entity, spider = spider, leg = this))
    }

    private fun applyBodyMotion(point: Vector) {
        point.add(spider.velocity)
        spinAroundUp(point)
    }

    /** Fait tourner [point] autour de la normale de la surface (la verticale quand elle est au sol), comme le corps. */
    private fun spinAroundUp(point: Vector) {
        val up = spider.surfaceUp
        val rotation = spider.rotationalVelocity
        val spin = (rotation.x * up.x + rotation.y * up.y + rotation.z * up.z).toDouble()
        if (spin == 0.0 || !spin.isFinite()) return
        val axis = up.clone().safeNormalize(Vector(0.0, 1.0, 0.0))
        point.subtract(spider.position).rotateAroundNonUnitAxis(axis, spin).add(spider.position)
    }

    private fun beginMove() {
        isMoving = true
        timeSinceBeginMove = 0
        stepStart = endEffector.clone()
        stepProgress = 0.0
        touchingGround = false
    }

    private fun completeMove(): Boolean {
        isMoving = false
        timeSinceStopMove = 0
        endEffector.copy(target.position)
        touchingGround = touchingGround()
        return touchingGround
    }

    /** Parabolic height envelope: 0 at ends, 1 at mid-step. */
    private fun stepLiftFactor(t: Double) = 4.0 * t * (1.0 - t)

    private fun sampleStepPosition(t: Double): Vector {
        val position = Vector(
            stepStart.x.lerp(target.position.x, t),
            stepStart.y.lerp(target.position.y, t),
            stepStart.z.lerp(target.position.z, t),
        )
        // la patte se lève le long de la normale de la surface (vers le haut au sol, vers l'extérieur sur un mur / plafond)
        val up = UP_VECTOR.rotate(options.gait.scanOrientation.get(spider))
        position.add(up.multiply(options.gait.legLiftHeight * stepLiftFactor(t)))
        return position
    }

    private fun updateMove(): Boolean {
		val gait = options.gait
    	
        val stepUp = UP_VECTOR.rotate(gait.scanOrientation.get(spider))
        val stepDelta = target.position.clone().subtract(stepStart)
        stepDelta.subtract(stepUp.clone().multiply(stepDelta.dot(stepUp)))
        val distance = stepDelta.length().coerceAtLeast(1e-4)
        stepProgress = stepProgress.moveTowards(1.0, gait.legMoveSpeed / distance)

        endEffector.copy(sampleStepPosition(stepProgress))

        if (stepProgress >= 1.0) {
            return completeMove()
        }

        return false
    }

    private fun softResetStep() {
        stepStart = endEffector.clone()
        stepProgress = 0.0
    }

    private fun touchingGround(): Boolean {
        val scanOrientation = options.gait.scanOrientation.get(spider)
        return spider.world.raycastGround(endEffector, DOWN_VECTOR.rotate(scanOrientation), 0.001) != null
    }

    private fun lookAheadPosition(restPosition: Vector, triggerZoneRadius: Double): Vector {
        if (!spider.isWalking) return restPosition

        val direction = if (spider.velocity.isZero) spider.forwardDirection() else spider.velocity.clone().normalize()

        val lookAhead = direction.multiply(triggerZoneRadius * options.gait.legLookAheadFraction).add(restPosition)
        spinAroundUp(lookAhead)
        return lookAhead
    }

    fun locateGroundTarget(): LegTarget? {
        // mur, plafond ou sol très incliné : balayage dans le plan de la surface
        val surfaceUp = UP_VECTOR.rotate(options.gait.scanOrientation.get(spider))
        if (surfaceUp.y < 0.999) return locateGroundTargetOnSurface(surfaceUp)

        val lookAhead = lookAheadPosition.toLocation(spider.world)
        val scanStartPosition = scanLine.point1
        val scanVector = scanLine.vector()
        val scanLength = scanVector.length()

        fun candidateAllowed(id: Int): Boolean {
            return true
        }

        var id = 0
        val world = spider.world
        fun rayCast(x: Double, z: Double): LegTarget? {
            id += 1

            if (!candidateAllowed(id)) return null

            val start = Vector(x, scanStartPosition.y, z)
            val hit = world.raycastGround(start, scanVector, scanLength) ?: return null

            return LegTarget(position = hit.hitPosition, isGrounded = true, id = id)
        }

        val x = scanStartPosition.x
        val z = scanStartPosition.z

        val mainCandidate = rayCast(x, z)

        if (!options.gait.legScanAlternativeGround) return mainCandidate

        if (mainCandidate != null) {
            if (mainCandidate.position.y in lookAhead.y - .24 .. lookAhead.y + 1.5) {
                return mainCandidate
            }
        }

        val margin = 2 / 16.0
        val nx = floor(x) - margin
        val nz = floor(z) - margin
        val pz = ceil(z) + margin
        val px = ceil(x) + margin

        val candidates = listOf(
            rayCast(nx, nz), rayCast(nx, z), rayCast(nx, pz),
            rayCast(x, nz),  mainCandidate,  rayCast(x, pz),
            rayCast(px, nz), rayCast(px, z), rayCast(px, pz),
        )

        val preferredPosition = lookAhead.toVector()

        val frontBlock = lookAhead.clone().add(spider.forwardDirection().clone().multiply(1)).block
        if (!frontBlock.isPassable) preferredPosition.y += options.gait.legScanHeightBias

        val best = candidates
            .filterNotNull()
            .minByOrNull { it.position.distanceSquared(preferredPosition) }

        if (best != null && !comfortZone.contains(best.position)) {
            return null
        }

        return best
    }

    /** Version de [locateGroundTarget] valable pour n'importe quelle orientation de surface (murs, plafonds). */
    private fun locateGroundTargetOnSurface(up: Vector): LegTarget? {
        val world = spider.world
        val scanStart = scanLine.point1
        val scanVector = scanLine.vector()
        val scanLength = scanVector.length()
        if (scanLength < 1e-6 || !scanLength.isFinite()) return null

        // deux axes tangents à la surface
        val reference = if (abs(up.y) < 0.9) Vector(0.0, 1.0, 0.0) else Vector(1.0, 0.0, 0.0)
        val sideA = up.clone().crossProduct(reference).safeNormalize(Vector(1.0, 0.0, 0.0))
        val sideB = up.clone().crossProduct(sideA).safeNormalize(Vector(0.0, 0.0, 1.0))

        var id = 0
        fun cast(offsetA: Double, offsetB: Double): LegTarget? {
            id += 1
            val start = scanStart.clone()
                .add(sideA.clone().multiply(offsetA))
                .add(sideB.clone().multiply(offsetB))
            val hit = world.raycastGround(start, scanVector, scanLength) ?: return null
            // le départ du rayon est dans un bloc : la "surface" trouvée n'en est pas une
            if (hit.hitBlockFace == null || hit.hitPosition.distanceSquared(start) < 1e-4) return null
            return LegTarget(position = hit.hitPosition, isGrounded = true, id = id)
        }

        val lookAhead = lookAheadPosition
        val main = cast(0.0, 0.0)
        if (!options.gait.legScanAlternativeGround) return main

        if (main != null) {
            val height = main.position.clone().subtract(lookAhead).dot(up)
            if (height >= -0.24 && height <= 1.5) return main
        }

        val radius = maxOf(0.35, options.bodyPlan.scale * 0.5)
        val candidates = ArrayList<LegTarget?>()
        candidates.add(main)
        for (i in 0 until 8) {
            val angle = i * Math.PI / 4
            candidates.add(cast(cos(angle) * radius, sin(angle) * radius))
        }

        val preferred = lookAhead.clone()
        val frontBlock = lookAhead.clone().add(spider.forwardDirection()).toLocation(world).block
        if (!frontBlock.isPassable) preferred.add(up.clone().multiply(options.gait.legScanHeightBias))

        val best = candidates
            .filterNotNull()
            .minByOrNull { it.position.distanceSquared(preferred) }

        if (best != null && !comfortZone.contains(best.position)) return null
        return best
    }

    private fun strandedTarget(): LegTarget {
        return LegTarget(position = lookAheadPosition.clone(), isGrounded = false, id = -1)
    }

    private fun disabledTarget(groundPosition: Vector?): LegTarget {
        val posture = spider.posture(options.gait)
        val upVector = UP_VECTOR.rotate(spider.orientation)

        val target = strandedTarget()
        target.position.add(upVector.clone().multiply(posture.bodyHeight * .5))

        val minY = (groundPosition?.y ?: -Double.MAX_VALUE) + posture.bodyHeight * .1
        target.position.y = target.position.y.coerceAtLeast(minY)

        return target
    }
}

class LegTarget(
    val position: Vector,
    val isGrounded: Boolean,
    val id: Int,
)