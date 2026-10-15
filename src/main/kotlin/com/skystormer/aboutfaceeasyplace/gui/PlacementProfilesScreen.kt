package com.skystormer.aboutfaceeasyplace.gui

import com.skystormer.aboutfaceeasyplace.Substitutions
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * One placement's substitutes: its switch, each of your profiles on or off for it alone, and the way
 * to make or change profiles. Every change is saved and shown straight away.
 */
class PlacementProfilesScreen(private val parent: Screen?, private val placement: SchematicPlacement) :
    FramedScreen(Component.translatable("gui.aboutfaceeasyplace.placement_title", placement.name)) {

    override val resetTip: String
        get() = Component.translatable("gui.aboutfaceeasyplace.placement.reset").string

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
                .create(left, y, WIDTH, ROW, Component.translatable("gui.aboutfaceeasyplace.substitutes_here")) { _, on -> Substitutions.setOn(placement, on) }
                .also { it.setTooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.switch.tip"))) }
        )
        y += ROW + GAP * 4

        for (profile in Substitutions.profiles) {
            addRenderableWidget(
                CycleButton.onOffBuilder(profile.id !in choice.off)
                    .create(left, y, WIDTH, ROW, Component.literal(profile.name)) { _, on ->
                        if (on) choice.off -= profile.id else choice.off += profile.id
                        Substitutions.changed()
                    }
            )
            y += ROW + GAP
        }
        // To make or change profiles without leaving Litematica.
        addRenderableWidget(Button.builder(Component.translatable("gui.aboutfaceeasyplace.edit_profiles")) {
            minecraft.gui.setScreen(SubstitutionsScreen(this))
        }.bounds(left, y + GAP * 3, WIDTH, ROW).build())
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
