package org.valkyrienskies.eureka.ship

import com.fasterxml.jackson.annotation.JsonIgnore
import net.minecraft.core.Direction
import org.joml.AxisAngle4d
import org.joml.Math.clamp
import org.joml.Quaterniond
import org.joml.Vector3d
import org.valkyrienskies.core.api.*
import org.valkyrienskies.core.game.ships.PhysShip
import org.valkyrienskies.core.pipelines.SegmentUtils
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.mod.api.SeatedControllingPlayer
import org.valkyrienskies.mod.common.util.toJOMLD
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

class EurekaShipControl : ShipForcesInducer, ServerShipUser, Ticked {

    @JsonIgnore
    override var ship: ServerShip? = null
    private val controllingPlayer by shipValue<SeatedControllingPlayer>()

    private var extraForce = 0.0
    var aligning = false
    private var physConsumption = 0f
    private val anchored get() = anchorsActive > 0
    private val anchorSpeed = EurekaConfig.SERVER.anchorSpeed
    private var wasAnchored = false
    private var anchorTargetPos = Vector3d()
    private var anchorTargetRot = Quaterniond()

    private var angleUntilAligned = 0.0
    private var alignTarget = 0
    val canDisassemble get() = angleUntilAligned < DISASSEMBLE_THRESHOLD
    val aligningTo: Direction get() = Direction.from2DDataValue(alignTarget)
    var consumed = 0f
        private set

    private var wasCruisePressed = false
    private var cruise = false
    private var controlData: ControlData? = null

    private data class ControlData(
        val seatInDirection: Direction,
        var forwardImpulse: Float = 0.0f,
        var leftImpulse: Float = 0.0f,
        var upImpulse: Float = 0.0f,
        var sprintOn: Boolean = false
    ) {
        companion object {
            fun create(player: SeatedControllingPlayer): ControlData {
                return ControlData(
                    player.seatInDirection,
                    player.forwardImpulse,
                    player.leftImpulse,
                    player.upImpulse,
                    player.sprintOn
                )
            }
        }
    }

