// Copyright (c) 2015-2022 Amazon.com, Inc.  All rights reserved.

package com.amazon.fusion.util.function;

/**
 * Simulates Java 8's {@code java.util.function.Function}.
 * <p>
 * <b>Not for application use!</b>
 */
public interface Function<T, R>
{
    R apply(T t);
}
