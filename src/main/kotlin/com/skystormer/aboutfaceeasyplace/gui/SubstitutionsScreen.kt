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
 * Block substitutes: your profiles, to make, open and delete, then each Litematica placement loaded
 * now, with its switch and the profiles it uses. Every change is saved and shown straight away.
 */
class SubstitutionsScreen(private val parent: Screen?) : FramedScreen(Component.translatable("gui.aboutfaceeasyplace.substitutes")) {

    override val resetTip: String
        get() = Component.translatable("gui.aboutfaceeasyplace.substitutes.reset").string

    override fun resetToDefaults() {
        Substitutions.placements().forEach { Substitutions.choiceFor(it).apply { on = false; off.clear() } }
        Substitutions.changed()
    }

    override fun content(top: Int) {
        val left = width / 2 - WIDTH / 2
        var y = top

        y = heading(left, y, "gui.aboutfaceeasyplace.profiles")
        val delete = 60
        for (profile in Substitutions.profiles.toList()) {
            addRenderableWidget(Button.builder(Component.literal(profile.name)) {
                minecraft.gui.setScreen(ProfileScreen(this, profile))
            }
                .bounds(left, y, WIDTH - delete - GAP, ROW)
                .tooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.profile.tip", profile.groups.size)))
                .build())
            addRenderableWidget(deleteButton(left + WIDTH - delete, y, delete) {
                Substitutions.profiles.remove(profile)
                Substitutions.changed()
                rebuildWidgets()
            })
            y += ROW + GAP
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.aboutfaceeasyplace.new_profile")) {
            val profile = Substitutions.newProfile(Component.translatable("gui.aboutfaceeasyplace.new_profile_name", Substitutions.profiles.size + 1).string)
            Substitutions.changed()
            minecraft.gui.setScreen(ProfileScreen(this, profile))
        }.bounds(left, y, WIDTH, ROW).build())
        y += ROW + GAP * 4

        y = heading(left, y, "gui.aboutfaceeasyplace.placements")
        val placements = Substitutions.placements()
        if (placements.isEmpty()) {
            addRenderableWidget(StringWidget(left, y, WIDTH, ROW, Component.translatable("gui.aboutfaceeasyplace.no_placements"), font))
        }
        val nameWidth = WIDTH - SWITCH - PROFILES - GAP * 2
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
                    .create(left + nameWidth + GAP, y, SWITCH, ROW, Component.translatable("gui.aboutfaceeasyplace.substitutes_short")) { _, on ->
                        Substitutions.setOn(placement, on)
                    }
                    .also { it.setTooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.switch.tip"))) }
            )
            addRenderableWidget(Button.builder(Component.translatable("gui.aboutfaceeasyplace.profiles_button")) {
                minecraft.gui.setScreen(PlacementProfilesScreen(this, placement))
            }
                .bounds(left + nameWidth + GAP + SWITCH + GAP, y, PROFILES, ROW)
                .tooltip(Tooltip.create(Component.translatable("gui.aboutfaceeasyplace.profiles_button.tip")))
                .build())
            y += ROW + GAP
        }
    }

    private fun heading(left: Int, y: Int, key: String): Int {
        addRenderableWidget(StringWidget(left, y, WIDTH, font.lineHeight, Component.translatable(key), font))
        return y + font.lineHeight + GAP * 2
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    companion object {
        private const val WIDTH = 300
        private const val ROW = 20
        private const val GAP = 2
        private const val SWITCH = 96
        private const val PROFILES = 70

        /** A Delete button that asks first: the first click only changes it to "Sure?". */
        fun deleteButton(x: Int, y: Int, width: Int, onDelete: () -> Unit): Button {
            var armed = false
            return Button.builder(Component.translatable("gui.aboutfaceeasyplace.delete")) { button ->
                if (armed) onDelete() else {
                    armed = true
                    button.message = Component.translatable("gui.aboutfaceeasyplace.delete_sure")
                }
            }.bounds(x, y, width, 20).build()
        }

        /** "Glass, White Stained Glass +15": the first two blocks of a group, and how many more. */
        fun label(group: Set<Block>): Component {
            val out = Component.empty()
            group.take(2).forEachIndexed { i, block -> if (i > 0) out.append(", "); out.append(block.name) }
            if (group.size > 2) out.append(" +${group.size - 2}")
            return out
        }

        /** Every block of a group, for its tooltip. */
        fun details(group: Set<Block>): Component {
            val out = Component.translatable("gui.aboutfaceeasyplace.group.tip").copy()
            group.forEach { out.append("\n  ").append(it.name) }
            return out
        }
    }
}
