package org.mockserver.model;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
public class HeaderTest {

    @Test
    public void shouldReturnValuesSetInConstructors() {
        // when
        Header firstHeader = new Header("first", "first_one", "first_two");
        Header secondHeader = new Header("second", Arrays.asList("second_one", "second_two"));

        // then
        assertThat(firstHeader.getValues(), containsInAnyOrder(string("first_one"), string("first_two")));
        assertThat(secondHeader.getValues(), containsInAnyOrder(string("second_one"), string("second_two")));
    }

    @Test
    public void shouldReturnValueSetInStaticConstructors() {
        // when
        Header firstHeader = header("first", "first_one", "first_two");
        Header secondHeader = header("second", Arrays.asList("second_one", "second_two"));

        // then
        assertThat(firstHeader.getValues(), containsInAnyOrder(string("first_one"), string("first_two")));
        assertThat(secondHeader.getValues(), containsInAnyOrder(string("second_one"), string("second_two")));
    }

    // ---- lazily cached primitive hashCode (KeyToMultiValue) ----

    @Test
    public void hashCodeLazilyComputedMatchesEqualHeaderAndIsStable() {
        // the varargs constructor leaves the hashCode uncomputed; the first hashCode() call must compute a
        // value consistent with equals and return the same value on every subsequent call.
        Header header = new Header("X-Test", "a", "b");
        int first = header.hashCode();
        assertThat(header.hashCode(), is(first));
        assertThat(header.hashCode(), is(header("X-Test", "a", "b").hashCode()));
        assertThat(header, is(header("X-Test", "a", "b")));
    }

    @Test
    public void hashCodeRecomputedAfterValuesMutated() {
        // mutating the values must refresh the cached hashCode so it never goes stale against equals.
        Header header = new Header("X-Test", "a");
        int before = header.hashCode();

        header.addValues("b");

        assertThat(header.hashCode(), is(not(before)));
        assertThat(header.hashCode(), is(new Header("X-Test", "a", "b").hashCode()));
        assertThat(header, is(new Header("X-Test", "a", "b")));
    }

    @Test
    public void hashCodeIsConsistentUnderConcurrentFirstAccess() throws Exception {
        // Each header is varargs-constructed (hashCode left uncomputed), then many threads race their
        // FIRST hashCode() call. The cache is a single int (0 = "not computed"), so a racing reader sees
        // either 0 and recomputes the same value, or the finished value — never a torn "computed?"+value
        // pair. This exercises that path; it cannot deterministically reproduce a weak-memory tear, so it
        // is a smoke test, not a proof — the guarantee is structural (a single self-healing int field, as
        // in Not and HttpRequest), not something this test can force to fail on a strong-memory host.
        int rounds = 2000;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds; round++) {
                Header shared = new Header("X-Test", "a", "b");
                int expected = header("X-Test", "a", "b").hashCode();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return shared.hashCode();
                    }));
                }
                start.countDown();
                for (Future<Integer> result : results) {
                    assertThat(result.get(10, SECONDS), is(expected));
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

}
