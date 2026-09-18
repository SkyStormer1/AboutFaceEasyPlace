package org.jetbrains.annotations;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS) @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface Nullable {}
