package com.skystormer.aboutfaceeasyplace

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.AxeItem
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Makes stripped wood out of the unstripped kind. Where the schematic has a stripped log or wood
 * block and you have none of those, but do have the block it is stripped from and an axe, Easy Place
 * puts that block down along the same axis and this strips it, which costs the axe what stripping
 * by hand does. An axe with one use left is not used, so nothing here ever breaks one.
 *
 * Stripping happens a tick or two after the placement rather than as part of it, which works the same
 * whichever of this mod or Litematica's own protocol made the placement. The axe is only in the hand
 * for the click: from anywhere in the inventory, and put back straight after.
 */
object Stripping {

    /** How long to wait for the block to show up before giving up on it. */
    private const val PATIENCE_TICKS = 40

    /** Ticks to let the server's answer replace the client's own prediction of the block. */
    private const val SETTLE_TICKS = 2

    /** Ticks to wait for the server's answer to a click before making it again. */
    private const val RETRY_TICKS = 10

    /** Clicks to make before leaving the block as it is. */
    private const val MAX_CLICKS = 3

    private class Expected(
        val pos: BlockPos,
        val source: Block,
        val stripped: Block,
        var ticks: Int = 0,
        /** Ticks since the last click; starts full, so the first click need not wait. */
        var quiet: Int = RETRY_TICKS,
        var clicks: Int = 0,
    )

    private val expected = ArrayList<Expected>()

    /** Each stripped block, with the block it is stripped from: the game names them that way. */
    private val sources: Map<Block, Block> by lazy {
        val out = HashMap<Block, Block>()
        for (block in BuiltInRegistries.BLOCK) {
            val id = BuiltInRegistries.BLOCK.getKey(block)
            if (!id.path.startsWith("stripped_")) continue
            val sourceId = Identifier.fromNamespaceAndPath(id.namespace, id.path.removePrefix("stripped_"))
            val source = BuiltInRegistries.BLOCK.getOptional(sourceId).orElse(null)
            if (source != null && source.asItem() is BlockItem) out[block] = source
        }
        out
    }

    fun sourceOf(stripped: Block): Block? = sources[stripped]

    /**
     * What Easy Place should put down to end up with [wanted], or null to put down [wanted] itself:
     * the block it is stripped from, when that and an axe are what you have.
     */
    fun toPlace(wanted: BlockState, player: LocalPlayer): BlockState? {
        val source = sources[wanted.block] ?: return null
        if (!Config.enabled || !Config.autoStrip || player.hasInfiniteMaterials()) return null
        if (Substitutions.has(player, wanted.block) || !Substitutions.has(player, source) || axeSlot(player) == null) return null
        return Substitutions.substituted(wanted, source)
    }

    /** A placement is being made at [pos] with [stack]: strips the block once it is down, if that is what it is for. */
    fun expect(pos: BlockPos, stack: ItemStack) {
        if (!Config.autoStrip) return
        val wanted = Litematica.schematicAsItIs()?.getBlockState(pos) ?: return
        val source = sources[wanted.block] ?: return
        if ((stack.item as? BlockItem)?.block !== source || expected.any { it.pos == pos }) return
        // Where the unstripped block is an allowed substitute it is already right, and worth no durability.
        if (Substitutions.matches(pos.x, pos.y, pos.z, wanted, Substitutions.substituted(wanted, source))) return
        expected += Expected(pos, source, wanted.block)
    }

    fun tick(minecraft: Minecraft) {
        if (expected.isEmpty()) return
        val player = minecraft.player ?: return forget()
        val level = player.level()

        val entries = expected.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            entry.ticks++
            val block = level.getBlockState(entry.pos).block
            if (block === entry.stripped) {
                Log.detail("stripped {} at {} with {} click(s)", entry.source.descriptionId, entry.pos, entry.clicks)
                entries.remove()
            } else if (block !== entry.source) {
                // Not down yet, or broken since: nothing to strip either way.
                if (entry.clicks > 0 || entry.ticks > PATIENCE_TICKS) entries.remove()
            } else if (!player.isWithinBlockInteractionRange(entry.pos, 1.0)) {
                Log.detail("{} at {} went out of reach before it was stripped", entry.source.descriptionId, entry.pos)
                entries.remove()
            } else if (entry.ticks > SETTLE_TICKS && ++entry.quiet > RETRY_TICKS && minecraft.gui.screen() == null && !RotationHold.busy) {
                if (entry.clicks >= MAX_CLICKS) {
                    Log.warn("{} at {} is still not stripped after {} clicks with an axe", entry.source.descriptionId, entry.pos, entry.clicks)
                    entries.remove()
                } else if (strip(minecraft, player, entry.pos)) {
                    entry.clicks++
                    entry.quiet = 0
                } else {
                    Log.detail("no axe left to strip {} at {} with", entry.source.descriptionId, entry.pos)
                    entries.remove()
                }
            }
        }
    }

    /** One click on [pos] with an axe in the main hand, wherever in the inventory it is kept; false with no axe. */
    private fun strip(minecraft: Minecraft, player: LocalPlayer, pos: BlockPos): Boolean {
        val gameMode = minecraft.gameMode ?: return false
        val connection = minecraft.connection ?: return false
        val slot = axeSlot(player) ?: return false
        val inventory = player.inventory
        val selected = inventory.selectedSlot
        // A shield in the other hand stops an axe stripping, unless the player is crouching.
        val sneak = player.lastSentInput.shift() || player.offhandItem.has(DataComponents.BLOCKS_ATTACKS)

        fun click() = EasyPlaceHook.useBlock(gameMode, player, connection, pos, sneak)

        if (Inventory.isHotbarSlot(slot)) {
            inventory.selectedSlot = slot
            click()
            inventory.selectedSlot = selected
        } else {
            val menu = player.inventoryMenu.containerId
            gameMode.handleContainerInput(menu, slot, selected, ContainerInput.SWAP, player)
            click()
            gameMode.handleContainerInput(menu, slot, selected, ContainerInput.SWAP, player)
        }
        return true
    }

    /** Where the axe to use is: the one in the hand if that will do, or else the first in the inventory. */
    private fun axeSlot(player: LocalPlayer): Int? {
        val inventory = player.inventory
        if (usableAxe(inventory.getItem(inventory.selectedSlot))) return inventory.selectedSlot
        return (0 until Inventory.INVENTORY_SIZE).firstOrNull { usableAxe(inventory.getItem(it)) }
    }

    private fun usableAxe(stack: ItemStack): Boolean =
        stack.item is AxeItem &&
            (!stack.isDamageableItem || stack.damageValue < stack.maxDamage - 1)

    fun forget() = expected.clear()
}
