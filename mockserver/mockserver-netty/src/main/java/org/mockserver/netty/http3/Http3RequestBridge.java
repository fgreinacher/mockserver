package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts between HTTP/3 frames and MockServer's {@link HttpRequest}/{@link HttpResponse} model.
 * <p>
 * These are pure conversion helpers with no Netty channel dependencies, so they
 * can be unit-tested without the native QUIC transport.
 */
public final class Http3RequestBridge {

    private Http3RequestBridge() {
        // utility class
    }

    /**
     * Build a MockServer {@link HttpRequest} from the HTTP/3 pseudo-headers and
     * accumulated body bytes.
     *
     * @param method    the :method pseudo-header value
     * @param path      the :path pseudo-header value (may include query string)
     * @param scheme    the :scheme pseudo-header value (nullable)
     * @param authority the :authority pseudo-header value (nullable)
     * @param headers   list of non-pseudo-header name/value pairs
     * @param body      the accumulated request body bytes (may be empty)
     * @return a fully populated HttpRequest
     */
    public static HttpRequest toHttpRequest(
        String method,
        String path,
        String scheme,
        String authority,
        List<Map.Entry<String, String>> headers,
        byte[] body
    ) {
        // split path and query
        String requestPath = path;
        String queryString = "";
        if (path != null) {
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) {
                requestPath = path.substring(0, queryIndex);
                queryString = path.substring(queryIndex + 1);
            }
        }
        if (requestPath == null || requestPath.isEmpty()) {
            requestPath = "/";
        }

        HttpRequest request = HttpRequest.request()
            .withMethod(method != null ? method : "GET")
            .withPath(requestPath)
            .withSecure(true) // HTTP/3 is always over TLS
            // the HTTP/3 ALPN identifier is always "h3", so the negotiated protocol is
            // server-trusted and cannot be spoofed by a header (unlike the h2c upgrade);
            // tag the request so it can be matched on / verified by protocol
            .withProtocol(Protocol.HTTP_3);

        if (!queryString.isEmpty()) {
            request.withQueryStringParameters(parseQueryString(queryString));
        }

        // set authority as Host header if present
        if (authority != null && !authority.isEmpty()) {
            request.withHeader("host", authority);
        }

        // add regular headers
        if (headers != null) {
            for (Map.Entry<String, String> header : headers) {
                request.withHeader(header.getKey(), header.getValue());
            }
        }

        // set body -- use string body for text content types so that expectation
        // matching (which compares string bodies) works correctly; use binary body
        // for everything else
        if (body != null && body.length > 0) {
            String contentType = null;
            if (headers != null) {
                for (Map.Entry<String, String> header : headers) {
                    if ("content-type".equalsIgnoreCase(header.getKey())) {
                        contentType = header.getValue();
                        break;
                    }
                }
            }
            if (isTextContentType(contentType)) {
                java.nio.charset.Charset charset = extractCharset(contentType);
                request.withBody(new String(body, charset));
            } else {
                request.withBody(body);
            }
        }

