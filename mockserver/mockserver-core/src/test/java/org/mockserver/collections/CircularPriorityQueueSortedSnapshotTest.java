package org.mockserver.collections;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.SortableExpectationId;
import org.mockserver.model.HttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.mock.SortableExpectationId.EXPECTATION_SORTABLE_PRIORITY_COMPARATOR;
import static org.mockserver.model.HttpRequest.request;

/**
 * The cached {@link CircularPriorityQueue#toSortedList()} snapshot must never outlive a mutation,
 * even when a reader built it from the pre-mutation contents and publishes it afterwards.
 */
public class CircularPriorityQueueSortedSnapshotTest {

    /**
     * Holds a reader inside toSortedList() between iterating the store and publishing its list, so
     * a writer can mutate in that window. Only the thread named in {@code pausedThread} is held.
     */
    private static class PausingQueue extends CircularPriorityQueue<String, Expectation, SortableExpectationId> {
        final CountDownLatch readerHasIterated = new CountDownLatch(1);
        final CountDownLatch writerHasMutated = new CountDownLatch(1);
        volatile Thread pausedThread;

        PausingQueue(int maxSize) {
            super(maxSize, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);
        }

        @Override
        public Stream<Expectation> stream() {
            List<Expectation> iterated = super.stream().collect(Collectors.toList());
            if (Thread.currentThread() == pausedThread) {
                pausedThread = null;
                readerHasIterated.countDown();
                try {
                    assertThat(writerHasMutated.await(30, TimeUnit.SECONDS), is(true));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return iterated.stream();
        }
    }

    private static Expectation expectation(String path, long created) {
        return when(request(path), 0).withCreated(created);
    }

    private static String pathOf(Expectation expectation) {
        return String.valueOf(((HttpRequest) expectation.getHttpRequest()).getPath());
    }

    private static List<Expectation> readRacingWriter(PausingQueue queue, Runnable writer) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<List<Expectation>> reader = executor.submit(() -> {
                queue.pausedThread = Thread.currentThread();
                return queue.toSortedList();
            });
            assertThat(queue.readerHasIterated.await(30, TimeUnit.SECONDS), is(true));
            writer.run();
            queue.writerHasMutated.countDown();
            return reader.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldNotServeSnapshotBuiltBeforeConcurrentAdd() throws Exception {
        // given
        PausingQueue queue = new PausingQueue(10);
        long now = System.currentTimeMillis();
        Expectation one = expectation("one", now + 1);
        Expectation two = expectation("two", now + 2);
        queue.add(one);

        // when - a reader iterates [one], then "two" is added before the reader publishes
        List<Expectation> racingRead = readRacingWriter(queue, () -> queue.add(two));

        // then - the racing read may lag (eventual consistency) but the next read must not
        assertThat(racingRead, contains(one));
        assertThat(queue.toSortedList(), contains(one, two));
    }

    @Test
    public void shouldNotServeSnapshotBuiltBeforeConcurrentRemove() throws Exception {
        // given
        PausingQueue queue = new PausingQueue(10);
        long now = System.currentTimeMillis();
        Expectation one = expectation("one", now + 1);
        Expectation two = expectation("two", now + 2);
        queue.add(one);
        queue.add(two);

        // when
        List<Expectation> racingRead = readRacingWriter(queue, () -> queue.remove(one));

        // then
        assertThat(racingRead, contains(one, two));
        assertThat(queue.toSortedList(), contains(two));
    }

    @Test
    public void shouldNotServeSnapshotBuiltBeforeConcurrentPriorityChange() throws Exception {
        // given
        PausingQueue queue = new PausingQueue(10);
        long now = System.currentTimeMillis();
        Expectation low = expectation("low", now + 1);
        Expectation high = expectation("high", now + 2);
        queue.add(low);
        queue.add(high);
        Expectation highReprioritised = when(request("high"), 10).withId(high.getId()).withCreated(now + 2);

        // when
        List<Expectation> racingRead = readRacingWriter(queue, () -> queue.replaceValue(high.getId(), highReprioritised));

        // then
        assertThat(racingRead, contains(low, high));
        assertThat(queue.toSortedList(), contains(highReprioritised, low));
    }

    @Test
    public void shouldNotServeSnapshotBuiltBeforeConcurrentEviction() throws Exception {
        // given
        PausingQueue queue = new PausingQueue(10);
        long now = System.currentTimeMillis();
        Expectation one = expectation("one", now + 1);
        Expectation two = expectation("two", now + 2);
        queue.add(one);
        queue.add(two);

        // when
        List<Expectation> racingRead = readRacingWriter(queue, () -> queue.setMaxSize(1));

        // then
        assertThat(racingRead, contains(one, two));
        assertThat(queue.toSortedList(), contains(two));
    }

    @Test
    public void shouldSeeOwnMutationFromMutationListener() {
        // A derived index (CandidateIndex.rebuild) reads toSortedList() under the same monitor
        // the listener callback holds, so the snapshot must already reflect the mutation by the
        // time the listener runs.
        CircularPriorityQueue<String, Expectation, SortableExpectationId> queue = new CircularPriorityQueue<>(2, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);
        long now = System.currentTimeMillis();
        Expectation one = expectation("one", now + 1);
        Expectation two = expectation("two", now + 2);
        Expectation three = expectation("three", now + 3);
        List<String> observed = new ArrayList<>();
        queue.setMutationListener(new CircularPriorityQueue.MutationListener<Expectation>() {
            @Override
            public void onAdd(Expectation element) {
                observed.add("add " + pathOf(element) + " sees " + queue.toSortedList().contains(element));
            }

            @Override
            public void onRemove(Expectation element) {
                observed.add("remove " + pathOf(element) + " sees " + queue.toSortedList().contains(element));
            }
        });

        // when - each mutation happens with the snapshot already cached
        queue.toSortedList();
        queue.add(one);
        queue.toSortedList();
        queue.add(two);
        queue.toSortedList();
        queue.add(three); // evicts "one"
        queue.toSortedList();
        queue.remove(two);
        queue.toSortedList();
        queue.removePriorityKey(three);
        queue.toSortedList();
        queue.addPriorityKey(three);
        queue.toSortedList();
        queue.replaceValue(three.getId(), when(request("threeReplaced"), 0).withId(three.getId()).withCreated(now + 3));

        // then
        assertThat(observed, hasItems("remove three sees false", "add threeReplaced sees true"));
        assertThat(observed, hasSize(9));
        List<String> stale = observed.stream()
            .filter(entry -> entry.startsWith("add") ? entry.endsWith("false") : entry.endsWith("true"))
            .collect(Collectors.toList());
        assertThat(String.valueOf(observed), stale, is(empty()));
    }

    @Test
    public void shouldNotServeSnapshotAfterByteBudgetShrinkEvicts() {
        // given
        CircularPriorityQueue<String, Expectation, SortableExpectationId> queue = new CircularPriorityQueue<>(10, 1_000L, element -> 10L, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);
        long now = System.currentTimeMillis();
        Expectation one = expectation("one", now + 1);
        Expectation two = expectation("two", now + 2);
        Expectation three = expectation("three", now + 3);
        queue.add(one);
        queue.add(two);
        queue.add(three);
        assertThat(queue.toSortedList(), contains(one, two, three));

        // when
        queue.setMaxBytes(15L);

        // then
        assertThat(queue.toSortedList(), contains(three));
    }

    @Test
    public void shouldReuseSnapshotWhileUnchanged() {
        CircularPriorityQueue<String, Expectation, SortableExpectationId> queue = new CircularPriorityQueue<>(10, EXPECTATION_SORTABLE_PRIORITY_COMPARATOR, Expectation::getSortableId, Expectation::getId);
        queue.add(expectation("one", System.currentTimeMillis()));

        List<Expectation> first = queue.toSortedList();

        assertThat(queue.toSortedList(), sameInstance(first));
    }
}
