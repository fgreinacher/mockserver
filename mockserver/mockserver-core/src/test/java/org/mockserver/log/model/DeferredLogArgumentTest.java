package org.mockserver.log.model;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.LogEntrySerializer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;

public class DeferredLogArgumentTest {

    @Test
    public void shouldRenderOnEveryRead() {
        AtomicInteger renders = new AtomicInteger();
        DeferredLogArgument argument = DeferredLogArgument.deferred(() -> "rendered-" + renders.incrementAndGet());

        assertThat(argument.render(), is("rendered-1"));
        assertThat(argument.toString(), is("rendered-2"));
    }

    @Test
    public void shouldFallBackToAPlaceholderWhenRenderingFails() {
        DeferredLogArgument argument = DeferredLogArgument.deferred(() -> {
            throw new IllegalStateException("boom");
        });

        assertThat(argument.render(), is("<unable to render: IllegalStateException>"));
        assertThat(argument.toString(), is("<unable to render: IllegalStateException>"));
    }

    @Test
    public void shouldNotBreakRenderingOfTheEntryWhenAnArgumentFails() {
        LogEntry entry = new LogEntry()
            .setMessageFormat("forwarded request in curl:{}")
            .setArguments(DeferredLogArgument.deferred(() -> {
                throw new IllegalStateException("boom");
            }));

        assertThat(entry.getMessage(), containsString("<unable to render: IllegalStateException>"));
        assertThat(entry.getCompactMessage(), containsString("<unable to render: IllegalStateException>"));
        assertThat(new LogEntrySerializer(new MockServerLogger()).serialize(entry), containsString("<unable to render: IllegalStateException>"));
    }
}
