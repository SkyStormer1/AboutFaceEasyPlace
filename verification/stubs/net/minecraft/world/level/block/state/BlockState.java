package net.minecraft.world.level.block.state;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.LinkedHashMap;
import java.util.Map;
public class BlockState {
    private final Block block; private final Map<Property<?>, Comparable<?>> values;
    public BlockState(Block block, Map<Property<?>, Comparable<?>> values) {
        this.block = block; this.values = new LinkedHashMap<>(values);
    }
    public Block getBlock() { return block; }
    @SuppressWarnings("unchecked")
    public <T extends Comparable<T>> T getValue(Property<T> property) { return (T) values.get(property); }
    public <T extends Comparable<T>> boolean hasProperty(Property<T> property) { return values.containsKey(property); }
    @Override public boolean equals(Object o) {
        if (!(o instanceof BlockState)) return false;
        BlockState other = (BlockState) o;
        return other.block == block && other.values.equals(values);
    }
    @Override public int hashCode() { return block.hashCode() * 31 + values.hashCode(); }
    @Override public String toString() { return block.getDescriptionId() + values; }
}
