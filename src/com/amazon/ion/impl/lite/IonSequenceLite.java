// Copyright (c) 2010 Amazon.com, Inc.  All rights reserved.

package com.amazon.ion.impl.lite;

import com.amazon.ion.ContainedValueException;
import com.amazon.ion.IonSequence;
import com.amazon.ion.IonValue;
import com.amazon.ion.ValueFactory;
import com.amazon.ion.impl.IonValuePrivate;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;

/**
 *
 */
public abstract class IonSequenceLite
    extends IonContainerLite
    implements IonSequence
{
    /**
     * A zero-length array.
     */
    protected static final IonValueLite[] EMPTY_VALUE_ARRAY = new IonValueLite[0];

    protected IonSequenceLite(IonSystemLite system, boolean isNull)
    {
        super(system, isNull);
    }

    /**
     * Constructs a sequence value <em>not</em> backed by binary.
     *
     * @param elements
     *   the initial set of child elements.  If <code>null</code>, then the new
     *   instance will have <code>{@link #isNullValue()} == true</code>.
     *
     * @throws ContainedValueException if any value in <code>elements</code>
     * has <code>{@link IonValue#getContainer()} != null</code>.
     * @throws IllegalArgumentException
     * @throws NullPointerException
     */
    protected IonSequenceLite(IonSystemLite system,
                              Collection<? extends IonValue> elements)
        throws ContainedValueException, NullPointerException,
            IllegalArgumentException
    {
        this(system, (elements == null));
        assert _children == null;

        if (elements != null)
        {
            _children = new IonValueLite[elements.size()];
            for (Iterator i = elements.iterator(); i.hasNext();)
            {
                IonValueLite element = (IonValueLite) i.next();
                super.add(element);
            }
        }
    }

    //=========================================================================

    @Override
    public abstract IonSequenceLite clone();

    /**
     * Calculate Ion Sequence hash code as seed value XORed with hash
     * codes of contents, rotating 3 at each step to make the code
     * order-dependent.
     * @param seed Seed value
     * @return hash code
     */
    protected int sequenceHashCode(int seed)
    {
        int hash_code = seed;
        if (!isNullValue())  {
            for (IonValue v : this)  {
                hash_code ^= v.hashCode();
                hash_code = hash_code << 29 | hash_code >>> 3;
            }
        }
        return hash_code;
    }


    @Override
    // Increasing visibility
    public boolean add(IonValue element)
        throws ContainedValueException, NullPointerException
    {
        // super.add will check for the lock
        return super.add(element);
    }

    public boolean addAll(Collection<? extends IonValue> c)
    {
        checkForLock();

        if (c == null) {
            throw new NullPointerException();
        }

        boolean changed = false;

        for (IonValue v : c)
        {
            changed = add(v) || changed;
        }

        return changed;
    }

    public boolean addAll(int index, Collection<? extends IonValue> c)
    {
        checkForLock();

        if (c == null) {
            throw new NullPointerException();
        }
        if (index < 0 || index > size())
        {
            throw new IndexOutOfBoundsException();
        }

        // TODO optimize to avoid n^2 shifting and renumbering of elements.
        boolean changed = false;

        for (IonValue v : c)
        {
            add(index++, v);
            changed = true;
        }

        if (changed) {
            patch_elements_helper(index);
        }

        return changed;
    }


    public ValueFactory add()
    {
        return new CurriedValueFactoryLite(this.getSystem())
        {
            @Override
            void handle(IonValue newValue)
            {
                add(newValue);
            }
        };
    }

    @Override
    // Increasing visibility
    public void add(int index, IonValue element)
        throws ContainedValueException, NullPointerException
    {
        // super.add will check for the lock
        super.add(index, element);
    }

    public ValueFactory add(final int index)
    {
        return new CurriedValueFactoryLite(getSystem())
        {
            @Override
            void handle(IonValue newValue)
            {
                add(index, newValue);
                patch_elements_helper(index + 1);
            }
        };
    }

    public IonValue set(int index, IonValue element)
    {
        checkForLock();
        final IonValueLite concrete = ((IonValueLite) element);

        // NOTE: size calls makeReady() so we don't have to
        if (index < 0 || index >= size())
        {
            throw new IndexOutOfBoundsException("" + index);
        }

        validateNewChild(element);

        assert _children != null; // else index would be out of bounds above.

        IonValueLite removed = set_child_lite(index, concrete);
        concrete._elementid(index);
        concrete._context = this;

        removed.detachFromContainer();
        // calls setDirty(), UNLESS it hits an IOException

        return removed;
    }

    public IonValue remove(int index)
    {
        checkForLock();

        if (index < 0 || index >= get_child_count()) {
            throw new IndexOutOfBoundsException("" + index);
        }

        IonValueLite v = get_child_lite(index);
        assert(v._elementid() == index);
        remove_child(index);
        patch_elements_helper(index);
        return v;
    }

    public boolean remove(Object o)
    {
        checkForLock();

        int idx = lastIndexOf(o);
        if (idx < 0) {
            return false;
        }
        assert(o instanceof IonValueLite); // since it's in our current array
        assert( ((IonValueLite)o)._elementid() == idx );

        remove_child(idx);
        patch_elements_helper(idx);
        return true;
    }

    public boolean removeAll(Collection<?> c)
    {
        boolean changed = false;

        checkForLock();

        // remove the collection member if it is a
        // member of our child array keep track of
        // the lowest array index for later patching
        for (Object o : c) {
            int idx = lastIndexOf(o);
            if (idx >= 0) {
                assert(o == get_child(idx));
                remove_child(idx);
                patch_elements_helper(idx);
                changed = true;
            }
        }
        return changed;
    }

    public boolean retainAll(Collection<?> c)
    {
        checkForLock();

        if (get_child_count() < 1) return false;

        // TODO this method (and probably several others) needs optimization.
        IdentityHashMap<IonValue, IonValue> keepers =
            new IdentityHashMap<IonValue, IonValue>();

        for (Object o : c)
        {
            IonValue v = (IonValue) o;
            if (this == v.getContainer()) keepers.put(v, v);
        }

        boolean changed = false;
        for (int ii = get_child_count(); ii > 0; )
        {
            ii--;
            IonValue v = get_child(ii);
            if (! keepers.containsKey(v))
            {
                remove(v);
                patch_elements_helper(ii);
                changed = true;
            }
        }

        return changed;
    }

    public boolean contains(Object o)
    {
        if (o == null) {
            throw new NullPointerException();
        }
        if (!(o instanceof IonValue)) {
            throw new ClassCastException();
        }
        return ((IonValue)o).getContainer() == this;
    }

    public boolean containsAll(Collection<?> c)
    {
        for (Object o : c)
        {
            if (! contains(o)) return false;
        }
        return true;
    }

    public int indexOf(Object o)
    {
        if (o == null) {
            throw new NullPointerException();
        }
        if (!(o instanceof IonValuePrivate)) {
            throw new ClassCastException();
        }
        IonValuePrivate v = (IonValuePrivate) o;
        if (this != v.getContainer()) return -1;
        return v.getElementId();
    }

    public int lastIndexOf(Object o)
    {
        return indexOf(o);
    }

    public List<IonValue> subList(int fromIndex, int toIndex)
    {
        // TODO JIRA ION-92
        throw new UnsupportedOperationException("JIRA issue ION-92");
    }

    public IonValue[] toArray()
    {
        if (get_child_count() < 1) return EMPTY_VALUE_ARRAY;

        IonValue[] array = new IonValue[get_child_count()];
        System.arraycopy(_children, 0, array, 0, get_child_count());
        return array;
    }

    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a)
    {
        int size = get_child_count();
        if (a.length < size)
        {
            // TODO JDK 1.6 this could use Arrays.copyOf
            Class<?> type = a.getClass().getComponentType();
            // generates unchecked warning
            a = (T[]) Array.newInstance(type, size);
        }
        if (size > 0) {
            System.arraycopy(_children, 0, a, 0, size);
        }
        if (size < a.length) {
            // A surprising bit of spec.
            // this is required even with a 0 entries
            a[size] = null;
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    public <T extends IonValue> T[] extract(Class<T> type)
    {
        checkForLock();

        if (isNullValue()) return null;
        T[] array = (T[]) Array.newInstance(type, size());
        toArray(array);
        clear();
        return array;
    }

}