    override fun applyForces(forcesApplier: ForcesApplier, physShip: PhysShip) {
        if (helms < 1) {
            return
        }

        val mass = physShip.inertia.shipMass
        val moiTensor = physShip.inertia.momentOfInertiaTensor
        val segment = physShip.segments.segments[0]?.segmentDisplacement!!
        val omega = SegmentUtils.getOmega(physShip.poseVel, segment, Vector3d())
        val vel = SegmentUtils.getVelocity(physShip.poseVel, segment, Vector3d())

        val buoyantFactorPerFloater = min(
            EurekaConfig.SERVER.floaterBuoyantFactorPerKg / 15 / mass,
            EurekaConfig.SERVER.maxFloaterBuoyantFactor
        )

        physShip.buoyantFactor = 1.0 + floaters * buoyantFactorPerFloater
        // Revisiting eureka control code.
        // [x] Move torque stabilization code
        // [x] Move linear stabilization code
        // [x] Revisit player controlled torque
        // [x] Revisit player controlled linear force
        // [x] Anchor freezing
        // [x] Rewrite Alignment code
        // [x] Revisit Elevation code
        // [x] Balloon limiter
        // [ ] Add Cruise code
        // [ ] Rotation based of shipsize
        // [x] Engine consumption
        // [ ] Fix elevation sensititvity

        // region Aligning

        val invRotation = physShip.poseVel.rot.invert(Quaterniond())
        val invRotationAxisAngle = AxisAngle4d(invRotation)
        // Floor makes a number 0 to 3, which corresponds to direction
        alignTarget = floor((invRotationAxisAngle.angle / (PI * 0.5)) + 4.5).toInt() % 4
        angleUntilAligned = (alignTarget.toDouble() * (0.5 * Math.PI)) - invRotationAxisAngle.angle
        if (aligning && abs(angleUntilAligned) > ALIGN_THRESHOLD) {
            if (angleUntilAligned < 0.3 && angleUntilAligned > 0.0) angleUntilAligned = 0.3
            if (angleUntilAligned > -0.3 && angleUntilAligned < 0.0) angleUntilAligned = -0.3

            val idealOmega = Vector3d(invRotationAxisAngle.x, invRotationAxisAngle.y, invRotationAxisAngle.z)
                .mul(-angleUntilAligned)
                .mul(EurekaConfig.SERVER.stabilizationSpeed)

            val idealTorque = moiTensor.transform(idealOmega)

            forcesApplier.applyInvariantTorque(idealTorque)
        }
        // endregion

        stabilize(
            physShip,
            omega,
            vel,
            segment,
            forcesApplier,
            controllingPlayer == null && !aligning,
            controllingPlayer == null
        )

        var idealUpwardVel = Vector3d(0.0, 0.0, 0.0)


        val player = controllingPlayer

        if (player != null) {
            // If the player is currently controlling the ship
            if (!wasCruisePressed && player.cruise) {
                // the player pressed the cruise button
                cruise = !cruise
            }

            if (!cruise) {
                // only take the latest control data if the player is not cruising
                controlData = ControlData.create(player)
            }

            wasCruisePressed = player.cruise
        } else if (!cruise) {
            // If the player isn't controlling the ship, and not cruising, reset the control data
            controlData = null
        }


        controlData?.let { control ->
            // region Player controlled rotation
            var rotationVector = Vector3d(
                0.0,
                if (control.leftImpulse != 0.0f)
                    (control.leftImpulse.toDouble() * EurekaConfig.SERVER.turnSpeed)
                else
                    -omega.y() * EurekaConfig.SERVER.turnSpeed,
                0.0
            )

            rotationVector.sub(0.0, omega.y(), 0.0)

            SegmentUtils.transformDirectionWithScale(
                physShip.poseVel,
                segment,
                moiTensor.transform(
                    SegmentUtils.invTransformDirectionWithScale(
                        physShip.poseVel,
                        segment,
                        rotationVector,
                        rotationVector
                    )
                ),
                rotationVector
            )

            forcesApplier.applyInvariantTorque(rotationVector)
            // endregion

            // region Player controlled banking
            rotationVector = control.seatInDirection.normal.toJOMLD()

            physShip.poseVel.transformDirection(rotationVector)

            rotationVector.y = 0.0

            rotationVector.mul(control.leftImpulse.toDouble() * EurekaConfig.SERVER.turnSpeed * -1.5)

            SegmentUtils.transformDirectionWithScale(
                physShip.poseVel,
                segment,
                moiTensor.transform(
                    SegmentUtils.invTransformDirectionWithScale(
                        physShip.poseVel,
                        segment,
                        rotationVector,
                        rotationVector
                    )
                ),
                rotationVector
            )

            forcesApplier.applyInvariantTorque(rotationVector)
            // endregion

            // region Player controlled forward and backward thrust
            val forwardVector = control.seatInDirection.normal.toJOMLD()
            SegmentUtils.transformDirectionWithoutScale(
                physShip.poseVel,
                segment,
                forwardVector,
                forwardVector
            )
            forwardVector.mul(control.forwardImpulse.toDouble())


            // This is the speed that the ship is always allowed to go out, without engines
            val baseForwardVel = Vector3d(forwardVector).mul(EurekaConfig.SERVER.baseSpeed)
            val baseForwardForce = Vector3d(baseForwardVel).sub(vel.x(), 0.0, vel.z()).mul(mass * 10)

            // This is the maximum speed we want to go in any scenario (when not sprinting)
            val idealForwardVel = Vector3d(forwardVector).mul(EurekaConfig.SERVER.maxCasualSpeed.toDouble())
            val idealForwardForce = Vector3d(idealForwardVel).sub(vel.x(), 0.0, vel.z()).mul(mass * 10)

            val extraForceNeeded = Vector3d(idealForwardForce).sub(baseForwardForce)
            val actualExtraForce = Vector3d(forwardVector).add(baseForwardForce)

            if (extraForce != 0.0) {
                // extraForce gives the amount of force available to us, so this gives us the proportion of the
                // force provided by engines that we're using - we always use 100% if the player is sprinting.
                val usage = if (control.sprintOn) 1.0 else min(extraForceNeeded.length() / extraForce, 1.0)
                physConsumption += usage.toFloat()
                actualExtraForce.fma(extraForce * usage, forwardVector)
            }

            forcesApplier.applyInvariantForce(actualExtraForce)
            // endregion

            // Player controlled elevation
            if (control.upImpulse != 0.0f && balloons > 0) {
                idealUpwardVel = Vector3d(0.0, 1.0, 0.0)
                    .mul(control.upImpulse.toDouble())
                    .mul(EurekaConfig.SERVER.impulseElevationRate.toDouble())
            }
        }

        // region Elevation
        val idealUpwardForce = Vector3d(idealUpwardVel)
            .add(0.0, -vel.y() - GRAVITY, 0.0)
            .mul(mass)

        val balloonForceNeeded = idealUpwardForce.length()
        val balloonForceProvided = balloons * forcePerBalloon

        val actualUpwardForce = Vector3d(0.0, min(balloonForceNeeded, balloonForceProvided), 0.0)
        forcesApplier.applyInvariantForce(actualUpwardForce)
        // endregion

        // region Anchor
        if (wasAnchored != anchored) {
            anchorTargetPos = physShip.poseVel.pos as Vector3d
            anchorTargetRot = physShip.poseVel.rot as Quaterniond
            wasAnchored = anchored
        }
        if (anchored && anchorTargetPos.isFinite) { // TODO: Same thing but with rotation; rotate ship to anchor point
            val x1 = anchorTargetPos.x()
            val z1 = anchorTargetPos.z()
            val x2 = physShip.poseVel.pos.x()
            val z2 = physShip.poseVel.pos.z()
            val targetVel = Vector3d(x1 - x2, 0.0, z1 - z2)
            val len = targetVel.length()
            targetVel.mul(clamp(0.0, anchorSpeed, len * 10.0))
            targetVel.mul(physShip.inertia.shipMass)
            forcesApplier.applyInvariantForce(targetVel)

            val invRotation = physShip.poseVel.rot.invert(Quaterniond())
            val invRotationAxisAngle = AxisAngle4d(invRotation)

            val alignTarget = (anchorTargetRot.angle() / (0.5 * Math.PI))
            val angleUntilAligned = abs((alignTarget * (0.5 * Math.PI)) - invRotationAxisAngle.angle)
            val idealOmega = Vector3d(invRotationAxisAngle.x, invRotationAxisAngle.y, invRotationAxisAngle.z)
                .mul(angleUntilAligned)
                .mul(EurekaConfig.SERVER.stabilizationSpeed)

            val idealTorque = moiTensor.transform(idealOmega)

            forcesApplier.applyInvariantTorque(idealTorque)
        }
        // endregion

        // Drag
        // forcesApplier.applyInvariantForce(Vector3d(vel.y()).mul(-mass))
    }

