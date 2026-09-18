package com.skystormer.aboutfaceeasyplace;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Reads a block state property whose type is not known at the call site.
 *
 * Everything else in this mod is Kotlin, and this one operation is here in Java for a reason:
 * {@code BlockState.getValue} is declared over {@code Property<T extends Comparable<T>>}, and
 * there is no way to name that {@code T} when the property came out of a collection. Java's raw
 * types let the question be asked once, here, rather than worked around everywhere it comes up.
 */
public final class BlockStates {

    private BlockStates() {
    }

    /** The value this state holds for {@code property}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Comparable<?> read(BlockState state, Property<?> property) {
        return state.getValue((Property) property);
    }

    /** Whether two states agree about {@code property}. */
    public static boolean agree(BlockState a, BlockState b, Property<?> property) {
        return read(a, property).equals(read(b, property));
    }
}
