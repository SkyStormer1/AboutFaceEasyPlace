package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target({})
public @interface At { String value(); }
