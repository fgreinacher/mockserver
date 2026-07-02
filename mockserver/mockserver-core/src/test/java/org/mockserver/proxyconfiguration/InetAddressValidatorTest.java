package org.mockserver.proxyconfiguration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThrows;

/**
 * Behaviour: the validator blocks SSRF-suspect targets when
 * {@code forwardProxyBlockPrivateNetworks=true}, and is a no-op otherwise.
 * Tests use the per-instance Configuration so they don't depend on global state.
 */
public class InetAddressValidatorTest {

    private Configuration enabled;
    private Configuration disabled;

    @Before
    public void setUp() {
        enabled = Configuration.configuration().forwardProxyBlockPrivateNetworks(true);
        disabled = Configuration.configuration().forwardProxyBlockPrivateNetworks(false);
    }

    @After
    public void tearDown() {
        // ensure system property is not lingering between test methods
        ConfigurationProperties.forwardProxyBlockPrivateNetworks(false);
    }

    @Test
    public void shouldAllowPublicLiteralAddressWhenEnabled() {
        InetAddressValidator.validateForwardTarget(enabled, "8.8.8.8");
    }

    @Test
    public void shouldBlockLoopbackLiteralWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "127.0.0.1"));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockLoopbackHostNameWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "localhost"));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockIpv6LoopbackWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "::1"));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockBracketedIpv6LoopbackWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "[::1]"));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockRfc1918TenWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "10.0.0.1"));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldBlockRfc1918OneSeventyTwoWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "172.16.0.1"));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldBlockRfc1918OneNinetyTwoWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "192.168.1.1"));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldBlockCloudMetadataEndpointWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "169.254.169.254"));
        assertThat(ex.getMessage(), containsString("metadata"));
    }

    @Test
    public void shouldBlockIpv6UniqueLocalAddressWhenEnabled() {
        // RFC 4193 ULA range (fc00::/7) is not covered by Java's isSiteLocalAddress()
        // — common Docker / Kubernetes / Tailscale IPv6 prefix. Must be blocked explicitly.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "fd00::1"));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldBlockWildcardAddressWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "0.0.0.0"));
        assertThat(ex.getMessage(), containsString("wildcard"));
    }

    @Test
    public void shouldRejectUnresolvableHostWhenEnabled() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, "no-such-host-for-mockserver-ssrf.invalid"));
        assertThat(ex.getMessage(), containsString("could not be resolved"));
    }

    @Test
    public void shouldAllowEverythingWhenDisabled() {
        // none of these should throw
        InetAddressValidator.validateForwardTarget(disabled, "127.0.0.1");
        InetAddressValidator.validateForwardTarget(disabled, "localhost");
        InetAddressValidator.validateForwardTarget(disabled, "10.0.0.1");
        InetAddressValidator.validateForwardTarget(disabled, "169.254.169.254");
        InetAddressValidator.validateForwardTarget(disabled, "0.0.0.0");
    }

    @Test
    public void shouldTreatBlankHostAsNoOp() {
        // ambiguous input should not raise — caller will surface its own error
        InetAddressValidator.validateForwardTarget(enabled, "");
        InetAddressValidator.validateForwardTarget(enabled, (String) null);
    }

    // ---- InetAddress overload (validate the SAME resolved address that is connected) ----

    @Test
    public void shouldBlockLoopbackInetAddressWhenEnabled() throws UnknownHostException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, loopback));
        assertThat(ex.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldBlockCloudMetadataInetAddressWhenEnabled() throws UnknownHostException {
        InetAddress metadata = InetAddress.getByName("169.254.169.254");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, metadata));
        assertThat(ex.getMessage(), containsString("metadata"));
    }

    @Test
    public void shouldBlockRfc1918InetAddressWhenEnabled() throws UnknownHostException {
        InetAddress priv = InetAddress.getByName("10.1.2.3");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> InetAddressValidator.validateForwardTarget(enabled, priv));
        assertThat(ex.getMessage(), containsString("private"));
    }

    @Test
    public void shouldAllowPublicInetAddressWhenEnabled() throws UnknownHostException {
        // literal public address so no DNS dependency; must not throw
        InetAddressValidator.validateForwardTarget(enabled, InetAddress.getByName("8.8.8.8"));
    }

    @Test
    public void shouldBeNoOpForInetAddressOverloadWhenDisabled() throws UnknownHostException {
        // even a loopback address passes when the feature is disabled
        InetAddressValidator.validateForwardTarget(disabled, InetAddress.getByName("127.0.0.1"));
    }

    @Test
    public void shouldTreatNullInetAddressAsNoOp() {
        InetAddressValidator.validateForwardTarget(enabled, (InetAddress) null);
    }
}
