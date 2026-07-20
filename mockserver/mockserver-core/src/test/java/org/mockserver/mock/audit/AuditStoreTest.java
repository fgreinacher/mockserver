package org.mockserver.mock.audit;

import org.junit.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;

public class AuditStoreTest {

    private static AuditEntry entry(long epoch, String operation) {
        return new AuditEntry(epoch, "PUT", "/mockserver/" + operation, operation, "127.0.0.1:1234", "anonymous", "none", "AUTHORIZED", null);
    }

    @Test
    public void storesAndRetrievesNewestFirst() {
        AuditStore store = new AuditStore(10);
        store.add(entry(1, "expectation"));
        store.add(entry(2, "clear"));
        store.add(entry(3, "reset"));
        List<AuditEntry> recent = store.getRecent(10);
        assertThat(recent, hasSize(3));
        assertThat(store.size(), is(3));
        // newest first
        assertThat(recent.get(0).getOperation(), is("reset"));
        assertThat(recent.get(1).getOperation(), is("clear"));
        assertThat(recent.get(2).getOperation(), is("expectation"));
    }

    @Test
    public void evictsOldestWhenFull() {
        AuditStore store = new AuditStore(3);
        for (int i = 0; i < 5; i++) {
            store.add(entry(i, "op" + i));
        }
        assertThat(store.size(), is(3));
        List<AuditEntry> recent = store.getRecent(3);
        assertThat(recent, hasSize(3));
        // most recent 3 (descending): op4, op3, op2
        assertThat(recent.get(0).getOperation(), is("op4"));
        assertThat(recent.get(1).getOperation(), is("op3"));
        assertThat(recent.get(2).getOperation(), is("op2"));
    }

    @Test
    public void getRecentRespectsLimit() {
        AuditStore store = new AuditStore(100);
        for (int i = 0; i < 10; i++) {
            store.add(entry(i, "op" + i));
        }
        assertThat(store.getRecent(3), hasSize(3));
        assertThat(store.getRecent(100), hasSize(10));
    }

    @Test
    public void setMaxSizeShrinkDiscardsOldestEntriesImmediately() {
        // given - a store holding 10 entries
        AuditStore store = new AuditStore(100);
        for (int i = 0; i < 10; i++) {
            store.add(entry(i, "op" + i));
        }
        assertThat(store.size(), is(10));

        // when - the capacity is shrunk (as a live controlPlaneAuditMaxEntries change does)
        store.setMaxSize(3);

        // then - the oldest are discarded straight away, the newest retained, and the new bound holds
        assertThat(store.getMaxSize(), is(3));
        assertThat(store.size(), is(3));
        List<AuditEntry> recent = store.getRecent(10);
        assertThat(recent, hasSize(3));
        assertThat(recent.get(0).getOperation(), is("op9"));
        assertThat(recent.get(2).getOperation(), is("op7"));

        // and - subsequent adds respect the new bound
        store.add(entry(99, "op99"));
        assertThat(store.size(), is(3));
        assertThat(store.getRecent(1).get(0).getOperation(), is("op99"));
    }

    @Test
    public void setMaxSizeGrowthRetainsEntriesAndRaisesTheBound() {
        // given - a store already at its bound
        AuditStore store = new AuditStore(2);
        store.add(entry(1, "op1"));
        store.add(entry(2, "op2"));
        assertThat(store.size(), is(2));

        // when - the capacity is raised and the new headroom used
        store.setMaxSize(5);
        store.add(entry(3, "op3"));
        store.add(entry(4, "op4"));

        // then - nothing was discarded by the resize
        assertThat(store.getMaxSize(), is(5));
        assertThat(store.size(), is(4));
    }

    @Test
    public void setMaxSizeIgnoresNonPositiveValues() {
        // given
        AuditStore store = new AuditStore(4);
        store.add(entry(1, "op1"));

        // when - a non-positive capacity is supplied (matching the constructor's treatment)
        store.setMaxSize(0);
        store.setMaxSize(-1);

        // then - the existing capacity and contents are untouched
        assertThat(store.getMaxSize(), is(4));
        assertThat(store.size(), is(1));
    }

    @Test
    public void clearRemovesAll() {
        AuditStore store = new AuditStore(100);
        store.add(entry(1, "expectation"));
        store.add(entry(2, "reset"));
        store.clear();
        assertThat(store.size(), is(0));
        assertThat(store.getRecent(10), is(empty()));
    }
}
