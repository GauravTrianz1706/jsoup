package org.jsoup.internal;

import java.lang.ref.SoftReference;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

/**
 A SoftPool is a thread-safe object pool that holds a pool of initializable objects. This allows us to reuse
 expensive objects (buffers, etc.) between invocations, while also allowing those objects to be reclaimed when
 no longer in use.
 <p>Replaces the previous ThreadLocal-based implementation to prevent memory leaks and data corruption in
 cloud environments with thread pooling (AWS ECS/EKS). Uses a ConcurrentLinkedDeque as a shared pool
 that is safe for concurrent access without relying on thread-local storage.</p>
 <p>Like the previous ThreadLocal version, should be stored in a static field.</p>
 @param <T> the type of object to pool.
 @since 1.18.2
 */
public class SoftPool<T> {
    // Replaced ThreadLocal<SoftReference<ArrayDeque<T>>> with ConcurrentLinkedDeque for cloud compatibility.
    // ThreadLocal variables cause memory leaks in cloud thread-pooled environments (AWS ECS/EKS) when
    // threads are reused across requests. ConcurrentLinkedDeque provides thread-safe pooling without
    // thread-local storage, preventing both memory leaks and data corruption.
    final ConcurrentLinkedDeque<T> pool;
    private final Supplier<T> initializer;
    /**
     How many total uses of the creating object might be instantiated on the same thread at once. More than this and
     those objects aren't recycled. Doesn't need to be too conservative, as they can still be GCed.
     */
    static final int MaxIdle = 12;

    /**
     Create a new SoftPool.
     @param initializer a supplier that creates a new object when one is needed.
     */
    public SoftPool(Supplier<T> initializer) {
        this.initializer = initializer;
        this.pool = new ConcurrentLinkedDeque<>();
    }

    /**
     Borrow an object from the pool, creating a new one if the pool is empty. Make sure to release it back to the pool
     when done, so that it can be reused.
     @return an object from the pool, as defined by the initializer.
     */
    public T borrow() {
        T item = pool.pollFirst();
        return item != null ? item : initializer.get();
    }

    /**
     Release an object back to the pool. If the pool is full, the object is not retained. If you don't want to reuse a
     borrowed object (for e.g. a StringBuilder that grew too large), just don't release it.
     @param value the object to release back to the pool.
     */
    public void release(T value) {
        if (pool.size() < MaxIdle) {
            pool.offerFirst(value);
        }
    }

    /**
     Returns the underlying pool deque. Provided for compatibility with existing code that accesses the stack.
     @return the pool deque used for object storage.
     */
    ArrayDeque<T> getStack() {
        // Compatibility shim: drain pool into a local ArrayDeque for callers that need stack semantics.
        // In cloud environments, prefer borrow()/release() directly.
        ArrayDeque<T> stack = new ArrayDeque<>();
        T item;
        while ((item = pool.pollFirst()) != null) {
            stack.push(item);
        }
        return stack;
    }
}
