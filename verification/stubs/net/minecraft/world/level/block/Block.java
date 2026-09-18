package net.minecraft.world.level.block;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.jetbrains.annotations.Nullable;
import java.util.Collections;
import java.util.List;
public class Block {
    @Nullable public BlockState getStateForPlacement(BlockPlaceContext context) { return null; }
    public StateDefinition<Block, BlockState> getStateDefinition() {
        return new StateDefinition<>(Collections.<net.minecraft.world.level.block.state.properties.Property<?>>emptyList());
    }
    public Component getName() { return null; }
    public String getDescriptionId() { return getClass().getSimpleName(); }
}
