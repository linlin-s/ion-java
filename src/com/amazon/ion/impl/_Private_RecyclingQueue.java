package com.amazon.ion.impl;


import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class _Private_RecyclingQueue<T> {

    /**
     * Factory for new stack elements.
     * @param <T> the type of element.
     */
    public interface ElementFactory<T> {

        /**
         * @return a new instance.
         */
        T newElement();
    }

    private final List<T> elements;
    private final ElementFactory<T> elementFactory;
    private int currentIndex;
    private T top;

    /**
     * @param initialCapacity the initial capacity of the underlying collection.
     * @param elementFactory the factory used to create a new element on {@link #push()} when the stack has
     *                       not previously grown to the new depth.
     */
    public _Private_RecyclingQueue(int initialCapacity, ElementFactory<T> elementFactory) {
        elements = new ArrayList<T>(initialCapacity);
        this.elementFactory = elementFactory;
        currentIndex = -1;

    }

    public T truncate(long position) {
        if (currentIndex >= position) {
            currentIndex = (int) position;
            return elements.get(currentIndex);
        } else {
            return null;
        }
    }

    /**
     * Pushes an element onto the top of the stack, instantiating a new element only if the stack has not
     * previously grown to the new depth.
     * @return the element at the top of the stack after the push. This element must be initialized by the caller.
     */
    public T push() {
        currentIndex++;
        if (currentIndex >= elements.size()) {
            top = elementFactory.newElement();
            elements.add(top);
        }  else {
            top = elements.get(currentIndex);
        }
        return top;
    }

    public Iterator<T> iterate() {
        return elements.iterator();
    }

    public void extend(_Private_RecyclingQueue<T> patches) {
        elements.addAll(patches.elements);
    }

    public boolean isEmpty() {
       return elements.isEmpty();
    }

    public void clear() {
        currentIndex = -1;
    }

}
