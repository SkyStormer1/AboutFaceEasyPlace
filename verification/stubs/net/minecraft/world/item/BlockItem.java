package net.minecraft.world.item;
import net.minecraft.world.level.block.Block;
public class BlockItem extends Item {
    private final Block block;
    public BlockItem(Block block) { this.block = block; }
    public Block getBlock() { return block; }
}
