package com.skystormer.aboutfaceeasyplace.gui

import com.skystormer.aboutfaceeasyplace.Substitutions
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * One profile: its name, and its groups of blocks, each a row to click and change in the block grid,
 * or remove. Every change is saved and shown straight away.
 */
class ProfileScreen(private val parent: Screen?, private val profile: Substitutions.Profile) :
    FramedScreen(Component.translatable("gui.aboutfaceeasyplace.profile_title")) {

    override val resetTip: String
        get() = Component.translatable("gui.aboutfaceeasyplace.profile.reset").string

    override fun resetToDefaults() {
        profile.groups.clear()
        Substitutions.changed()
    }

    override fun content(top: Int) {
        val left = width / 2 - WIDTH / 2
        var y = top

        val name = EditBox(font, left, y, WIDTH, ROW, Component.translatable("gui.aboutfaceeasyplace.profile_name"))
        name.setMaxLength(48)
        name.value = profile.name
        name.setResponder { text ->
            profile.name = text.ifBlank { profile.name }
            Substitutions.save()
        }
        addRenderableWidget(name)
        y += ROW + GAP * 4

        addRenderableWidget(StringWidget(left, y, WIDTH, font.lineHeight, Component.translatable("gui.aboutfaceeasyplace.groups"), font))
        y += font.lineHeight + GAP * 2
        val remove = 60
        profile.groups.toList().forEachIndexed { index, group ->
            addRenderableWidget(Button.builder(SubstitutionsScreen.label(group)) { pick(index) }
                .bounds(left, y, WIDTH - remove - GAP, ROW)
                .tooltip(Tooltip.create(SubstitutionsScreen.details(group)))
                .build())
            addRenderableWidget(SubstitutionsScreen.deleteButton(left + WIDTH - remove, y, remove) {
                profile.groups.removeAt(index)
                Substitutions.changed()
                rebuildWidgets()
            })
            y += ROW + GAP
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.aboutfaceeasyplace.new_group")) { pick(null) }
            .bounds(left, y, WIDTH, ROW)
            .tooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.new_group.tip")))
            .build())
    }

    /** Picks the blocks of the group at [index], or of a new group. */
    private fun pick(index: Int?) {
        val group = index?.let { profile.groups.getOrNull(it) }.orEmpty()
        minecraft.gui.setScreen(BlockPickScreen(this, Component.translatable("gui.aboutfaceeasyplace.pick_group"), group,
            Component.translatable("gui.aboutfaceeasyplace.save")) { picked ->
            profile.setGroup(index, picked)
            Substitutions.changed()
            minecraft.gui.setScreen(this)
        })
    }

    override fun onClose() {
        Substitutions.save()
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        const val WIDTH = 300
        const val ROW = 20
        const val GAP = 2
    }
}
