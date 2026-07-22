package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.net.InetSocketAddress;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TproxyOriginalDestinationResolver}. These tests verify the
 * resolver's behaviour by mocking the channel's local address and configuration.
 * <p>
 * The actual TPROXY iptables integration is tested by {@link TproxyEndToEndIntegrationTest}
 * which runs in a Docker container with NET_ADMIN on Linux CI.
 */
public class TproxyOriginalDestinationResolverTest {

    @Test
    public void shouldReturnLocalAddressWhenTproxyEnabled() {
        // given
        Configuration config = Configuration.configuration()
            .transparentProxyEnabled(true)
            .transparentProxyTproxy(true);

        InetSocketAddress originalDst = new InetSocketAddress("10.99.99.1", 8080);
        Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(originalDst);

        TproxyOriginalDestinationResolver resolver = new TproxyOriginalDestinationResolver(config);

        // when
        InetSocketAddress result = resolver.resolve(channel);

        // then — the local address IS the original destination in TPROXY mode
        assertThat(result, is(originalDst));
        assertThat(result.getAddress().getHostAddress(), is("10.99.99.1"));
        assertThat(result.getPort(), is(8080));
    }

    @Test
    public void shouldReturnNullWhenTproxyDisabled() {
        // given — TPROXY not enabled
        Configuration config = Configuration.configuration()
            .transparentProxyEnabled(true)
            .transparentProxyTproxy(false);

        InetSocketAddress localAddr = new InetSocketAddress("10.99.99.1", 8080);
        Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(localAddr);

        TproxyOriginalDestinationResolver resolver = new TproxyOriginalDestinationResolver(config);

        // when
        InetSocketAddress result = resolver.resolve(channel);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullWhenTproxyNotConfigured() {
        // given — default config (TPROXY not set)
        Configuration config = Configuration.configuration()
            .transparentProxyEnabled(true);

        Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(new InetSocketAddress("10.0.0.1", 80));

        TproxyOriginalDestinationResolver resolver = new TproxyOriginalDestinationResolver(config);

        // when
        InetSocketAddress result = resolver.resolve(channel);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNullChannel() {
        // given
        Configuration config = Configuration.configuration()
            .transparentProxyEnabled(true)
            .transparentProxyTproxy(true);

        TproxyOriginalDestinationResolver resolver = new TproxyOriginalDestinationResolver(config);

        // when
        InetSocketAddress result = resolver.resolve(null);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullWhenLocalAddressIsNull() {
        // given
        Configuration config = Configuration.configuration()
            .transparentProxyEnabled(true)
            .transparentProxyTproxy(true);

        Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(null);

        TproxyOriginalDestinationResolver resolver = new TproxyOriginalDestinationResolver(config);

        // when
        InetSocketAddress result = resolver.resolve(channel);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldHandleIpv6LocalAddress() {
        // given
        Configuration config = Configuration.configuration()
            .transparentProxyEnabled(true)
            .transparentProxyTproxy(true);

        InetSocketAddress ipv6Addr = new InetSocketAddress("::1", 443);
        Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(ipv6Addr);

        TproxyOriginalDestinationResolver resolver = new TproxyOriginalDestinationResolver(config);

        // when
        InetSocketAddress result = resolver.resolve(channel);

        // then
        assertThat(result, is(ipv6Addr));
        assertThat(result.getPort(), is(443));
    }

    @Test
    public void shouldReturnHighPortLocalAddress() {
        // given
        Configuration config = Configuration.configuration()
            .transparentProxyEnabled(true)
            .transparentProxyTproxy(true);

        InetSocketAddress addr = new InetSocketAddress("192.168.1.100", 65535);
        Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(addr);

        TproxyOriginalDestinationResolver resolver = new TproxyOriginalDestinationResolver(config);

        // when
        InetSocketAddress result = resolver.resolve(channel);

        // then
        assertThat(result, is(addr));
        assertThat(result.getPort(), is(65535));
    }
}
