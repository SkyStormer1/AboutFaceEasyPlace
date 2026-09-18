package com.skystormer.aboutfaceeasyplace.mixin;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Asks an item, rather than its block, what it would place.
 *
 * The two differ for every item that places more than one block: a skull, a torch, a banner or a
 * sign goes on the floor or on a wall depending on the click, and only the item knows which. It
 * also applies the item's own checks — whether the block could survive there, whether something is
 * in the way — which are the same checks the server makes before placing anything.
 */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {

    @Invoker("getPlacementState")
    BlockState aboutFaceEasyPlace$getPlacementState(BlockPlaceContext context);
}
