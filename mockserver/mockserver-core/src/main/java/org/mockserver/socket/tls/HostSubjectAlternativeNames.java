package org.mockserver.socket.tls;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.mockserver.configuration.Configuration;

/**
 * Records a request's {@code Host} header as a Subject Alternative Name, skipping the work when the same
 * connection already recorded the same value and no SAN (or other server TLS input) has changed since.
 * {@link Configuration#addSubjectAlternativeName(String)} is idempotent while nothing changes, so the skip
 * is exact; a changed generation (e.g. the host was evicted or the SAN set cleared) records it again.
 */
public final class HostSubjectAlternativeNames {

    static final AttributeKey<RecordedHost> RECORDED_HOST = AttributeKey.valueOf("HOST_SUBJECT_ALTERNATIVE_NAME");

    private HostSubjectAlternativeNames() {
    }

    public static void record(Configuration configuration, Channel channel, String host) {
        if (host == null || channel == null) {
            configuration.addSubjectAlternativeName(host);
            return;
        }
        // an HTTP/2 stream is a child channel per request; remember on the connection (guard on the TYPE,
        // because an HTTP/1.1 socket's parent() is the server's listening channel)
        Channel connection = channel instanceof Http2StreamChannel && channel.parent() != null ? channel.parent() : channel;
        Attribute<RecordedHost> attribute = connection.attr(RECORDED_HOST);
        // read BEFORE the add: an add that changes the SAN set advances the generation, so the next
        // request re-checks once and then settles on the post-add generation
        long generation = configuration.serverTLSContextGeneration();
        RecordedHost recorded = attribute.get();
        if (recorded != null && recorded.generation == generation && recorded.host.equals(host)) {
            return;
        }
        configuration.addSubjectAlternativeName(host);
        attribute.set(new RecordedHost(host, generation));
    }

    static final class RecordedHost {
        private final String host;
        private final long generation;

        private RecordedHost(String host, long generation) {
            this.host = host;
            this.generation = generation;
        }
    }
}