        return request;
    }

    /**
     * Extract pseudo-headers and regular headers from an HTTP/3 headers frame.
     */
    public static ParsedHeaders parseHeaders(Http3HeadersFrame headersFrame) {
        Http3Headers h3Headers = headersFrame.headers();
        String method = charSeqToString(h3Headers.method());
        String path = charSeqToString(h3Headers.path());
        String scheme = charSeqToString(h3Headers.scheme());
        String authority = charSeqToString(h3Headers.authority());

        List<Map.Entry<String, String>> regularHeaders = new ArrayList<>();
        h3Headers.forEach(entry -> {
            String name = entry.getKey().toString();
            // skip pseudo-headers (they start with ':')
            if (!name.startsWith(":")) {
                regularHeaders.add(new AbstractMap.SimpleImmutableEntry<>(name, entry.getValue().toString()));
            }
        });

        return new ParsedHeaders(method, path, scheme, authority, regularHeaders);
    }

    /**
     * Convert a MockServer {@link HttpResponse} into an HTTP/3 headers frame.
     */
    public static DefaultHttp3HeadersFrame toHttp3HeadersFrame(HttpResponse response) {
        return toHttp3HeadersFrame(response, false);
    }

    /**
     * As {@link #toHttp3HeadersFrame(HttpResponse)}, additionally dropping {@code content-length}
     * when {@code streaming} is true. A streamed response's length is not known when the headers are
     * sent, and a {@code content-length} copied from (for example) a relayed upstream response
     * describes a different body than the one actually streamed - which a conforming client treats
     * as a malformed message rather than merely ignoring.
     */
    public static DefaultHttp3HeadersFrame toHttp3HeadersFrame(HttpResponse response, boolean streaming) {
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        int statusCode = response.getStatusCode() != null ? response.getStatusCode() : 200;
        headersFrame.headers().status(String.valueOf(statusCode));
        headersFrame.headers().add("server", "mockserver-http3");

        if (response.getHeaderMultimap() != null) {
            response.getHeaderMultimap().entries().forEach(entry -> {
                // Locale.ROOT: under a Turkish default locale "CONNECTION" folds to "connectıon"
                // and the forbidden-header filter below is silently bypassed entirely
                String name = entry.getKey().getValue().toLowerCase(java.util.Locale.ROOT);
                if (isForbiddenHttp3ResponseHeader(name, entry.getValue().getValue())
                    || (streaming && CONTENT_LENGTH.equals(name))) {
                    return;
                }
                headersFrame.headers().add(name, entry.getValue().getValue());
            });
        }

        return headersFrame;
    }

    private static final String CONTENT_LENGTH = "content-length";

    /**
     * Whether a header field is one HTTP/3 forbids on a response.
     * <p>
     * RFC 9114 section 4.2 bans the connection-specific fields {@code Connection}, {@code Keep-Alive},
     * {@code Proxy-Connection}, {@code Transfer-Encoding} and {@code Upgrade} outright, and permits
     * {@code TE} only with the exact value {@code trailers}. A receiver "MUST treat" a message
     * carrying any of them "as malformed", so letting one through does not merely add a useless
     * header - it can make a conforming client reject the whole response.
     * <p>
     * This previously filtered only {@code connection} and {@code transfer-encoding}. The other
     * three reach a model {@link HttpResponse} two ways: an expectation can set any header
     * explicitly, and — more importantly — in proxy/forward mode
     * {@code FullHttpResponseToMockServerHttpResponse} copies an upstream response's headers onto
     * the model wholesale, stripping only the HTTP/2 extension headers. An HTTP/1.1 origin commonly
     * answers with {@code Keep-Alive: timeout=5, max=100}, which was therefore relayed verbatim
     * onto an HTTP/3 response.
     */
    private static boolean isForbiddenHttp3ResponseHeader(String lowerCaseName, String value) {
        switch (lowerCaseName) {
            case "connection":
            case "keep-alive":
            case "proxy-connection":
            case "transfer-encoding":
            case "upgrade":
                return true;
            case "te":
                // TE is allowed, but only with the single value "trailers"
                return !"trailers".equalsIgnoreCase(value);
            default:
                return false;
        }
    }

    /**
     * Build a trailing HTTP/3 HEADERS frame from the response trailers, or null when the
     * response carries no trailers. The trailer field names are lower-cased per HTTP/3
     * (HTTP/2-style) header conventions. This is the general-purpose (non-gRPC) trailer
     * frame; gRPC trailers (grpc-status / grpc-message) are emitted separately by
     * {@code Http3GrpcResponseWriter}.
     */
    public static DefaultHttp3HeadersFrame toHttp3TrailersFrame(HttpResponse response) {
        if (response.getTrailerMultimap() == null || response.getTrailerMultimap().isEmpty()) {
            return null;
        }
        DefaultHttp3HeadersFrame trailersFrame = new DefaultHttp3HeadersFrame();
        response.getTrailerMultimap().entries().forEach(entry ->
            trailersFrame.headers().add(entry.getKey().getValue().toLowerCase(), entry.getValue().getValue())
        );
        return trailersFrame;
    }

    /**
     * Convert the body of a MockServer {@link HttpResponse} into an HTTP/3 data frame.
     * Returns null if the response has no body.
     */
    public static DefaultHttp3DataFrame toHttp3DataFrame(HttpResponse response) {
        byte[] bodyBytes = response.getBodyAsRawBytes();
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }
        return new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(bodyBytes));
    }

    /**
     * Accumulate body data from an HTTP/3 data frame into a composite buffer.
     */
    public static void accumulateBody(CompositeByteBuf composite, Http3DataFrame dataFrame) {
        ByteBuf content = dataFrame.content();
        if (content.isReadable()) {
            composite.addComponent(true, content.retain());
        }
    }

    /**
     * Read the accumulated composite buffer into a byte array.
     */
    public static byte[] readAccumulatedBody(CompositeByteBuf composite) {
        if (composite.readableBytes() == 0) {
            return new byte[0];
        }
        byte[] body = new byte[composite.readableBytes()];
        composite.readBytes(body);
        return body;
    }

    /**
     * Determine if the content-type header indicates text content that should be
     * stored as a string body rather than binary.
     */
    private static boolean isTextContentType(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            // no content-type: assume text to maximise expectation matching compatibility
            return true;
        }
        String lower = contentType.toLowerCase();
        return lower.startsWith("text/")
            || lower.contains("json")
            || lower.contains("xml")
            || lower.contains("html")
            || lower.contains("javascript")
            || lower.contains("yaml")
            || lower.contains("csv")
            || lower.contains("x-www-form-urlencoded");
    }

    /**
     * Extract the charset from a content-type header value, defaulting to UTF-8.
     */
    private static java.nio.charset.Charset extractCharset(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            int charsetIndex = lower.indexOf("charset=");
            if (charsetIndex >= 0) {
                String charsetName = contentType.substring(charsetIndex + 8).trim();
                // strip quotes and trailing parameters
                if (charsetName.startsWith("\"")) {
                    charsetName = charsetName.substring(1);
                }
                int endIndex = charsetName.indexOf(';');
                if (endIndex >= 0) {
                    charsetName = charsetName.substring(0, endIndex);
                }
                if (charsetName.endsWith("\"")) {
                    charsetName = charsetName.substring(0, charsetName.length() - 1);
                }
                try {
                    return java.nio.charset.Charset.forName(charsetName.trim());
                } catch (Exception ignored) {
                    // fall through to default
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8;
    }

    private static String charSeqToString(CharSequence seq) {
        return seq != null ? seq.toString() : null;
    }

    private static org.mockserver.model.Parameters parseQueryString(String queryString) {
        org.mockserver.model.Parameters parameters = new org.mockserver.model.Parameters();
        if (queryString == null || queryString.isEmpty()) {
            return parameters;
        }
        for (String param : queryString.split("&")) {
            int eqIndex = param.indexOf('=');
            if (eqIndex >= 0) {
                String name = param.substring(0, eqIndex);
                String value = param.substring(eqIndex + 1);
                parameters.withEntry(name, value);
            } else if (!param.isEmpty()) {
                parameters.withEntry(param, "");
            }
        }
        return parameters;
    }

    /**
     * Parsed HTTP/3 pseudo-headers and regular headers.
     */
    public static final class ParsedHeaders {
        private final String method;
        private final String path;
        private final String scheme;
        private final String authority;
        private final List<Map.Entry<String, String>> headers;

        public ParsedHeaders(String method, String path, String scheme, String authority, List<Map.Entry<String, String>> headers) {
            this.method = method;
            this.path = path;
            this.scheme = scheme;
            this.authority = authority;
            this.headers = headers;
        }

        public String method() {
            return method;
        }

        public String path() {
            return path;
        }

        public String scheme() {
            return scheme;
        }

        public String authority() {
            return authority;
        }

        public List<Map.Entry<String, String>> headers() {
            return headers;
        }
    }
}
