package com.skystormer.aboutfaceeasyplace.gui

import com.skystormer.aboutfaceeasyplace.Substitutions
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Block substitution's settings: each Litematica placement loaded now, with its switch and the way
 * to the swaps it leaves out, then the swaps themselves, which every placement shares. Every change
 * is saved and shown in the world straight away.
 */
class SubstitutionsScreen(private val parent: Screen?) : FramedScreen(Component.translatable("gui.aboutfaceeasyplace.substitutions")) {

    override val resetTip: String
        get() = Component.translatable("gui.aboutfaceeasyplace.substitutions.reset").string

    override fun resetToDefaults() {
        Substitutions.swaps.clear()
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
        for (swap in Substitutions.swaps.toList()) {
            val label = Button.builder(label(swap)) {}.bounds(left, y, WIDTH - remove - GAP, ROW).build()
            label.active = false
            addRenderableWidget(label)
            addRenderableWidget(Button.builder(Component.translatable("gui.aboutfaceeasyplace.remove")) {
                Substitutions.swaps.remove(swap)
                Substitutions.changed()
                rebuildWidgets()
            }.bounds(left + WIDTH - remove, y, remove, ROW).build())
            y += ROW + GAP
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.aboutfaceeasyplace.add_swap")) { pickFrom() }
            .bounds(left, y, WIDTH, ROW)
            .tooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.add_swap.tip")))
            .build())
    }

    private fun heading(left: Int, y: Int, key: String): Int {
        addRenderableWidget(StringWidget(left, y, WIDTH, font.lineHeight, Component.translatable(key), font))
        return y + font.lineHeight + GAP * 2
    }

    /** Picks the schematic's block, then what may stand in for it, and adds the swap. */
    private fun pickFrom() {
        minecraft.gui.setScreen(BlockPickScreen(this, Component.translatable("gui.aboutfaceeasyplace.pick_from")) { from ->
            minecraft.gui.setScreen(BlockPickScreen(this, Component.translatable("gui.aboutfaceeasyplace.pick_to", from.name)) { to ->
                val swap = Substitutions.Swap(from, to)
                if (from !== to && swap !in Substitutions.swaps) {
                    Substitutions.swaps += swap
                    Substitutions.changed()
                }
                minecraft.gui.setScreen(this)
            })
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

        fun label(swap: Substitutions.Swap): Component =
            Component.translatable("gui.aboutfaceeasyplace.swap", swap.from.name, swap.to.name)
    }
}
