package org.mockserver.socket.tls;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2StreamChannel;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

public class HostSubjectAlternativeNamesTest {

    /** Counts calls into the per-request SAN work the memo exists to skip. */
    private static final class CountingConfiguration extends Configuration {
        private final AtomicInteger adds = new AtomicInteger();

        @Override
        public void addSubjectAlternativeName(String host) {
            adds.incrementAndGet();
            super.addSubjectAlternativeName(host);
        }
    }

    @Test
    public void shouldRecordTheHostOnceForRepeatRequestsOnTheSameConnection() {
        CountingConfiguration configuration = new CountingConfiguration();
        EmbeddedChannel connection = new EmbeddedChannel(new ChannelInboundHandlerAdapter());

        for (int i = 0; i < 10; i++) {
            HostSubjectAlternativeNames.record(configuration, connection, "repeat.host.test:1080");
        }

        assertThat(configuration.sslSubjectAlternativeNameDomains(), hasItem("repeat.host.test"));
        // the first add changes the SAN set, so the second request re-checks once, then it settles
        assertThat(configuration.adds.get(), is(2));
    }

    @Test
    public void shouldRecordEachDistinctHostAndEachConnection() {
        CountingConfiguration configuration = new CountingConfiguration();
        EmbeddedChannel first = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel second = new EmbeddedChannel(new ChannelInboundHandlerAdapter());

        HostSubjectAlternativeNames.record(configuration, first, "one.host.test");
        HostSubjectAlternativeNames.record(configuration, first, "two.host.test");
        HostSubjectAlternativeNames.record(configuration, second, "one.host.test");

        assertThat(configuration.sslSubjectAlternativeNameDomains(), hasItems("one.host.test", "two.host.test"));
        assertThat(configuration.adds.get(), is(3));
    }

    @Test
    public void shouldRecordTheHostAgainAfterTheSubjectAlternativeNamesAreCleared() {
        Configuration configuration = configuration();
        EmbeddedChannel connection = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        HostSubjectAlternativeNames.record(configuration, connection, "cleared.host.test");
        HostSubjectAlternativeNames.record(configuration, connection, "cleared.host.test");
        assertThat(configuration.sslSubjectAlternativeNameDomains(), hasItem("cleared.host.test"));

        configuration.clearSslSubjectAlternativeNameDomains();
        HostSubjectAlternativeNames.record(configuration, connection, "cleared.host.test");

        assertThat(configuration.sslSubjectAlternativeNameDomains(), hasItem("cleared.host.test"));
    }

    @Test
    public void shouldRecordTheHostAgainAfterItWasEvictedByNewerHosts() {
        Configuration configuration = configuration().maxSubjectAlternativeNames(3);
        configuration.sslSubjectAlternativeNameDomains(new java.util.HashSet<>());
        EmbeddedChannel connection = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        EmbeddedChannel otherConnection = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        HostSubjectAlternativeNames.record(configuration, connection, "kept.host.test");
        HostSubjectAlternativeNames.record(configuration, connection, "kept.host.test");

        for (int i = 0; i < 3; i++) {
            HostSubjectAlternativeNames.record(configuration, otherConnection, "evictor-" + i + ".host.test");
        }
        assertThat(configuration.sslSubjectAlternativeNameDomains(), not(hasItem("kept.host.test")));

        HostSubjectAlternativeNames.record(configuration, connection, "kept.host.test");
        assertThat(configuration.sslSubjectAlternativeNameDomains(), hasItem("kept.host.test"));
    }

    @Test
    public void shouldRememberAnHttp2StreamsHostOnItsConnectionNotTheStream() {
        CountingConfiguration configuration = new CountingConfiguration();
        EmbeddedChannel connection = new EmbeddedChannel(new ChannelInboundHandlerAdapter());

        for (int stream = 0; stream < 5; stream++) {
            Http2StreamChannel streamChannel = mock(Http2StreamChannel.class);
            when(streamChannel.parent()).thenReturn(connection);
            HostSubjectAlternativeNames.record(configuration, streamChannel, "h2.host.test");
        }

        assertThat(connection.attr(HostSubjectAlternativeNames.RECORDED_HOST).get(), is(notNullValue()));
        assertThat(configuration.adds.get(), is(2));
    }

    @Test
    public void shouldRememberAnHttp1HostOnTheSocketNotItsParentListeningChannel() {
        CountingConfiguration configuration = new CountingConfiguration();
        EmbeddedChannel listening = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        io.netty.channel.Channel socket = mock(io.netty.channel.Channel.class);
        when(socket.parent()).thenReturn(listening);
        io.netty.util.Attribute<HostSubjectAlternativeNames.RecordedHost> socketAttribute = new EmbeddedChannel().attr(HostSubjectAlternativeNames.RECORDED_HOST);
        when(socket.attr(HostSubjectAlternativeNames.RECORDED_HOST)).thenReturn(socketAttribute);

        HostSubjectAlternativeNames.record(configuration, socket, "h1.host.test");

        assertThat(socketAttribute.get(), is(notNullValue()));
        assertThat(listening.attr(HostSubjectAlternativeNames.RECORDED_HOST).get(), is(nullValue()));
    }

    @Test
    public void shouldIgnoreAMissingOrBlankHost() {
        Configuration configuration = configuration();
        EmbeddedChannel connection = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        int before = configuration.sslSubjectAlternativeNameDomains().size();

        HostSubjectAlternativeNames.record(configuration, connection, null);
        HostSubjectAlternativeNames.record(configuration, connection, "");
        HostSubjectAlternativeNames.record(configuration, null, "no-channel.host.test");

        assertThat(configuration.sslSubjectAlternativeNameDomains().size(), is(before + 1));
        assertThat(configuration.sslSubjectAlternativeNameDomains(), hasItem("no-channel.host.test"));
    }
}
