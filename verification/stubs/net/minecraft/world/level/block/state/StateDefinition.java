package net.minecraft.world.level.block.state;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.Collection;
import java.util.List;
public class StateDefinition<O, S> {
    private final List<Property<?>> properties;
    public StateDefinition(List<Property<?>> properties) { this.properties = properties; }
    public Collection<Property<?>> getProperties() { return properties; }
}
