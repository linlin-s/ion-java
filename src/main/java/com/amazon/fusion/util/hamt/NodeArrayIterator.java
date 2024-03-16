// Copyright (c) 2018-2021 Amazon.com, Inc.  All rights reserved.

package com.amazon.fusion.util.hamt;

import com.amazon.fusion.util.hamt.HashArrayMappedTrie.HashArrayMappedNode;
import com.amazon.fusion.util.hamt.HashArrayMappedTrie.TrieNode;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

/**
 * Iterator for {@link HashArrayMappedNode}s.
 */
final class NodeArrayIterator<K, V>
    implements Iterator<Entry<K, V>>
{
    private final TrieNode<K, V>[] array;
    private int                    i;
    private Iterator<Entry<K, V>>  childIter;

    NodeArrayIterator(TrieNode<K, V>[] array)
    {
        this.array = array;
    }

    private boolean getAndCacheNext()
    {
        while (i < array.length)
        {
            TrieNode<K, V> node = array[i++];
            if (node != null)
            {
                childIter = node.iterator();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasNext()
    {
        return childIter != null || getAndCacheNext();
    }

    @Override
    public Entry<K, V> next()
    {
        if (childIter != null)
        {
            Entry<K, V> ret = childIter.next();
            if (!childIter.hasNext())
            {
                childIter = null;
            }
            return ret;
        }
        else if (getAndCacheNext())
        {
            return next();
        }
        else
        {
            throw new NoSuchElementException();
        }
    }

    @Override
    public void remove()
    {
        throw new UnsupportedOperationException();
    }
}
