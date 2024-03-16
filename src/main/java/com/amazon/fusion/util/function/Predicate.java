// Copyright (c) 2014-2022 Amazon.com, Inc.  All rights reserved.

package com.amazon.fusion.util.function;

/**
 * Simulates Java 8's {@code java.util.function.Predicate}.
 * <p>
 * <b>Not for application use!</b>
 */
public interface Predicate<T>
{
    boolean test(T t);
}
