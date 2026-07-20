package org.mockserver.grpc;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.grpc.GrpcStatusMapper.*;

public class GrpcStatusMapperTest {

    @Test
    public void shouldMapCodeToStatus() {
        assertThat(fromCode(0), is(GrpcStatusCode.OK));
        assertThat(fromCode(1), is(GrpcStatusCode.CANCELLED));
        assertThat(fromCode(2), is(GrpcStatusCode.UNKNOWN));
        assertThat(fromCode(3), is(GrpcStatusCode.INVALID_ARGUMENT));
        assertThat(fromCode(5), is(GrpcStatusCode.NOT_FOUND));
        assertThat(fromCode(13), is(GrpcStatusCode.INTERNAL));
        assertThat(fromCode(16), is(GrpcStatusCode.UNAUTHENTICATED));
    }

    @Test
    public void shouldDefaultToUnknownForInvalidCode() {
        assertThat(fromCode(99), is(GrpcStatusCode.UNKNOWN));
    }

    @Test
    public void shouldMapNameToStatus() {
        assertThat(fromName("OK"), is(GrpcStatusCode.OK));
        assertThat(fromName("NOT_FOUND"), is(GrpcStatusCode.NOT_FOUND));
        assertThat(fromName("ok"), is(GrpcStatusCode.OK));
        assertThat(fromName("not_found"), is(GrpcStatusCode.NOT_FOUND));
    }

    @Test
    public void shouldDefaultToUnknownForInvalidName() {
        assertThat(fromName("BOGUS"), is(GrpcStatusCode.UNKNOWN));
    }

    @Test
    public void shouldReturnUnknownForNullName() {
        assertThat(fromName(null), is(GrpcStatusCode.UNKNOWN));
    }

    @Test
    public void shouldMapHttpStatusToGrpc() {
        assertThat(fromHttpStatus(200), is(GrpcStatusCode.OK));
        assertThat(fromHttpStatus(400), is(GrpcStatusCode.INVALID_ARGUMENT));
        assertThat(fromHttpStatus(404), is(GrpcStatusCode.NOT_FOUND));
        assertThat(fromHttpStatus(500), is(GrpcStatusCode.UNKNOWN));
    }

    /**
     * The gRPC-over-HTTP/2 spec's "HTTP-Status to Status code" mapping, used when a call fails at
     * the HTTP transport level and carries no {@code grpc-status} of its own.
     * <p>
     * Deliberately different from {@link GrpcStatusMapper#fromHttpStatus(int)}: 404 here means "the
     * server does not implement this method" ({@code UNIMPLEMENTED}), not "the status whose
     * canonical HTTP rendering is 404" ({@code NOT_FOUND}). MockServer relies on this so an
     * unmatched gRPC request surfaces the way a real gRPC server would.
     */
    @Test
    public void shouldMapHttpTransportStatusToGrpcPerSpec() {
        assertThat(fromHttpTransportStatus(400), is(GrpcStatusCode.INTERNAL));
        assertThat(fromHttpTransportStatus(401), is(GrpcStatusCode.UNAUTHENTICATED));
        assertThat(fromHttpTransportStatus(403), is(GrpcStatusCode.PERMISSION_DENIED));
        assertThat(fromHttpTransportStatus(404), is(GrpcStatusCode.UNIMPLEMENTED));
        assertThat(fromHttpTransportStatus(429), is(GrpcStatusCode.UNAVAILABLE));
        assertThat(fromHttpTransportStatus(502), is(GrpcStatusCode.UNAVAILABLE));
        assertThat(fromHttpTransportStatus(503), is(GrpcStatusCode.UNAVAILABLE));
        assertThat(fromHttpTransportStatus(504), is(GrpcStatusCode.UNAVAILABLE));
        assertThat("anything unlisted falls back to UNKNOWN",
            fromHttpTransportStatus(418), is(GrpcStatusCode.UNKNOWN));
        assertThat("this mapping must not agree with fromHttpStatus on 404",
            fromHttpTransportStatus(404), is(not(fromHttpStatus(404))));
    }

    @Test
    public void shouldDetectGrpcContentType() {
        assertThat(isGrpcContentType("application/grpc"), is(true));
        assertThat(isGrpcContentType("application/grpc+proto"), is(true));
        assertThat(isGrpcContentType("application/grpc+json"), is(true));
        assertThat(isGrpcContentType("application/json"), is(false));
        assertThat(isGrpcContentType(null), is(false));
        // gRPC-Web content types must NOT match standard gRPC
        assertThat(isGrpcContentType("application/grpc-web"), is(false));
        assertThat(isGrpcContentType("application/grpc-web+proto"), is(false));
        assertThat(isGrpcContentType("application/grpc-web-text"), is(false));
        assertThat(isGrpcContentType("application/grpc-web-text+proto"), is(false));
    }

    @Test
    public void shouldReturnCorrectGrpcCodes() {
        assertThat(GrpcStatusCode.OK.getCode(), is(0));
        assertThat(GrpcStatusCode.INTERNAL.getCode(), is(13));
    }

    @Test
    public void shouldReturnCorrectHttpStatus() {
        assertThat(GrpcStatusCode.OK.getHttpStatus(), is(200));
        assertThat(GrpcStatusCode.NOT_FOUND.getHttpStatus(), is(404));
        assertThat(GrpcStatusCode.UNAUTHENTICATED.getHttpStatus(), is(401));
    }
}
