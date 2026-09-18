package com.skystormer.aboutfaceeasyplace;

import com.skystormer.aboutfaceeasyplace.mixin.BlockItemAccessor;
import com.skystormer.aboutfaceeasyplace.mixin.LocalPlayerAccessor;
import com.skystormer.aboutfaceeasyplace.mixin.TrapDoorBlockAccessor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Everything that goes through an accessor mixin, in one place.
 *
 * In Java because the accessors are mixin interfaces, which a Kotlin cast would name by their
 * {@code $}-prefixed methods; and kept together so that nothing else needs to know they exist.
 */
public final class Placing {

    /**
     * The head yaw the client's player reports while set; see {@code LocalPlayerViewMixin}. Set
     * only for the length of a simulation or a placement, on the client thread.
     */
    public static Float headOverride = null;

    private Placing() {
    }

    /**
     * What {@code item} would place for {@code context}, or null if it would place nothing.
     *
     * This is the question the server itself asks, by the same route, so an answer here is an
     * answer about the server.
     */
    public static BlockState stateFor(BlockItem item, BlockPlaceContext context) {
        BlockPlaceContext updated = item.updatePlacementContext(context);
        if (updated == null) {
            return null;
        }
        return ((BlockItemAccessor) item).aboutFaceEasyPlace$getPlacementState(updated);
    }

    /** The yaw the client last reported to the server. */
    public static float lastSentYaw(LocalPlayer player) {
        return ((LocalPlayerAccessor) player).aboutFaceEasyPlace$getYRotLast();
    }

    /** Whether a trapdoor can be opened with a click, which an iron one cannot. */
    public static boolean opensByHand(TrapDoorBlock block) {
        return ((TrapDoorBlockAccessor) block).aboutFaceEasyPlace$getType().canOpenByHand();
    }
}
