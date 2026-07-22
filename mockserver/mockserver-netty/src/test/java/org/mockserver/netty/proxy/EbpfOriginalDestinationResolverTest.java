package org.mockserver.netty.proxy;

import io.netty.channel.Channel;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import java.net.InetSocketAddress;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link EbpfOriginalDestinationResolver}.
 * <p>
 * These tests verify the resolver's config-gating logic, value decoding, and
 * graceful fallthrough behaviour. The actual BPF syscall interactions are not
 * testable without a Linux kernel with BPF support — those are covered by the
 * Docker-gated {@link EbpfOriginalDestinationEndToEndIntegrationTest}.
 */
public class EbpfOriginalDestinationResolverTest {

    private final Channel mockChannel = mock(Channel.class);

    // ------------------------------------------------------------------
    // Config gating
    // ------------------------------------------------------------------

    @Test
    public void shouldReturnNullWhenEbpfNotEnabled() {
        // given — eBPF not enabled (default)
        Configuration config = Configuration.configuration();
        EbpfOriginalDestinationResolver resolver = new EbpfOriginalDestinationResolver(config);

        // when
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then — returns null without attempting BPF operations
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullWhenEbpfEnabledButPlatformUnsupported() {
        // given — eBPF enabled but platform is macOS (or non-Linux)
        Configuration config = Configuration.configuration().transparentProxyEbpf(true);
        EbpfOriginalDestinationResolver resolver = new EbpfOriginalDestinationResolver(config);

        // Platform check is static — on macOS/CI, isPlatformSupported() is false.
        // If this test runs on Linux, the resolver will attempt BPF operations
        // but fail gracefully (no pinned map).
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then — returns null (either platform unsupported or BPF map unavailable)
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNullChannel() {
        // given
        Configuration config = Configuration.configuration().transparentProxyEbpf(true);
        EbpfOriginalDestinationResolver resolver = new EbpfOriginalDestinationResolver(config);

        // when — null channel
        InetSocketAddress result = resolver.resolve(null);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullForNonEpollChannel() {
        // given — eBPF enabled, mock channel (not EpollSocketChannel)
        Configuration config = Configuration.configuration().transparentProxyEbpf(true);
        EbpfOriginalDestinationResolver resolver = new EbpfOriginalDestinationResolver(config);

        // when — mock channel is not an EpollSocketChannel
        InetSocketAddress result = resolver.resolve(mockChannel);

        // then — returns null
        assertThat(result, is(nullValue()));
    }

    // ------------------------------------------------------------------
    // Value decoding
    // ------------------------------------------------------------------

    @Test
    public void shouldDecodeIpv4MapValue() {
        // given — 6-byte value: 10.99.99.1:8080
        // 10.99.99.1 = 0x0A, 0x63, 0x63, 0x01 (network byte order)
        // 8080 = 0x1F, 0x90 (network byte order)
        byte[] value = new byte[]{0x0A, 0x63, 0x63, 0x01, 0x1F, (byte) 0x90};

        // when
        InetSocketAddress result = EbpfOriginalDestinationResolver.decodeMapValue(value);

        // then
        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("10.99.99.1"));
        assertThat(result.getPort(), is(8080));
    }

    @Test
    public void shouldDecodeLocalhostMapValue() {
        // given — 127.0.0.1:443
        byte[] value = new byte[]{0x7F, 0x00, 0x00, 0x01, 0x01, (byte) 0xBB};

        // when
        InetSocketAddress result = EbpfOriginalDestinationResolver.decodeMapValue(value);

        // then
        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("127.0.0.1"));
        assertThat(result.getPort(), is(443));
    }

    @Test
    public void shouldDecodeHighPortMapValue() {
        // given — 192.168.1.100:65535
        byte[] value = new byte[]{
            (byte) 0xC0, (byte) 0xA8, 0x01, 0x64, // 192.168.1.100
            (byte) 0xFF, (byte) 0xFF               // 65535
        };

        // when
        InetSocketAddress result = EbpfOriginalDestinationResolver.decodeMapValue(value);

        // then
        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("192.168.1.100"));
        assertThat(result.getPort(), is(65535));
    }

    @Test
    public void shouldReturnNullForNullValue() {
        assertThat(EbpfOriginalDestinationResolver.decodeMapValue(null), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForShortValue() {
        // given — value too short (only 5 bytes, need 6)
        byte[] value = new byte[]{0x0A, 0x63, 0x63, 0x01, 0x1F};

        // when
        InetSocketAddress result = EbpfOriginalDestinationResolver.decodeMapValue(value);

        // then
        assertThat(result, is(nullValue()));
    }

    @Test
    public void shouldReturnNullForEmptyValue() {
        assertThat(EbpfOriginalDestinationResolver.decodeMapValue(new byte[0]), is(nullValue()));
    }

    @Test
    public void shouldHandleExtraBytes() {
        // given — 8-byte value (extra trailing bytes should be ignored)
        byte[] value = new byte[]{0x0A, 0x63, 0x63, 0x01, 0x1F, (byte) 0x90, 0x00, 0x00};

        // when
        InetSocketAddress result = EbpfOriginalDestinationResolver.decodeMapValue(value);

        // then — still decodes correctly using first 6 bytes
        assertThat(result, is(notNullValue()));
        assertThat(result.getAddress().getHostAddress(), is("10.99.99.1"));
        assertThat(result.getPort(), is(8080));
    }

    // ------------------------------------------------------------------
    // Config property defaults
    // ------------------------------------------------------------------

    @Test
    public void shouldDefaultEbpfToFalse() {
        Configuration config = Configuration.configuration();
        assertThat(config.transparentProxyEbpf(), is(false));
    }

    @Test
    public void shouldDefaultMapPath() {
        Configuration config = Configuration.configuration();
        assertThat(config.transparentProxyEbpfMapPath(), is("/sys/fs/bpf/mockserver_orig_dst"));
    }

    @Test
    public void shouldAcceptCustomMapPath() {
        Configuration config = Configuration.configuration()
            .transparentProxyEbpfMapPath("/sys/fs/bpf/custom_map");
        assertThat(config.transparentProxyEbpfMapPath(), is("/sys/fs/bpf/custom_map"));
    }

    // ------------------------------------------------------------------
    // Syscall constant sanity
    // ------------------------------------------------------------------

    @Test
    public void shouldHaveCorrectBpfConstants() {
        assertThat(EbpfOriginalDestinationResolver.BPF_MAP_LOOKUP_ELEM, is(1));
        assertThat(EbpfOriginalDestinationResolver.BPF_MAP_DELETE_ELEM, is(3));
        assertThat(EbpfOriginalDestinationResolver.BPF_OBJ_GET, is(7));
        assertThat(EbpfOriginalDestinationResolver.SOL_SOCKET, is(1));
        assertThat(EbpfOriginalDestinationResolver.SO_COOKIE, is(57));
        assertThat(EbpfOriginalDestinationResolver.MAP_VALUE_SIZE, is(6));
        assertThat(EbpfOriginalDestinationResolver.SOCKET_COOKIE_SIZE, is(8));
    }

    @Test
    public void shouldDetectCorrectSyscallNumber() {
        String arch = System.getProperty("os.arch", "");
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            assertThat(EbpfOriginalDestinationResolver.SYS_BPF_AARCH64, is(280L));
        } else {
            assertThat(EbpfOriginalDestinationResolver.SYS_BPF_X86_64, is(321L));
        }
    }
}
