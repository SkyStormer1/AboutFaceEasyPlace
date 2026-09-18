package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Turns the server's idea of the player's head before a block that needs it is clicked.
 *
 * The same idea About Face uses: arrange the answer in advance rather than at the moment of
 * placement. Every tick, while Easy Place is on, this finds the schematic block under the
 * crosshair — the one Easy Place would place next — and asks whether it is one of the blocks that
 * read the head. If it is, the rotation it needs is claimed now, and by the time the click comes
 * the server's head has long since turned. The placement then goes through at once, as fast as any
 * other block.
 *
 * Nothing is claimed for anything else, so for most of a build this does nothing at all. What the
 * server — and anyone watching — sees is the player's head turned towards where the piston needs
 * it while they look at one.
 */
object Anticipation {

    /** How finely the line of sight is stepped through, in blocks. */
    private const val STEP = 0.05

    private var lastPos: BlockPos? = null
    private var lastWanted: BlockState? = null
    private var lastClaim: Aligner.Rotation? = null

    fun tick(minecraft: Minecraft) {
        val player = minecraft.player ?: return clear()
        val connection = minecraft.connection ?: return clear()
        if (!Config.enabled || Litematica.unavailable) return release(player)
        if (RotationHold.busy) return
        if (minecraft.gui.screen() != null || !Litematica.easyPlaceActive() || !Engagement.shouldAlign()) {
            return release(player)
        }
        val schematic = Litematica.schematicWorld() ?: return release(player)
        val hit = targetUnderCrosshair(player, schematic) ?: return release(player)
        val pos = hit.blockPos
        val wanted = schematic.getBlockState(pos)

        // Worked out once per block looked at, not once per tick: the answer depends on the block
        // and where the player stands, and a player placing a row of pistons looks at each in turn.
        if (pos != lastPos || wanted != lastWanted) {
            lastPos = pos
            lastWanted = wanted
            lastClaim = claimFor(player, schematic, hit, wanted)
        }
        RotationHold.anticipate(lastClaim, player, connection)
    }

    /**
     * The rotation to claim ahead of [wanted], or null if it needs none.
     *
     * Planned as though nothing were claimed, with the item the schematic calls for: the question
     * is whether this block would need the head turned, not whether it would now that it is.
     */
    private fun claimFor(player: LocalPlayer, schematic: BlockGetter, hit: BlockHitResult, wanted: BlockState): Aligner.Rotation? {
        if (wanted.isAir) return null
        val stack = ItemStack(wanted.block.asItem())
        if (stack.isEmpty) return null
        val real = Aligner.Rotation(player.yRot, player.xRot)
        val outcome = try {
            Aligner.plan(player, InteractionHand.MAIN_HAND, stack, hit, schematic, real, listOf(player.yRot, player.yRotO).distinct())
        } catch (e: Exception) {
            return null
        } catch (e: LinkageError) {
            return null
        }
        return (outcome as? Aligner.Outcome.Aligned)?.takeIf { it.turnsHead }?.placement?.rotation
    }

    /**
     * The first space along the line of sight where the schematic wants a block and the world has
     * room for one — which is where Easy Place looks too, give or take the edge of a block. A real
     * block in the way ends the search, as it ends Easy Place's.
     *
     * Getting this slightly wrong costs only speed: a block that was not anticipated falls back to
     * a claim held for a couple of ticks at the moment it is placed.
     */
    private fun targetUnderCrosshair(player: LocalPlayer, schematic: BlockGetter): BlockHitResult? {
        val level = player.level()
        val eye = player.eyePosition
        val look = player.lookAngle
        val reach = player.blockInteractionRange()
        var previous: BlockPos? = null
        var distance = 0.0
        while (distance <= reach) {
            val point = eye.add(look.scale(distance))
            val pos = BlockPos.containing(point)
            if (pos != previous) {
                previous = pos
                val inWorld = level.getBlockState(pos)
                if (!inWorld.canBeReplaced()) return null
                if (!schematic.getBlockState(pos).isAir) {
                    return BlockHitResult(point, player.direction.opposite, pos, false)
                }
            }
            distance += STEP
        }
        return null
    }

    /** Stops claiming anything ahead, and puts the server back to the player's own rotation. */
    private fun release(player: LocalPlayer) {
        lastPos = null
        lastWanted = null
        lastClaim = null
        val connection = Minecraft.getInstance().connection ?: return
        RotationHold.anticipate(null, player, connection)
    }

    private fun clear() {
        lastPos = null
        lastWanted = null
        lastClaim = null
    }
}
