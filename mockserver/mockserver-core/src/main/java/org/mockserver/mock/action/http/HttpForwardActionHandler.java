package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.proxyconfiguration.InetAddressValidator;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;

/**
 * @author jamesdbloom
 */
public class HttpForwardActionHandler extends HttpForwardAction {

    public HttpForwardActionHandler(MockServerLogger logFormatter, Configuration configuration, NettyHttpClient httpClient) {
        super(logFormatter, configuration, httpClient);
    }

    public HttpForwardActionResult handle(HttpForward httpForward, HttpRequest httpRequest) {
        httpRequest.withSecure(HttpForward.Scheme.HTTPS.equals(httpForward.getScheme()));
        int port = httpForward.getPort();
        boolean defaultPort = (HttpForward.Scheme.HTTPS.equals(httpForward.getScheme()) && port == 443)
            || (HttpForward.Scheme.HTTP.equals(httpForward.getScheme()) && port == 80);
        String hostHeader = defaultPort ? httpForward.getHost() : httpForward.getHost() + ":" + port;
        httpRequest.replaceHeader(new Header("Host", hostHeader));
        try {
            InetAddressValidator.validateForwardTarget(configuration, httpForward.getHost());
        } catch (IllegalArgumentException blocked) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setHttpRequest(httpRequest)
                        .setMessageFormat("forward action blocked by SSRF policy:{}")
                        .setArguments(blocked.getMessage())
                );
            }
            return badGatewayFuture(httpRequest);
        }
        // SSRF validation above has already resolved and vetted the host. Hand the connect path an
        // unresolved address so Netty's event-loop resolver performs the (blocking) DNS lookup off the
        // calling thread instead of resolving synchronously here via the InetSocketAddress constructor.
        return sendRequest(httpRequest, InetSocketAddress.createUnresolved(httpForward.getHost(), httpForward.getPort()), null);
    }

}
