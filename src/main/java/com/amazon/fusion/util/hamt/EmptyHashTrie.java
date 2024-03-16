// Copyright (c) 2021-2022 Amazon.com, Inc.  All rights reserved.

package com.amazon.fusion.util.hamt;

import com.amazon.fusion.util.function.BiFunction;
import java.util.Collections;
import java.util.Set;

public final class EmptyHashTrie<K, V>
    extends FunctionalHashTrie<K, V>
{
    @SuppressWarnings("rawtypes")
    public static final EmptyHashTrie EMPTY = new EmptyHashTrie();

    private EmptyHashTrie()
    {
        super(HashArrayMappedTrie.<K, V>empty(), 0);
    }

    @Override
    public int size()
    {
        return 0;
    }

    @Override
    public boolean containsKey(K key)
    {
        // When empty, the HAMT get() won't compute the hash of the key, and
        // thus won't fail given null.  This ensures uniform behavior.
        validateKey(key);
        return false;
    }

    @Override
    public V get(K key)
    {
        validateKey(key);
        return null;
    }

    @Override
    public Set<K> keySet()
    {
        return Collections.emptySet();
    }

    @Override
    public FunctionalHashTrie<K, V> withoutKey(K key)
    {
        validateKey(key);
        return this;
    }

    @Override
    @SafeVarargs
    public final FunctionalHashTrie<K, V> withoutKeys(K... keys)
    {
        validateKey(keys);
        return this;
    }

    @Override
    public FunctionalHashTrie<K, V> merge1(MultiHashTrie<K, V> that)
    {
        return that.oneify();
    }

    @Override
    public MultiHashTrie<K, V> mergeMulti(MultiHashTrie<K, V> that)
    {
        return that;
    }

    @Override
    public FunctionalHashTrie<K, V> transform(BiFunction<K, V, V> xform)
    {
        return this;
    }

    @Override
    @SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
    public boolean equals(Object that)
    {
        // Empty singleton is only equal to itself.
        return (this == that);
    }
}
