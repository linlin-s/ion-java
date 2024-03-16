// Copyright (c) 2015-2022 Amazon.com, Inc.  All rights reserved.

package com.amazon.fusion.util.function;

/**
 * Simulates Java 8's {@code java.util.function.Supplier}.
 * <p>
 * <b>Not for application use!</b>
 */
public interface Supplier<T>
{
    T get();
}
