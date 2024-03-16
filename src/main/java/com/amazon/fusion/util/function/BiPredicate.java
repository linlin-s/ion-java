// Copyright (c) 2021-2022 Amazon.com, Inc.  All rights reserved.

package com.amazon.fusion.util.function;

/**
 * Simulates Java 8's {@code java.util.function.BiPredicate}.
 * <p>
 * <b>Not for application use!</b>
 * </p>
 */
public interface BiPredicate<T, U>
//  extends java.util.function.BiPredicate
{
    boolean test(T t, U u);
}
