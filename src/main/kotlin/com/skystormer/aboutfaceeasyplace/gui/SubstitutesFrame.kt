package com.skystormer.aboutfaceeasyplace.gui

import com.skystormer.aboutfaceeasyplace.Substitutions
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** What the block substitutes screens have in common: their sizes, going back, and a placement's switch. */
abstract class SubstitutesFrame(private val parent: Screen?, title: Component) : FramedScreen(title) {

    /** Adds [placement]'s switch for substitutes. */
    protected fun addPlacementSwitch(x: Int, y: Int, width: Int, label: Component, placement: SchematicPlacement) {
        addRenderableWidget(
            CycleButton.onOffBuilder(Substitutions.choiceFor(placement).on)
                .create(x, y, width, ROW, label) { _, on -> Substitutions.setOn(placement, on) }
                .also { it.setTooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.switch.tip"))) }
        )
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    protected companion object {
        const val WIDTH = 300
        const val ROW = 20
        const val GAP = 2
    }
}
