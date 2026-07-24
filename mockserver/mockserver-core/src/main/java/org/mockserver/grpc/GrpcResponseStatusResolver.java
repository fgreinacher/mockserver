package org.mockserver.grpc;

import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;

import java.util.List;

/**
 * Resolves the gRPC status a client should observe for a given {@link HttpResponse}, independently
 * of transport.
 * <p>
 * This lives in core, and is shared by every gRPC response path -- {@code GrpcToHttpResponseHandler}
 * (HTTP/1.1 and HTTP/2), {@code GrpcHttp3Adapter} and {@code Http3GrpcResponseWriter} (HTTP/3) --
 * because the resolution rules are a property of the gRPC contract, not of the wire protocol.
 * Duplicating them per transport is how the two defects this class was extracted to fix arose:
 * an expectation authored with {@code withTrailer("grpc-status", ...)} returned {@code NOT_FOUND}
 * over HTTP/2 but {@code OK} over HTTP/3, and an unmatched request over HTTP/3 fabricated a
 * success.
 * <p>
 * Resolution order:
 * <ol>
 *   <li>{@code grpc-status-name} header (a MockServer convenience, mapped by name)</li>
 *   <li>numeric {@code grpc-status}, read from the headers <strong>or the trailers</strong> --
 *       consumer documentation recommends authoring it as a trailer, so both must work</li>
 *   <li>for a non-2xx HTTP status, the gRPC-over-HTTP/2 specification's HTTP-status mapping
 *       (see {@link GrpcStatusMapper#fromHttpTransportStatus(int)}) -- this is a
 *       <em>transport failure</em>, and its body must be discarded rather than framed</li>
 *   <li>otherwise {@code OK}</li>
 * </ol>
 * A numeric status is carried through <strong>verbatim</strong> (parsed, so whitespace is
 * normalised, then re-rendered) rather than round-tripped through {@link GrpcStatusMapper#fromCode}
 * -- that lookup is {@code getOrDefault(code, UNKNOWN)}, so a non-standard or future code such as
 * {@code 42} would be silently rewritten to {@code 2}.
 */
public class GrpcResponseStatusResolver {

    /**
     * MockServer's internal marker identifying a synthesized DEADLINE_EXCEEDED response.
     * <p>
     * Defined here so core and netty cannot drift; {@code GrpcToHttpResponseHandler.DEADLINE_RESPONSE_HEADER}
     * is an alias of this constant, and {@code GrpcToHttpResponseHandlerTest} pins them equal.
     */
    public static final String GRPC_DEADLINE_RESPONSE_MARKER = "x-mockserver-grpc-deadline-response";

    /**
     * The resolved status, plus whether it came from an HTTP-level failure (in which case the
     * response body is not a protobuf message of the method's output type and must be dropped).
     */
    public static final class ResolvedStatus {
        private final String code;
        private final String message;
        private final boolean transportFailure;

        private ResolvedStatus(String code, String message, boolean transportFailure) {
            this.code = code;
            this.message = message;
            this.transportFailure = transportFailure;
        }

        /**
         * The {@code grpc-status} value to emit, as a decimal string.
         */
        public String code() {
            return code;
        }

        /**
         * The {@code grpc-message} to emit, or {@code null}.
         */
        public String message() {
            return message;
        }

        /**
         * {@code true} when the status was derived from a non-2xx HTTP status rather than an
         * explicitly-authored gRPC status. The caller must not frame the response body.
         */
        public boolean isTransportFailure() {
            return transportFailure;
        }

        /**
         * {@code true} when the resolved status is {@code OK}.
         */
        public boolean isOk() {
            return String.valueOf(GrpcStatusMapper.GrpcStatusCode.OK.getCode()).equals(code);
        }
    }

    /**
     * Resolves the status for a response, including the HTTP-status fallback.
     */
    public static ResolvedStatus resolve(HttpResponse response) {
        String message = firstHeaderOrTrailer(response, GrpcStatusMapper.GRPC_MESSAGE_HEADER);
        String explicit = explicitStatus(response);
        if (explicit != null) {
            return new ResolvedStatus(explicit, message, false);
        }
        Integer httpStatusCode = response.getStatusCode();
        if (httpStatusCode != null && (httpStatusCode < 200 || httpStatusCode > 299)) {
            String reasonPhrase = response.getReasonPhrase();
            String detail = "MockServer returned HTTP " + httpStatusCode
                + (isEmpty(reasonPhrase) ? "" : " " + reasonPhrase);
            return new ResolvedStatus(
                String.valueOf(GrpcStatusMapper.fromHttpTransportStatus(httpStatusCode).getCode()),
                detail,
                true
            );
        }
        return new ResolvedStatus(String.valueOf(GrpcStatusMapper.GrpcStatusCode.OK.getCode()), message, false);
    }

