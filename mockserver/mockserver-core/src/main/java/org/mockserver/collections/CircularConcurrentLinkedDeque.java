package org.mockserver.collections;

import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/**
 * A bounded {@link ConcurrentLinkedDeque} that evicts the oldest element(s) once it reaches
 * {@code maxSize}, invoking an optional callback on each evicted element.
 * <p>
 * <strong>Why the explicit size counter:</strong> {@link ConcurrentLinkedDeque#size()} is
 * documented as an <em>O(n)</em> operation (it walks the whole list). The eviction check runs
 * on every {@link #add}/{@link #offer}, so relying on {@code super.size()} made each insertion
 * O(n) once the deque was full — the hot path for MockServer's request/event log. Under a
 * sustained request load this manifested as CPU usage that climbed as the log filled and stayed
 * high (GitHub issue #2329). An {@link AtomicInteger} maintained by every mutating method makes
 * {@link #size()} and the eviction check O(1).
 * <p>
 * The counter is kept consistent by every size-changing method on this class
 * ({@link #add}, {@link #offer}, {@link #addAll} (via {@code add}), {@link #remove},
 * {@link #removeItem}, {@link #clear}, and the internal eviction). Callers must mutate the deque
 * only through these methods (MockServer's {@code MockServerEventLog} does); direct use of other
 * inherited bulk mutators is not supported by this subclass.
 * <p>
 * <strong>Optional byte budget:</strong> in addition to the element-count bound, an optional
 * {@code maxBytes} budget can be supplied together with a {@code weigher} (via the 4-arg
 * constructor). Each element's weight is measured by the weigher on insertion and accumulated into
 * {@link #totalBytes}; whenever an insertion would push the running total over {@code maxBytes} the
 * oldest elements are evicted first until it fits (or the deque is empty). This caps the heap held
 * by the event log when individual entries are large (e.g. big LLM-capture bodies) rather than only
 * by entry count. A single element whose weight alone exceeds {@code maxBytes} is still retained —
 * the byte-eviction loop stops once the deque is empty so we never reject the incoming element. The
 * budget is disabled when {@code maxBytes <= 0} or the weigher is {@code null}, in which case the
 * deque behaves exactly as the count-bounded version.
 *
 * @author jamesdbloom
 */
public class CircularConcurrentLinkedDeque<E> extends ConcurrentLinkedDeque<E> {

    private static final long serialVersionUID = 1L;

    private int maxSize;
    private long maxBytes;
    private final ToLongFunction<E> weigher;
    private final Consumer<E> onEvictCallback;
    // O(1) element count — see class javadoc. Updated by every mutating method below.
    private final AtomicInteger count = new AtomicInteger(0);
    // Running total of element weights (per the weigher), kept consistent alongside count so the
    // byte-budget check is O(1). Zero/unused when the budget is disabled.
    private final AtomicLong totalBytes = new AtomicLong(0);

    public CircularConcurrentLinkedDeque(int maxSize, Consumer<E> onEvictCallback) {
        this(maxSize, 0, null, onEvictCallback);
    }

    public CircularConcurrentLinkedDeque(int maxSize, long maxBytes, ToLongFunction<E> weigher, Consumer<E> onEvictCallback) {
        this.maxSize = maxSize;
        this.maxBytes = maxBytes;
        this.weigher = weigher;
        this.onEvictCallback = onEvictCallback;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * O(1) size, backed by an internal counter (unlike {@link ConcurrentLinkedDeque#size()}
     * which is O(n)).
     */
    @Override
    public int size() {
        return count.get();
    }

    @Override
    public boolean isEmpty() {
        return count.get() == 0;
    }

    @Override
    public boolean add(E element) {
        if (maxSize > 0) {
            long weight = weigher != null ? weigher.applyAsLong(element) : 0;
            evictExcessElements(weight);
            if (super.add(element)) {
                count.incrementAndGet();
                totalBytes.addAndGet(weight);
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    @Override
    public boolean addAll(Collection<? extends E> collection) {
        if (maxSize > 0) {
            boolean result = false;
            for (E element : collection) {
                if (add(element)) {
                    result = true;
                }
            }
            return result;
        } else {
            return false;
        }
    }

    @Override
    public boolean offer(E element) {
        if (maxSize > 0) {
            long weight = weigher != null ? weigher.applyAsLong(element) : 0;
            evictExcessElements(weight);
            if (super.offer(element)) {
                count.incrementAndGet();
                totalBytes.addAndGet(weight);
                return true;
            }
            return false;
        } else {
            return false;
        }
    }

    /**
     * Evict the oldest elements to make room for an incoming element of the given weight: first to
     * satisfy the element-count bound, then (when a byte budget is configured) to keep the running
     * byte total plus the incoming weight within {@code maxBytes}. The byte loop stops once the deque
     * is empty so a single element larger than the whole budget is still admitted by the caller.
     */
    private void evictExcessElements(long incomingWeight) {
        while (count.get() >= maxSize) {
            if (!pollAndEvict()) {
                // deque already empty (defensive — should not happen while count >= maxSize > 0)
                break;
            }
        }
        if (maxBytes > 0 && weigher != null) {
            while (totalBytes.get() + incomingWeight > maxBytes && count.get() > 0) {
                if (!pollAndEvict()) {
                    break;
                }
            }
        }
    }

    /**
     * Remove and return the oldest element, keeping {@link #count} and {@link #totalBytes} consistent
     * and invoking the eviction callback. The weight is subtracted BEFORE the callback runs because
     * the callback may clear/reset the element (and hence its weigher input). Returns {@code false}
     * when the deque was already empty.
     */
    private boolean pollAndEvict() {
        E evicted = super.poll();
        if (evicted == null) {
            return false;
        }
        count.decrementAndGet();
        if (weigher != null) {
            totalBytes.addAndGet(-weigher.applyAsLong(evicted));
        }
        if (onEvictCallback != null) {
            onEvictCallback.accept(evicted);
        }
        return true;
    }

    @Override
    public void clear() {
        E evicted;
        while ((evicted = super.poll()) != null) {
            count.decrementAndGet();
            if (onEvictCallback != null) {
                onEvictCallback.accept(evicted);
            }
        }
        // drained — reset the byte accounting in one shot (avoids relying on per-element weights of
        // elements the callback may already have cleared).
        totalBytes.set(0);
    }

    /**
     * @deprecated use removeItem instead
     */
    @Deprecated
    @Override
    public boolean remove(Object o) {
        // Deprecated path: the element weight is not subtracted from totalBytes (the weigher is typed
        // on E and this takes Object), so the byte total may remain approximate — biased high, never
        // negative — after this call. removeItem(E) keeps the byte total exact; prefer it.
        if (super.remove(o)) {
            count.decrementAndGet();
            return true;
        }
        return false;
    }

    public boolean removeItem(E e) {
        // capture the weight before the callback (which may clear the element) and before removal
        long weight = weigher != null ? weigher.applyAsLong(e) : 0;
        if (onEvictCallback != null) {
            onEvictCallback.accept(e);
        }
        if (super.remove(e)) {
            count.decrementAndGet();
            totalBytes.addAndGet(-weight);
            return true;
        }
        return false;
    }
}
