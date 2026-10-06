package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Adds to blocks that come in several to a space — candles, sea pickles, turtle eggs, petals, leaf
 * litter, snow layers — until there are as many as the schematic has.
 *
 * Easy Place puts the first one down and then never comes back: once the space holds the right
 * block, it has no notion of that block needing more of itself. So while Easy Place is on and its
 * key is held, the block under the crosshair is topped up one at a time, the way a player adds a
 * candle — by clicking it with another.
 *
 * Every click is first asked of the item, as the search asks it: one that would not come out
 * exactly one closer to the schematic, with everything else unchanged, is never made. A pink petal
 * patch facing the wrong way is a wrong block, and is left to be broken rather than added to.
 */
object TopUp {

    /** Ticks between clicks: one a tick sits well inside what Spigot and Paper accept. */
    private const val INTERVAL_TICKS = 1

    private val COUNTS: List<IntegerProperty> = listOf(
        BlockStateProperties.CANDLES,
        BlockStateProperties.PICKLES,
        BlockStateProperties.EGGS,
        BlockStateProperties.FLOWER_AMOUNT,
        BlockStateProperties.SEGMENT_AMOUNT,
        BlockStateProperties.LAYERS,
    )

    /** Settled by things other than the placement: water, and a candle being lit. */
    private val IGNORED: Set<Property<*>> = setOf(BlockStateProperties.WATERLOGGED, BlockStateProperties.LIT)

    private var cooldown = 0

    /** The last reason given for not adding to a block, so each is said once rather than every tick. */
    private var lastSkip: String? = null

    fun tick(minecraft: Minecraft) {
        if (cooldown > 0 && --cooldown > 0) return
        val player = minecraft.player ?: return
        val gameMode = minecraft.gameMode ?: return
        if (!Config.enabled || Litematica.unavailable) return
        if (minecraft.gui.screen() != null) return
        // Litematica's own key first: it takes the use key's press for itself, so the use key may
        // never read as held while Easy Place is working.
        val held = Litematica.easyPlaceKeyHeld() == true || minecraft.options.keyUse.isDown
        if (!held || !Litematica.easyPlaceActive() || !Engagement.shouldAlign()) return
        val schematic = Litematica.schematicWorld() ?: return
        val aimed = minecraft.hitResult as? BlockHitResult ?: return
        if (aimed.type != HitResult.Type.BLOCK) return
        val level = player.level()

        // A candle is small enough that the crosshair often lands on the block under it instead.
        for (pos in listOf(aimed.blockPos.immutable(), aimed.blockPos.relative(aimed.direction))) {
            val existing = level.getBlockState(pos)
            val wanted = schematic.getBlockState(pos)
            val count = countOf(existing) ?: continue
            if (existing.block !== wanted.block || existing.getValue(count) >= wanted.getValue(count)) continue
            if (!sameApartFrom(existing, wanted, count)) {
                skipped(pos, "it is not the schematic's $wanted apart from how many")
                continue
            }

            if (player.isShiftKeyDown) return skipped(pos, "sneaking")
            // A click now would be made under a claim meant for another block.
            if (RotationHold.busy) return skipped(pos, "a rotation claim is being held")
            val hand = handHolding(player, wanted) ?: return skipped(pos, "${wanted.block.asItem()} is not in either hand")
            if (!player.isWithinBlockInteractionRange(pos, 1.0)) return skipped(pos, "out of reach")

            val click = BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)
            val stack = player.getItemInHand(hand)
            val result = Placing.stateFor(stack.item as BlockItem, BlockPlaceContext(player, hand, stack, click))
            if (result == null || result.block !== wanted.block || result.getValue(count) != existing.getValue(count) + 1 ||
                !sameApartFrom(result, wanted, count)
            ) {
                return skipped(pos, "a click would make $result")
            }

            Log.detail("adding to {} at {}: {} of {}", existing.block.descriptionId, pos, existing.getValue(count) + 1, wanted.getValue(count))
            EasyPlaceHook.placeUnaligned(gameMode, player, hand, click)
            cooldown = INTERVAL_TICKS
            return
        }
    }

    private fun skipped(pos: BlockPos, why: String) {
        val key = "$pos $why"
        if (key == lastSkip) return
        lastSkip = key
        Log.detail("not adding to the block at {}: {}", pos, why)
    }

    private fun countOf(state: BlockState): IntegerProperty? = COUNTS.firstOrNull { state.hasProperty(it) }

    private fun sameApartFrom(a: BlockState, b: BlockState, count: IntegerProperty): Boolean =
        b.block.stateDefinition.properties.all { it == count || it in IGNORED || BlockStates.agree(a, b, it) }

    private fun handHolding(player: LocalPlayer, wanted: BlockState): InteractionHand? {
        val item = wanted.block.asItem()
        return InteractionHand.entries.firstOrNull { player.getItemInHand(it).item === item }
    }

    fun forget() {
        cooldown = 0
        lastSkip = null
    }
}