    /**
     * Returns the explicitly-authored gRPC status as a decimal string, or {@code null} if the
     * response carries none. A non-numeric {@code grpc-status} (a protocol violation) is treated
     * as absent.
     */
    public static String explicitStatus(HttpResponse response) {
        String statusName = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER);
        if (!isEmpty(statusName)) {
            return String.valueOf(GrpcStatusMapper.fromName(statusName).getCode());
        }
        String statusValue = firstHeaderOrTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER);
        if (!isEmpty(statusValue)) {
            try {
                return String.valueOf(Integer.parseInt(statusValue.trim()));
            } catch (NumberFormatException ignored) {
                // non-numeric grpc-status (protocol violation) -- treat as no explicit status
            }
        }
        return null;
    }

    /**
     * Reads a value from the response headers, falling back to the trailers.
     */
    public static String firstHeaderOrTrailer(HttpResponse response, String name) {
        String value = response.getFirstHeader(name);
        if (!isEmpty(value)) {
            return value;
        }
        return firstTrailer(response, name);
    }

    /**
     * Reads the first value of a response trailer, or {@code null}.
     */
    public static String firstTrailer(HttpResponse response, String name) {
        Headers trailers = response.getTrailers();
        if (trailers == null) {
            return null;
        }
        List<String> values = trailers.getValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * Connection-specific header fields, which RFC 9114 section 4.2 forbids in HTTP/3 (and RFC 9113
     * section 8.2.2 in HTTP/2). Netty enforces this: copying {@code connection} onto an HTTP/3
     * HEADERS frame raises {@code Http3Exception: connection header included} and the response is
     * never written at all -- the client sees no frames and hangs.
     * <p>
     * These reach a matched response legitimately: {@code ResponseWriter.addConnectionHeader} adds
     * {@code connection: keep-alive} on every response, and the streaming path sets
     * {@code transfer-encoding: chunked}. They are HTTP/1.1 framing concerns and must be dropped
     * when re-framing onto HTTP/2 or HTTP/3.
     */
    private static boolean isConnectionSpecific(String name) {
        return "connection".equalsIgnoreCase(name)
            || "keep-alive".equalsIgnoreCase(name)
            || "proxy-connection".equalsIgnoreCase(name)
            || "transfer-encoding".equalsIgnoreCase(name)
            || "upgrade".equalsIgnoreCase(name)
            || "te".equalsIgnoreCase(name);
    }

    /**
     * Returns {@code true} if the header or trailer name is gRPC protocol metadata that a
     * transport emits itself, or a connection-specific field that is illegal on HTTP/2 and HTTP/3,
     * and which must therefore not be copied through from an expectation's response headers.
     */
    public static boolean isGrpcProtocolMetadata(String name) {
        if (isConnectionSpecific(name)) {
            return true;
        }
        return GrpcStatusMapper.GRPC_STATUS_HEADER.equalsIgnoreCase(name)
            || GrpcStatusMapper.GRPC_MESSAGE_HEADER.equalsIgnoreCase(name)
            || GrpcStatusMapper.GRPC_STATUS_NAME_HEADER.equalsIgnoreCase(name)
            || "content-type".equalsIgnoreCase(name)
            || "content-length".equalsIgnoreCase(name)
            || "x-grpc-service".equalsIgnoreCase(name)
            || "x-grpc-method".equalsIgnoreCase(name)
            || "x-grpc-web-content-type".equalsIgnoreCase(name)
            // MockServer's internal deadline marker. This method is consulted only by
            // passThroughHeaders, so classing it here stops the marker being copied to an HTTP/3
            // client -- it does NOT stop a client- or expectation-supplied marker taking the
            // deadline early-return in GrpcToHttpResponseHandler.encode, which reads the header
            // directly. That is closed on ingress instead: GrpcToHttpRequestHandler strips the
            // marker from every inbound request.
            || GRPC_DEADLINE_RESPONSE_MARKER.equalsIgnoreCase(name)
            || (name != null && name.startsWith(":"));
    }

    /**
     * Returns the response's headers that should be passed through to a gRPC client, excluding
     * gRPC protocol metadata the transport emits itself.
     */
    public static Headers passThroughHeaders(HttpResponse response) {
        return passThrough(response.getHeaders());
    }

    /**
     * Returns the response's <strong>trailers</strong> that should be passed through to a gRPC
     * client as trailing metadata, excluding gRPC protocol metadata the transport emits itself.
     * <p>
     * This is the trailer twin of {@link #passThroughHeaders(HttpResponse)} and exists for the same
     * reason: the exclusion rule is a property of the gRPC contract rather than of a wire protocol,
     * so it must not be re-derived per transport. Filtering out
     * {@code grpc-status}/{@code grpc-message}/{@code grpc-status-name} is what stops a
     * user-authored trailer overriding or spoofing the status the transport itself resolved and
     * emits -- the same exclusion the HTTP/2 path applies through
     * {@code GrpcToHttpResponseHandler.remainingTrailers}. Connection-specific fields, pseudo-header
     * names and {@code content-length}/{@code content-type} are excluded too because RFC 9114
     * forbids all of them in a trailer section, and a conforming client "MUST treat" a message
     * carrying one as malformed.
     */
    public static Headers passThroughTrailers(HttpResponse response) {
        return passThrough(response.getTrailers());
    }

    private static Headers passThrough(Headers source) {
        Headers passThrough = new Headers();
        if (source == null) {
            return passThrough;
        }
        for (Header header : source.getEntries()) {
            String name = header.getName().getValue();
            if (isGrpcProtocolMetadata(name)) {
                continue;
            }
            for (NottableString value : header.getValues()) {
                passThrough.withEntry(name, value.getValue());
            }
        }
        return passThrough;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
