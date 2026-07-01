package org.mockserver.exception;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.socket.tls.SniHandler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.exception.ExceptionHandling.sniDescription;

/**
 * Locks the SNI-on-fault-log helpers used to identify which target host a failed SSL/decoder
 * connection was for: {@link SniHandler#getSniHostname(Channel)} (reads the
 * {@link SniHandler#SNI_HOSTNAME} channel attribute) and
 * {@link ExceptionHandling#sniDescription(Channel...)} (formats the log suffix, scanning several
 * channels as the relay handlers do). Uses {@link EmbeddedChannel} so no real network is needed.
 */
public class ExceptionHandlingSniDescriptionTest {

    // ---- SniHandler.getSniHostname ----

    @Test
    public void shouldReturnNullSniHostnameForNullChannel() {
        assertThat(SniHandler.getSniHostname(null), is(nullValue()));
    }

    @Test
    public void shouldReturnNullSniHostnameWhenChannelHasNoAttribute() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertThat(SniHandler.getSniHostname(channel), is(nullValue()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldReturnRecordedSniHostname() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.attr(SniHandler.SNI_HOSTNAME).set("example.com");
            assertThat(SniHandler.getSniHostname(channel), is("example.com"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    // ---- ExceptionHandling.sniDescription ----

    @Test
    public void shouldReturnEmptyDescriptionForNullChannelArray() {
        assertThat(sniDescription((Channel[]) null), is(""));
    }

    @Test
    public void shouldReturnEmptyDescriptionWhenNoChannelHasSni() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertThat(sniDescription(channel), is(""));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldFormatDescriptionWithLeadingSpaceForChannelWithSni() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            channel.attr(SniHandler.SNI_HOSTNAME).set("example.com");
            assertThat(sniDescription(channel), is(" (SNI: example.com)"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldReturnFirstNonNullSniAcrossChannels() {
        // mirrors the relay-handler usage where several channels (upstream + downstream) are passed
        // and the SNI is found on whichever channel recorded it
        EmbeddedChannel channelWithNoSni = new EmbeddedChannel();
        EmbeddedChannel channelWithSni = new EmbeddedChannel();
        try {
            channelWithSni.attr(SniHandler.SNI_HOSTNAME).set("example.com");
            assertThat(sniDescription(channelWithNoSni, channelWithSni), is(" (SNI: example.com)"));
        } finally {
            channelWithNoSni.finishAndReleaseAll();
            channelWithSni.finishAndReleaseAll();
        }
    }

}
