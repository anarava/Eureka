package org.valkyrienskies.eureka.util

import it.unimi.dsi.fastutil.Stack
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Registry
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Blocks
import org.joml.Vector3i
import org.valkyrienskies.core.api.Ship
import org.valkyrienskies.core.game.ships.ShipData
import org.valkyrienskies.eureka.EurekaConfig
import org.valkyrienskies.mod.common.shipObjectWorld
import org.valkyrienskies.mod.common.util.relocateBlock
import org.valkyrienskies.mod.common.util.toBlockPos

object ShipAssembler {
    val AIR = Blocks.AIR.defaultBlockState()

    // TODO use dense packed to send updates
    // with a more optimized algorithm for bigger ships

    fun fillShip(level: ServerLevel, ship: ShipData, center: BlockPos) {
        val shipCenter = ship.chunkClaim.getCenterBlockCoordinates(Vector3i()).toBlockPos()
        level.relocateBlock(center, shipCenter, ship)

        level.shipObjectWorld.shipLoadEvent.on { evt, _ -> println("Ship loaded: ${evt.ship.shipData.id}")}

        // wait until this ship is loaded to copy blocks
        level.shipObjectWorld.shipLoadEvent.once({ it.ship.shipData == ship }) {
            val stack = ObjectArrayList<Triple<BlockPos, BlockPos, Direction>>()
            Direction.values()
                .forEach { forwardAxis(level, shipCenter.relative(it), center, center.relative(it), it, ship, stack) }

            while (!stack.isEmpty) {
                val (to, from, dir) = stack.pop()
                forwardAxis(level, to, center, from, dir, ship, stack)
            }
        }
    }

    private fun forwardAxis(
        level: ServerLevel,
        shipPos: BlockPos,
        center: BlockPos,
        pos: BlockPos,
        direction: Direction,
        ship: Ship,
        stack: Stack<Triple<BlockPos, BlockPos, Direction>>
    ) {
        var pos = pos
        var shipPos = shipPos
        var blockState = level.getBlockState(pos)
        var depth = 0

        while (!EurekaConfig.SERVER.blockBlacklist.contains(Registry.BLOCK.getKey(blockState.block).toString())) {
            if (!pos.closerThan(center, 32.0 * 16.0)) return

            level.relocateBlock(pos, shipPos, ship)
            depth++

            Direction.values().filter { it != direction && it != direction.opposite }
                .forEach { stack.push(Triple(shipPos.relative(it), pos.relative(it), it)) }

            pos = pos.relative(direction)
            shipPos = shipPos.relative(direction)
            blockState = level.getBlockState(pos)
        }

        /*
        pos = pos.relative(direction.opposite, depth)

        repeat(depth) {
            val from1 = pos.relative(direction, it + 1)
            val from2 = pos.relative(direction, it + 1)
            level.neighborChanged(pos.relative(direction, it), level.getBlockState(from1).block, from1)
            level.neighborChanged(pos.relative(direction, it), level.getBlockState(from2).block, from2)
        }
        */
    }
}
