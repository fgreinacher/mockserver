package org.mockserver.collections;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

import static org.hamcrest.core.Is.is;
/**
 * @author jamesdbloom
 */
public class CircularConcurrentLinkedDequeTest {

    @Test
    public void shouldNotAllowAddingMoreThenMaximumNumberOfEntriesWhenUsingAdd() {
        // given
        CircularConcurrentLinkedDeque<String> concurrentLinkedQueue = new CircularConcurrentLinkedDeque<String>(3, null);

        // when
        concurrentLinkedQueue.add("1");
        concurrentLinkedQueue.add("2");
        concurrentLinkedQueue.add("3");
        concurrentLinkedQueue.add("4");

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue, not(contains("1")));
        assertThat(concurrentLinkedQueue, contains("2", "3", "4"));
    }

    @Test
    public void shouldNotAllowAddingMoreThenMaximumNumberOfEntriesWhenUsingAddAll() {
        // given
        CircularConcurrentLinkedDeque<String> concurrentLinkedQueue = new CircularConcurrentLinkedDeque<String>(3, null);

        // when
        concurrentLinkedQueue.addAll(Arrays.asList("1", "2", "3", "4"));

        // then
        assertThat(concurrentLinkedQueue.size(), is(3));
        assertThat(concurrentLinkedQueue, not(contains("1")));
        assertThat(concurrentLinkedQueue, contains("2", "3", "4"));
    }

    @Test
    public void shouldInvokeEvictCallbackForOldestEntriesWhenFull() {
        // given
        List<String> evicted = new ArrayList<>();
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(2, evicted::add);

        // when
        queue.add("1");
        queue.add("2");
        queue.add("3");
        queue.add("4");

        // then
        assertThat(queue.size(), is(2));
        assertThat(queue, contains("3", "4"));
        assertThat(evicted, contains("1", "2"));
    }

    @Test
    public void shouldKeepSizeConsistentAcrossRemoveItemAndClear() {
        // given
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(10, null);
        queue.add("1");
        queue.add("2");
        queue.add("3");

        // when / then — removeItem keeps size accurate
        assertThat(queue.size(), is(3));
        assertThat(queue.removeItem("2"), is(true));
        assertThat(queue.size(), is(2));
        assertThat(queue, contains("1", "3"));

        // and clear resets size to zero
        queue.clear();
        assertThat(queue.size(), is(0));
        assertThat(queue.isEmpty(), is(true));
    }

    @Test
    public void shouldReturnFalseAndNotChangeSizeWhenRemovingMissingItem() {
        // given
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(10, null);
        queue.add("1");

        // when / then
        assertThat(queue.removeItem("missing"), is(false));
        assertThat(queue.size(), is(1));
    }

    @Test
    public void shouldReportZeroSizeAndRejectAddsWhenMaxSizeIsZero() {
        // given
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(0, null);

        // when / then
        assertThat(queue.add("1"), is(false));
        assertThat(queue.size(), is(0));
        assertThat(queue.isEmpty(), is(true));
    }

    /**
     * Regression guard for GitHub issue #2329 (CPU climbs as the request/event log fills).
     * The previous implementation called {@link java.util.concurrent.ConcurrentLinkedDeque#size()}
     * (O(n)) inside the per-insert eviction loop, so inserting at capacity performed ~n node
     * traversals per element and took minutes at this scale. With O(1) sizing it completes in
     * well under a second; the timeout is deliberately generous to avoid CI flakiness while still
     * failing fast if the O(n) behaviour ever returns.
     */
    @Test
    public void shouldEvictOldestEntriesUntilUnderByteBudget() {
        // given — generous count bound so only the byte budget can trigger eviction; weight = length
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(100, 10, String::length, null);
        queue.add("12345"); // 5  (total 5)
        queue.add("67");    // 2  (total 7)
        queue.add("89");    // 2  (total 9)

        // when — adding 2 more bytes would make 11 > 10, so the oldest ("12345") is evicted first
        queue.add("ab");    // 2

        // then
        assertThat(queue.size(), is(3));
        assertThat(queue, contains("67", "89", "ab"));
    }

    @Test
    public void shouldStillRetainSingleElementLargerThanByteBudget() {
        // given
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(100, 4, String::length, null);

        // when — a single element heavier than the whole budget must still be admitted
        queue.add("abcdef"); // 6 > 4

        // then
        assertThat(queue.size(), is(1));
        assertThat(queue, contains("abcdef"));

        // and adding another element evicts the oversized one to make room (budget stops at empty)
        queue.add("x"); // 1
        assertThat(queue.size(), is(1));
        assertThat(queue, contains("x"));
    }

    @Test
    public void shouldResetByteTotalOnClearSoLaterAddsAreNotSpuriouslyEvicted() {
        // given — fill exactly to the byte budget then clear
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(100, 6, String::length, null);
        queue.add("aaa"); // 3
        queue.add("bbb"); // 3 (total 6)
        queue.clear();

        // when — after clear the running byte total must be zero, so both new entries fit
        queue.add("ccc"); // 3
        queue.add("ddd"); // 3 (total 6)

        // then — if totalBytes had not reset, "ccc" would have been wrongly evicted
        assertThat(queue.size(), is(2));
        assertThat(queue, contains("ccc", "ddd"));
    }

    @Test
    public void shouldKeepByteTotalConsistentAcrossRemoveItem() {
        // given — total 6 of a 6 budget
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(100, 6, String::length, null);
        queue.add("aaa"); // 3
        queue.add("bbb"); // 3

        // when — removing one frees its weight so a new same-size entry fits without eviction
        assertThat(queue.removeItem("aaa"), is(true));
        queue.add("ccc"); // 3 (total back to 6)

        // then
        assertThat(queue.size(), is(2));
        assertThat(queue, contains("bbb", "ccc"));
    }

    @Test
    public void shouldExposeRetainedTotalBytesThatRisesOnAddAndFallsOnEvictionAndClear() {
        // getTotalBytes() surfaces the running RETAINED weight so a caller (MockServerEventLog's
        // retained_bytes gauge) can attribute heap to the post-processing deque. Prove it moves: rises
        // with weighed adds, falls when an over-budget add evicts the oldest, and zeroes on clear.
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(100, 6, String::length, null);
        assertThat(queue.getTotalBytes(), is(0L));

        queue.add("aaa"); // 3
        assertThat(queue.getTotalBytes(), is(3L));
        queue.add("bbb"); // 3 (total 6, exactly the budget)
        assertThat(queue.getTotalBytes(), is(6L));

        // over-budget add evicts the oldest, so the total stays within the budget (fell, not grew)
        queue.add("ccc"); // would be 9 -> evict "aaa" -> back to 6
        assertThat(queue.getTotalBytes(), is(6L));
        assertThat(queue.getByteEvictedCount(), is(1L));

        queue.clear();
        assertThat(queue.getTotalBytes(), is(0L));
    }

    @Test
    public void shouldReportZeroRetainedTotalBytesWhenThereIsNoWeigher() {
        // NO WEIGHER (the 2-arg constructor) is the only thing that opts out of weight tracking, so the
        // retained-bytes accessor stays at zero however many elements are held.
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(3, null);
        queue.add("aaaaaaaaaa");
        queue.add("bbbbbbbbbb");
        assertThat(queue.getTotalBytes(), is(0L));
    }

    @Test
    public void shouldStillMeasureRetainedBytesWhenTheByteBudgetIsDisabled() {
        // A DISABLED BYTE BUDGET IS NOT A DISABLED MEASUREMENT. maxBytes <= 0 skips the byte-eviction
        // loop, not the accounting: with a weigher present the running total still climbs. This is the
        // configuration MockServerEventLog takes when maxEventLogSizeInBytes is 0 (it always supplies
        // LogEntry::estimatedHeapSize as the weigher), and it is exactly when retained_bytes matters
        // most — nothing is capping the deque, so the gauge is the only thing that can say what it
        // holds. A gauge that read 0 here would report "empty" while the deque grew without bound,
        // which is the blind spot these accessors exist to close.
        CircularConcurrentLinkedDeque<String> unbounded =
            new CircularConcurrentLinkedDeque<>(1000, 0, String::length, null);
        assertThat(unbounded.getMaxBytes(), is(0L));
        assertThat(unbounded.getTotalBytes(), is(0L));

        unbounded.add("aaaaaaaaaa");
        assertThat(unbounded.getTotalBytes(), is(10L));
        unbounded.add("bbbbb");
        assertThat(unbounded.getTotalBytes(), is(15L));

        // and it still falls on removal — the accounting is symmetric with the budget off
        unbounded.clear();
        assertThat(unbounded.getTotalBytes(), is(0L));
    }

    @Test
    public void shouldExposeConfiguredMaxBytesAndMaxSizeCeilings() {
        // the ceilings behind max_retained_bytes / max_retained_entries
        CircularConcurrentLinkedDeque<String> byteBounded =
            new CircularConcurrentLinkedDeque<>(42, 1024, String::length, null);
        assertThat(byteBounded.getMaxSize(), is(42));
        assertThat(byteBounded.getMaxBytes(), is(1024L));

        // 2-arg ctor disables the byte budget -> maxBytes 0
        CircularConcurrentLinkedDeque<String> countOnly = new CircularConcurrentLinkedDeque<>(7, null);
        assertThat(countOnly.getMaxSize(), is(7));
        assertThat(countOnly.getMaxBytes(), is(0L));

        // a live resize is reflected by the ceilings (mirrors PUT /mockserver/configuration)
        byteBounded.setMaxSize(9);
        byteBounded.setMaxBytes(2048);
        assertThat(byteBounded.getMaxSize(), is(9));
        assertThat(byteBounded.getMaxBytes(), is(2048L));
    }

    @Test
    public void shouldIgnoreByteBudgetWhenDisabledViaFourArgConstructor() {
        // given — maxBytes 0 disables the budget; only the count bound applies, exactly as the 2-arg ctor
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(3, 0, String::length, null);

        // when — large elements that would blow any small byte budget
        queue.add("aaaaaaaaaa");
        queue.add("bbbbbbbbbb");
        queue.add("cccccccccc");
        queue.add("dddddddddd");

        // then — bounded only by count
        assertThat(queue.size(), is(3));
        assertThat(queue, contains("bbbbbbbbbb", "cccccccccc", "dddddddddd"));
    }

    @Test(timeout = 30000)
    public void shouldAddAtCapacityInConstantTimeRegardlessOfSize() {
        // given
        int maxSize = 50000;
        CircularConcurrentLinkedDeque<Integer> queue = new CircularConcurrentLinkedDeque<>(maxSize, null);

        // when — 150k inserts, 100k of them at capacity (each evicting the oldest)
        int operations = maxSize * 3;
        for (int i = 0; i < operations; i++) {
            queue.add(i);
        }

        // then
        assertThat(queue.size(), is(maxSize));
    }

    @Test(timeout = 30000)
    public void shouldAddWithByteBudgetInConstantTimeRegardlessOfSize() {
        // given — a byte-bounded deque; the byte accounting (totalBytes AtomicLong + getByteEvictedCount)
        // must not reintroduce the O(n) size() cost the count path avoids
        int maxSize = 50000;
        CircularConcurrentLinkedDeque<byte[]> queue =
            new CircularConcurrentLinkedDeque<>(maxSize, 5_000_000L, b -> b.length, null);

        // when — 150k inserts of 200-byte elements; the 5 MB budget binds well before the count bound,
        // so almost every insert both count-checks and byte-evicts
        byte[] element = new byte[200];
        for (int i = 0; i < maxSize * 3; i++) {
            queue.add(element);
        }

        // then — bounded by bytes (5 MB / 200 => ~25k elements), and it completed within the timeout,
        // demonstrating the per-insert cost stays bounded as the deque fills rather than climbing O(n)
        assertThat(queue.size() <= 25_000, is(true));
        assertThat(queue.getByteEvictedCount() > 0, is(true));
    }

    @Test
    public void shouldEvictImmediatelyWhenMaxSizeShrunk() {
        // given - a full deque recording every eviction
        List<String> evicted = new ArrayList<>();
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(5, evicted::add);
        queue.add("a");
        queue.add("b");
        queue.add("c");
        queue.add("d");
        queue.add("e");
        assertThat(queue.size(), is(5));

        // when - the bound is shrunk (as a live maxLogEntries change does)
        queue.setMaxSize(2);

        // then - the oldest entries are evicted straight away, without waiting for another add
        assertThat(queue.size(), is(2));
        assertThat(queue, contains("d", "e"));
        assertThat(evicted, contains("a", "b", "c"));
    }

    @Test
    public void shouldEvictImmediatelyWhenMaxBytesShrunk() {
        // given - a byte-budgeted deque holding 30 bytes of elements
        List<String> evicted = new ArrayList<>();
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(100, 100, String::length, evicted::add);
        queue.add("aaaaaaaaaa");
        queue.add("bbbbbbbbbb");
        queue.add("cccccccccc");
        assertThat(queue.size(), is(3));

        // when - the byte budget is shrunk below the current total
        queue.setMaxBytes(15);

        // then - the oldest entries are evicted straight away until the total fits
        assertThat(queue.size(), is(1));
        assertThat(queue, contains("cccccccccc"));
        assertThat(evicted, contains("aaaaaaaaaa", "bbbbbbbbbb"));
    }

    @Test
    public void shouldAttributeByteEvictionsToTheByteBudget() {
        // given — count bound generous, byte budget small so eviction is byte-driven
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(1000, 6, String::length, null);

        // when — four 3-byte elements against a 6-byte budget => two byte-evictions
        queue.add("aaa");
        queue.add("bbb");
        queue.add("ccc");
        queue.add("ddd");

        // then — every eviction is attributed to the byte budget
        assertThat(queue.getEvictedCount(), is(2L));
        assertThat(queue.getByteEvictedCount(), is(2L));
        assertThat(queue, contains("ccc", "ddd"));
    }

    @Test
    public void shouldNotAttributeCountEvictionsToTheByteBudget() {
        // given — a byte budget large enough never to bind; only the count bound evicts
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(2, 1_000_000, String::length, null);

        // when — three elements against a count bound of two => one count-eviction
        queue.add("aaa");
        queue.add("bbb");
        queue.add("ccc");

        // then — the eviction is counted but NOT attributed to the byte budget
        assertThat(queue.getEvictedCount(), is(1L));
        assertThat(queue.getByteEvictedCount(), is(0L));
        assertThat(queue, contains("bbb", "ccc"));
    }

    @Test
    public void shouldAttributeByteEvictionsOnMaxBytesShrink() {
        // given — a byte-budgeted deque holding 30 bytes
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(100, 100, String::length, null);
        queue.add("aaaaaaaaaa");
        queue.add("bbbbbbbbbb");
        queue.add("cccccccccc");

        // when — shrinking the budget forces two byte-evictions
        queue.setMaxBytes(15);

        // then — both are attributed to the byte budget
        assertThat(queue.getEvictedCount(), is(2L));
        assertThat(queue.getByteEvictedCount(), is(2L));
    }

    @Test
    public void shouldResetByteEvictedCountOnClear() {
        // given — a deque that has byte-evicted
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(1000, 6, String::length, null);
        queue.add("aaa");
        queue.add("bbb");
        queue.add("ccc");
        assertThat(queue.getByteEvictedCount(), is(1L));

        // when — cleared
        queue.clear();

        // then — both eviction counters reset alongside the contents
        assertThat(queue.getEvictedCount(), is(0L));
        assertThat(queue.getByteEvictedCount(), is(0L));
    }

    @Test
    public void shouldResetByteEvictedCountOnResetEvictedCount() {
        // given — a deque that has byte-evicted (contents retained, only the counters cleared)
        CircularConcurrentLinkedDeque<String> queue =
            new CircularConcurrentLinkedDeque<>(1000, 6, String::length, null);
        queue.add("aaa");
        queue.add("bbb");
        queue.add("ccc");
        assertThat(queue.getByteEvictedCount(), is(1L));

        // when — the counters are reset without emptying the deque (the tombstone-clear path)
        queue.resetEvictedCount();

        // then — both counters are zero but the contents remain
        assertThat(queue.getEvictedCount(), is(0L));
        assertThat(queue.getByteEvictedCount(), is(0L));
        assertThat(queue.size(), is(2));
    }

    @Test
    public void shouldNotEvictWhenMaxSizeGrown() {
        // given
        List<String> evicted = new ArrayList<>();
        CircularConcurrentLinkedDeque<String> queue = new CircularConcurrentLinkedDeque<>(2, evicted::add);
        queue.add("a");
        queue.add("b");

        // when - the bound is raised
        queue.setMaxSize(10);
        queue.add("c");

        // then - nothing evicted and the new headroom is usable
        assertThat(queue.size(), is(3));
        assertThat(queue, contains("a", "b", "c"));
        assertThat(evicted, is(empty()));
    }

}