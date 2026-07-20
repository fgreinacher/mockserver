package org.mockserver.mock.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe, append-only, bounded ring buffer of {@link AuditEntry} records
 * for control-plane mutations. Oldest entries are evicted once the store reaches
 * its maximum capacity.
 * <p>
 * The singleton's initial capacity is read once from {@code controlPlaneAuditMaxEntries} at class
 * initialization, but is NOT fixed for the process lifetime: {@link #setMaxSize(int)} resizes the
 * store live, and is invoked when {@code controlPlaneAuditMaxEntries} changes via
 * {@code PUT /mockserver/configuration}. A package-visible constructor exists for unit tests that
 * need an isolated, small-capacity instance.
 */
public class AuditStore {

    private static final AuditStore INSTANCE = new AuditStore(maxFromConfig());

    private volatile int maxSize;
    private final Deque<AuditEntry> entries = new ArrayDeque<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    AuditStore(int maxSize) {
        this.maxSize = maxSize > 0 ? maxSize : 1000;
    }

    private static int maxFromConfig() {
        try {
            return org.mockserver.configuration.ConfigurationProperties.controlPlaneAuditMaxEntries();
        } catch (Throwable throwable) {
            return 1000;
        }
    }

    public static AuditStore getInstance() {
        return INSTANCE;
    }

    /**
     * Resize the store. A SHRINK takes effect immediately: the oldest entries are discarded until
     * the store fits the new capacity, rather than waiting for further audited mutations to push
     * them out. Non-positive values are ignored (the existing capacity is retained), matching the
     * constructor's treatment of a non-positive configured value.
     *
     * @param maxSize the new capacity
     */
    public void setMaxSize(int maxSize) {
        if (maxSize <= 0) {
            return;
        }
        lock.writeLock().lock();
        try {
            this.maxSize = maxSize;
            while (entries.size() > maxSize) {
                entries.pollFirst();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * The store's current capacity.
     */
    public int getMaxSize() {
        return maxSize;
    }

    public void add(AuditEntry entry) {
        lock.writeLock().lock();
        try {
            if (entries.size() >= maxSize) {
                entries.pollFirst();
            }
            entries.addLast(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns up to {@code limit} most-recent entries, newest first.
     */
    public List<AuditEntry> getRecent(int limit) {
        lock.readLock().lock();
        try {
            List<AuditEntry> result = new ArrayList<>();
            Iterator<AuditEntry> it = entries.descendingIterator();
            while (it.hasNext() && result.size() < limit) {
                result.add(it.next());
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            entries.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return entries.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
