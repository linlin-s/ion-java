// Copyright (c) 2022 Amazon.com, Inc.  All rights reserved.

package com.amazon.fusion.util.function;

/**
 * Simulates Java 8's {@code java.util.function.BiFunction}.
 * <p>
 * <b>Not for application use!</b>
 * </p>
 */
public interface BiFunction<T, U, R>
{
    R apply(T t, U u);
}
