package com.skystormer.aboutfaceeasyplace.gui

import com.skystormer.aboutfaceeasyplace.Substitutions
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block

/**
 * Block substitution's settings: each Litematica placement loaded now, with its switch and the way
 * to the links it leaves out, then every block with stand-ins, which every placement shares. Every
 * change is saved and shown in the world straight away.
 */
class SubstitutionsScreen(private val parent: Screen?) : FramedScreen(Component.translatable("gui.aboutfaceeasyplace.substitutions")) {

    override val resetTip: String
        get() = Component.translatable("gui.aboutfaceeasyplace.substitutions.reset").string

    override fun resetToDefaults() {
        Substitutions.links.clear()
        Substitutions.placements().forEach { Substitutions.choiceFor(it).apply { on = false; off.clear() } }
        Substitutions.changed()
    }

    override fun content(top: Int) {
        val left = width / 2 - WIDTH / 2
        var y = top

        y = heading(left, y, "gui.aboutfaceeasyplace.placements")
        val placements = Substitutions.placements()
        if (placements.isEmpty()) {
            addRenderableWidget(StringWidget(left, y, WIDTH, ROW, Component.translatable("gui.aboutfaceeasyplace.no_placements"), font))
            y += ROW + GAP
        }
        val nameWidth = WIDTH - SWITCH - EXCEPT - GAP * 2
        for (placement in placements) {
            val choice = Substitutions.choiceFor(placement)
            val name = Button.builder(Component.literal(font.plainSubstrByWidth(placement.name, nameWidth - 8))) {}
                .bounds(left, y, nameWidth, ROW)
                .tooltip(Tooltip.create(Component.literal(placement.name + (placement.schematicFile?.let { "\n" + it.fileName } ?: ""))))
                .build()
            name.active = false
            addRenderableWidget(name)
            addRenderableWidget(
                CycleButton.onOffBuilder(choice.on)
                    .create(left + nameWidth + GAP, y, SWITCH, ROW, Component.translatable("gui.aboutfaceeasyplace.swaps")) { _, on ->
                        Substitutions.setOn(placement, on)
                    }
                    .also { it.setTooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.swaps.tip"))) }
            )
            addRenderableWidget(
                Button.builder(Component.translatable("gui.aboutfaceeasyplace.except")) {
                    minecraft.gui.setScreen(PlacementSwapsScreen(this, placement))
                }
                    .bounds(left + nameWidth + GAP + SWITCH + GAP, y, EXCEPT, ROW)
                    .tooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.except.tip", choice.off.size)))
                    .build()
            )
            y += ROW + GAP
        }

        y += GAP * 3
        y = heading(left, y, "gui.aboutfaceeasyplace.swap_list")
        val remove = 60
        for (block in Substitutions.linkedBlocks()) {
            addRenderableWidget(Button.builder(label(block)) { pickStandIns(setOf(block)) }
                .bounds(left, y, WIDTH - remove - GAP, ROW)
                .tooltip(Tooltip.create(details(block)))
                .build())
            addRenderableWidget(Button.builder(Component.translatable("gui.aboutfaceeasyplace.remove")) {
                Substitutions.setStandIns(block, emptySet())
                Substitutions.changed()
                rebuildWidgets()
            }.bounds(left + WIDTH - remove, y, remove, ROW)
                .tooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.remove.tip", block.name)))
                .build())
            y += ROW + GAP
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.aboutfaceeasyplace.add_swap")) {
            minecraft.gui.setScreen(BlockPickScreen(this, Component.translatable("gui.aboutfaceeasyplace.pick_from"),
                emptySet(), Component.translatable("gui.aboutfaceeasyplace.next")) { blocks -> pickStandIns(blocks) })
        }
            .bounds(left, y, WIDTH, ROW)
            .tooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.add_swap.tip")))
            .build())
    }

    private fun heading(left: Int, y: Int, key: String): Int {
        addRenderableWidget(StringWidget(left, y, WIDTH, font.lineHeight, Component.translatable(key), font))
        return y + font.lineHeight + GAP * 2
    }

    /**
     * Picks the stand-ins for [blocks], each linked both ways. For one block, the picked ones become
     * exactly its stand-ins (one taken out is unlinked); for several, the picked ones are added to each.
     */
    private fun pickStandIns(blocks: Set<Block>) {
        val one = blocks.singleOrNull()
        val title = if (one != null) Component.translatable("gui.aboutfaceeasyplace.pick_to_one", one.name)
        else Component.translatable("gui.aboutfaceeasyplace.pick_to")
        minecraft.gui.setScreen(BlockPickScreen(this, title, one?.let(Substitutions::standInsFor).orEmpty(),
            Component.translatable("gui.aboutfaceeasyplace.save")) { picked ->
            if (one != null) {
                Substitutions.setStandIns(one, picked)
            } else {
                blocks.forEach { block -> Substitutions.setStandIns(block, Substitutions.standInsFor(block) + picked) }
            }
            Substitutions.changed()
            minecraft.gui.setScreen(this)
        })
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    companion object {
        private const val WIDTH = 300
        private const val ROW = 20
        private const val GAP = 2
        private const val SWITCH = 80
        private const val EXCEPT = 70

        /** "Smooth Stone ↔ Cobblestone +2": the block, its first stand-in, and how many more. */
        fun label(block: Block): Component {
            val standIns = Substitutions.standInsFor(block)
            val first = standIns.first().name.copy()
            return Component.translatable("gui.aboutfaceeasyplace.swap", block.name,
                if (standIns.size == 1) first else first.append(" +${standIns.size - 1}"))
        }

        /** Every stand-in of [block], for the tooltip. */
        fun details(block: Block): Component {
            val out = Component.translatable("gui.aboutfaceeasyplace.swap_from", block.name).copy()
            Substitutions.standInsFor(block).forEach { out.append("\n  ").append(it.name) }
            return out.append("\n").append(Component.translatable("gui.aboutfaceeasyplace.swap_edit"))
        }

        fun label(link: Substitutions.Link): Component = Component.translatable("gui.aboutfaceeasyplace.swap", link.a.name, link.b.name)
    }
}
