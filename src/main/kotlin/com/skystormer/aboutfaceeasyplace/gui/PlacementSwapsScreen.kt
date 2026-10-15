package com.skystormer.aboutfaceeasyplace.gui

import com.skystormer.aboutfaceeasyplace.Substitutions
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * One placement's swaps: its switch, and each of the shared swaps on or off for it alone, for a
 * build where one of them would be wrong. Every change is saved and shown straight away.
 */
class PlacementSwapsScreen(private val parent: Screen?, private val placement: SchematicPlacement) :
    FramedScreen(Component.translatable("gui.aboutfaceeasyplace.placement_swaps", placement.name)) {

    override val resetTip: String
        get() = Component.translatable("gui.aboutfaceeasyplace.placement_swaps.reset").string

    override fun resetToDefaults() {
        Substitutions.choiceFor(placement).off.clear()
        Substitutions.changed()
    }

    override fun content(top: Int) {
        val left = width / 2 - WIDTH / 2
        var y = top
        val choice = Substitutions.choiceFor(placement)
        addRenderableWidget(
            CycleButton.onOffBuilder(choice.on)
                .create(left, y, WIDTH, ROW, Component.translatable("gui.aboutfaceeasyplace.swaps_here")) { _, on -> Substitutions.setOn(placement, on) }
                .also { it.setTooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.swaps.tip"))) }
        )
        y += ROW + GAP * 4

        if (Substitutions.swaps.isEmpty()) {
            addRenderableWidget(StringWidget(left, y, WIDTH, ROW, Component.translatable("gui.aboutfaceeasyplace.no_swaps"), font))
            return
        }
        for (swap in Substitutions.swaps) {
            addRenderableWidget(
                CycleButton.onOffBuilder(swap.id !in choice.off)
                    .create(left, y, WIDTH, ROW, SubstitutionsScreen.label(swap)) { _, on ->
                        if (on) choice.off -= swap.id else choice.off += swap.id
                        Substitutions.changed()
                    }
            )
            y += ROW + GAP
        }
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        const val WIDTH = 300
        const val ROW = 20
        const val GAP = 2
    }
}
