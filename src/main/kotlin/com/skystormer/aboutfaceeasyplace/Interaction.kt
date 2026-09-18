package com.skystormer.aboutfaceeasyplace

import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/**
 * Which blocks do something when clicked, and so must never be clicked to place against.
 *
 * A click on a dropper opens it; a click on a repeater changes its delay; a click on a door swings
 * it. The server tries the clicked block's own use first and only places anything if the block
 * declines, so a placement aimed at one of these is not a placement at all.
 *
 * The test is whether the block's class, or anything between it and [BlockBehaviour], provides its
 * own use handling, plus whether it opens a menu. That answers for modded blocks as well as
 * vanilla ones without a list, and errs towards "interactive": a block that only sometimes does
 * something when used is still avoided, which costs nothing, because there is always the target's
 * own space to click instead.
 */
object Interaction {

    private val cache = HashMap<Class<*>, Boolean>()

    private val USE_WITHOUT_ITEM = arrayOf(
        BlockState::class.java, Level::class.java, BlockPos::class.java,
        Player::class.java, BlockHitResult::class.java,
    )

    private val USE_ITEM_ON = arrayOf(
        ItemStack::class.java, BlockState::class.java, Level::class.java, BlockPos::class.java,
        Player::class.java, InteractionHand::class.java, BlockHitResult::class.java,
    )

    fun isInteractive(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (state.isAir) return false
        if (state.getMenuProvider(level, pos) != null) return true
        return overridesUse(state.block)
    }

    private fun overridesUse(block: Block): Boolean = cache.getOrPut(block.javaClass) {
        var type: Class<*>? = block.javaClass
        while (type != null && type != Block::class.java && type != BlockBehaviour::class.java) {
            if (declares(type, "useWithoutItem", USE_WITHOUT_ITEM) || declares(type, "useItemOn", USE_ITEM_ON)) {
                return@getOrPut true
            }
            type = type.superclass
        }
        false
    }

    private fun declares(type: Class<*>, name: String, parameters: Array<Class<*>>): Boolean =
        try {
            type.getDeclaredMethod(name, *parameters)
            true
        } catch (_: NoSuchMethodException) {
            false
        }
}
