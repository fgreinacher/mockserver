package org.mockserver.client;

import org.junit.Test;
import org.mockserver.httpclient.SocketConnectionException;
import org.mockserver.socket.PortFactory;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class MockServerClientErrorsTest {

    @Test
    public void shouldHandleSocketErrorForReset() {
        // given
        int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("localhost", freePort);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, mockServerClient::reset);

        // then
        // Connecting to an unbound port always fails - the type
        // (SocketConnectionException) is the real contract asserted above. The
        // message, however, has two valid manifestations of that same failed
        // connection, so accept either:
        //   1. connect-refused: "Unable to connect to socket localhost/<addr>:<port>"
        //      (localhost may resolve to 127.0.0.1 or ::1 depending on the host's
        //      name resolution, so assert the socket prefix + port rather than a
        //      hardcoded IPv4 literal - the old equalTo("...127.0.0.1:port") flaked
        //      whenever localhost resolved to IPv6);
        //   2. connect-race teardown: "Channel handler removed before valid
        //      response has been received" (the channel is torn down before the
        //      refusal surfaces).
        assertThat(clientException.getMessage(), anyOf(
            allOf(startsWith("Unable to connect to socket localhost/"), endsWith(":" + freePort)),
            containsString("Channel handler removed before valid response has been received")));
    }

    @Test
    public void shouldHandleSocketErrorForClear() {
        // given
        int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("localhost", freePort);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, () -> mockServerClient.clear(request()));

        // then
        // Connecting to an unbound port always fails - the type
        // (SocketConnectionException) is the real contract asserted above. The
        // message, however, has two valid manifestations of that same failed
        // connection, so accept either:
        //   1. connect-refused: "Unable to connect to socket localhost/<addr>:<port>"
        //      (localhost may resolve to 127.0.0.1 or ::1 depending on the host's
        //      name resolution, so assert the socket prefix + port rather than a
        //      hardcoded IPv4 literal - the old equalTo("...127.0.0.1:port") flaked
        //      whenever localhost resolved to IPv6);
        //   2. connect-race teardown: "Channel handler removed before valid
        //      response has been received" (the channel is torn down before the
        //      refusal surfaces).
        assertThat(clientException.getMessage(), anyOf(
            allOf(startsWith("Unable to connect to socket localhost/"), endsWith(":" + freePort)),
            containsString("Channel handler removed before valid response has been received")));
    }

    @Test
    public void shouldHandleSocketErrorForExpectation() {
        // given
        int freePort = PortFactory.findFreePort();
        MockServerClient mockServerClient = new MockServerClient("localhost", freePort);

        // when
        SocketConnectionException clientException = assertThrows(SocketConnectionException.class, () -> mockServerClient.when(request()).respond(response()));

        // then
        // Connecting to an unbound port always fails - the type
        // (SocketConnectionException) is the real contract asserted above. The
        // message, however, has two valid manifestations of that same failed
        // connection, so accept either:
        //   1. connect-refused: "Unable to connect to socket localhost/<addr>:<port>"
        //      (localhost may resolve to 127.0.0.1 or ::1 depending on the host's
        //      name resolution, so assert the socket prefix + port rather than a
        //      hardcoded IPv4 literal - the old equalTo("...127.0.0.1:port") flaked
        //      whenever localhost resolved to IPv6);
        //   2. connect-race teardown: "Channel handler removed before valid
        //      response has been received" (the channel is torn down before the
        //      refusal surfaces).
        assertThat(clientException.getMessage(), anyOf(
            allOf(startsWith("Unable to connect to socket localhost/"), endsWith(":" + freePort)),
            containsString("Channel handler removed before valid response has been received")));
    }

}
