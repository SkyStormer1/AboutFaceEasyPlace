package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.lang.reflect.Field

/**
 * Lets Easy Place put down a wall-mounted block that does not need its wall.
 *
 * Called from Litematica's own special case for torches, banners, signs and skulls — see
 * `LitematicaEasyPlaceUtilsMixin` for what that special case gets wrong on a vanilla server. Here
 * the question is only whether to switch it off for one block, and the answer is the block's own:
 * if it would survive at the target with nothing to hold it up, it has no need of the special case,
 * and an ordinary click on the target lets this mod choose the face and the look that mount it the
 * right way round. A skull on a wall is the everyday case. A wall torch is not released, because it
 * falls off without its wall, and Litematica's refusal is then the right answer.
 */
object WallMounts {

    private var handledField: Field? = null
    private var mustFailField: Field? = null
    private var reportedFailure = false

    @JvmStatic
    fun release(data: Any?, pos: BlockPos, wanted: BlockState) {
        if (data == null || reportedFailure) return
        if (!Config.enabled || Litematica.unavailable) return
        // On a Carpet or Servux server Litematica's own protocol carries the orientation, and its
        // special case is part of how it does so.
        if (!Engagement.shouldAlign()) return
        val level = Minecraft.getInstance().level ?: return
        if (!level.getBlockState(pos).canBeReplaced()) return
        if (!wanted.canSurvive(level, pos)) return

        try {
            val handled = handledField ?: field(data, "handled").also { handledField = it }
            val mustFail = mustFailField ?: field(data, "mustFail").also { mustFailField = it }
            if (!handled.getBoolean(data)) return
            handled.setBoolean(data, false)
            mustFail.setBoolean(data, false)
        } catch (e: ReflectiveOperationException) {
            // Litematica has changed shape underneath this. Nothing is lost but this one
            // improvement: its own behaviour carries on exactly as it would without this mod.
            reportedFailure = true
            Log.error("could not read Litematica's placement data; wall-mounted blocks are left to Litematica", e)
        }
    }

    private fun field(data: Any, name: String): Field =
        data.javaClass.getDeclaredField(name).also { it.isAccessible = true }
}
