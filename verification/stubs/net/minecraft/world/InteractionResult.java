package net.minecraft.world;
public interface InteractionResult {
    InteractionResult FAIL = new InteractionResult() {};
    InteractionResult PASS = new InteractionResult() {};
}