    var power = 0.0
    var anchors = 0 // Amount of anchors
        set(v) {
            field = v; deleteIfEmpty()
        }

    var anchorsActive = 0 // Anchors that are active
    var balloons = 0 // Amount of balloons
        set(v) {
            field = v; deleteIfEmpty()
        }

    var helms = 0 // Amount of helms
        set(v) {
            field = v; deleteIfEmpty()
        }

    var floaters = 0 // Amount of floaters * 15
        set(v) {
            field = v; deleteIfEmpty()
        }

    override fun tick() {
        extraForce = power
        power = 0.0
        consumed = physConsumption * /* should be phyics ticks based*/ 0.1f
        physConsumption = 0.0f
    }

    private fun deleteIfEmpty() {
        if (helms == 0 && floaters == 0 && anchors == 0 && balloons == 0) {
            ship?.saveAttachment<EurekaShipControl>(null)
        }
    }

    companion object {
        fun getOrCreate(ship: ServerShip): EurekaShipControl {
            return ship.getAttachment<EurekaShipControl>()
                ?: EurekaShipControl().also { ship.saveAttachment(it) }
        }

        private const val ALIGN_THRESHOLD = 0.01
        private const val DISASSEMBLE_THRESHOLD = 0.02
        private val forcePerBalloon get() = EurekaConfig.SERVER.massPerBalloon * -GRAVITY

        private const val GRAVITY = -10.0
    }
}
