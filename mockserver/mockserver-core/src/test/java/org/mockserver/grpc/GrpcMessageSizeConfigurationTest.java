package org.mockserver.grpc;

import org.junit.Test;
import org.mockserver.configuration.Configuration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

/**
 * {@code maxGrpcMessageSize} must be enforced from a live {@link Configuration} instance, not only
 * from the static property store.
 * <p>
 * All four equivalent forms are documented for this property, but enforcement read
 * {@code ConfigurationProperties} alone — so setting it on a {@code Configuration}, through the
 * serialized DTO, or via {@code PUT /mockserver/config} silently did nothing. The DTO drift guard
 * did not catch it: that checks the field round-trips, not that the value reaches enforcement.
 */
public class GrpcMessageSizeConfigurationTest {

    private static byte[] frameOfSize(int payloadSize) {
        return GrpcFrameCodec.encode(new byte[payloadSize]);
    }

    @Test
    public void shouldEnforceTheLimitFromAConfigurationInstance() {
        Configuration configuration = Configuration.configuration().maxGrpcMessageSize(16);

        assertThat("the instance value must be what enforcement resolves",
            GrpcFrameCodec.maxMessageSize(configuration), is(16));

        try {
            GrpcFrameCodec.decode(frameOfSize(1024), null, configuration);
            throw new AssertionError("expected the configured limit to be enforced");
        } catch (GrpcException e) {
            assertThat("exceeding the receive-message-size limit is RESOURCE_EXHAUSTED, not INTERNAL",
                e.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED));
            assertThat(e.getMessage(), containsString("16"));
        }
    }

    @Test
    public void shouldAcceptMessagesWithinTheConfiguredLimit() {
        Configuration configuration = Configuration.configuration().maxGrpcMessageSize(4096);
        assertThat(GrpcFrameCodec.decode(frameOfSize(1024), null, configuration).size(), is(1));
    }

    /**
     * With no instance value the static store still applies, so the existing behaviour and the
     * default are unchanged.
     */
    @Test
    public void shouldFallBackToTheStaticPropertyStoreWhenNoConfigurationIsGiven() {
        assertThat(GrpcFrameCodec.maxMessageSize(null),
            is(org.mockserver.configuration.ConfigurationProperties.maxGrpcMessageSize()));
        assertThat(GrpcFrameCodec.maxMessageSize(Configuration.configuration()),
            is(org.mockserver.configuration.ConfigurationProperties.maxGrpcMessageSize()));
    }

    /**
     * The class doc above promises the limit reaches "the bidi streaming decoder too", but every
     * test here went through {@link GrpcFrameCodec#decode} -- the unary path. Production wires
     * {@link IncrementalGrpcFrameDecoder#IncrementalGrpcFrameDecoder(Configuration)} in four places
     * and no test used that constructor at all, so the streaming half of the promise was unverified.
     */
    @Test
    public void shouldEnforceTheConfiguredLimitInTheStreamingDecoder() {
        IncrementalGrpcFrameDecoder decoder =
            new IncrementalGrpcFrameDecoder(Configuration.configuration().maxGrpcMessageSize(16));

        try {
            decoder.feed(frameOfSize(1024));
            throw new AssertionError("expected the configured limit to be enforced while streaming");
        } catch (GrpcException e) {
            assertThat(e.getStatusCode(), is(GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED));
            assertThat(e.getMessage(), containsString("16"));
        }
    }

    @Test
    public void shouldAcceptStreamedMessagesWithinTheConfiguredLimit() {
        IncrementalGrpcFrameDecoder decoder =
            new IncrementalGrpcFrameDecoder(Configuration.configuration().maxGrpcMessageSize(4096));

        assertThat(decoder.feed(frameOfSize(1024)).size(), is(1));
    }

    /**
     * A limit larger than the 8 MiB floor must also raise the decoder's buffer ceiling, or a message
     * the limit permits could never be assembled across feeds.
     */
    @Test
    public void shouldSizeTheStreamingBufferFromTheConfiguredLimit() {
        int limit = 12 * 1024 * 1024;
        IncrementalGrpcFrameDecoder decoder =
            new IncrementalGrpcFrameDecoder(Configuration.configuration().maxGrpcMessageSize(limit));

        // feed the 5-byte header alone: the decoder must buffer it rather than reject the declared
        // size, which it would if the limit had not reached the streaming path
        byte[] frame = frameOfSize(limit - 1024);
        byte[] header = new byte[5];
        System.arraycopy(frame, 0, header, 0, 5);
        assertThat(decoder.feed(header), is(empty()));
    }

    /**
     * An unbounded limit would remove the only cap on the gzip decompression path, which
     * accumulates into a heap buffer while comparing against it -- a few KB of gzip could then
     * drive a multi-GB allocation. The value is clamped at the enforcement point, so every source
     * (static property, {@link Configuration} instance, DTO, {@code PUT /mockserver/config}) is
     * covered by the same check.
     */
    @Test
    public void shouldClampAnUnboundedConfiguredLimit() {
        assertThat(GrpcFrameCodec.maxMessageSize(Configuration.configuration().maxGrpcMessageSize(Integer.MAX_VALUE)),
            is(GrpcFrameCodec.MAX_MESSAGE_SIZE_CEILING));
        assertThat("a value under the ceiling is honoured unchanged",
            GrpcFrameCodec.maxMessageSize(Configuration.configuration().maxGrpcMessageSize(8 * 1024 * 1024)),
            is(8 * 1024 * 1024));
    }
}
