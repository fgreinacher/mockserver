package org.mockserver.serialization.curl;

import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import org.apache.commons.lang3.Strings;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpRequestToFullHttpRequest;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.NottableString;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * @author jamesdbloom
 */
public class HttpRequestToCurlSerializer {

    private final MockServerLogger mockServerLogger;

    public HttpRequestToCurlSerializer(MockServerLogger mockServerLogger) {
        this.mockServerLogger = mockServerLogger;
    }

    public String toCurl(HttpRequest request) {
        return toCurl(request, null);
    }

    public String toCurl(HttpRequest request, @Nullable InetSocketAddress remoteAddress) {
        StringBuilder curlString = new StringBuilder();
        if (request != null) {
            if (isNotBlank(request.getFirstHeader(HOST.toString())) || remoteAddress != null) {
                boolean isSsl = request.isSecure() != null && request.isSecure();
                curlString.append("curl -v");
                curlString.append(" ");
                curlString.append("'");
                curlString.append((isSsl ? "https" : "http"));
                curlString.append("://");
                curlString.append(getHostAndPort(request, remoteAddress));
                curlString.append(getUri(request));
                curlString.append("'");
                if (!hasDefaultMethod(request)) {
                    curlString.append(" -X ").append(request.getMethod().getValue());
                }
                for (Header header : request.getHeaderList()) {
                    for (NottableString headerValue : header.getValues()) {
                        curlString.append(" -H ").append(singleQuote(header.getName().getValue() + ": " + headerValue.getValue()));
                        if (header.getName().getValue().toLowerCase().contains("Accept-Encoding".toLowerCase())) {
                            if (headerValue.getValue().toLowerCase().contains("gzip")
                                || headerValue.getValue().toLowerCase().contains("deflate")
                                || headerValue.getValue().toLowerCase().contains("sdch")) {
                                curlString.append(" ");
                                curlString.append("--compress");
                            }
                        }
                    }
                }
                curlString.append(getCookieHeader(request));
                // read without caching: the request is usually already retained in the event log
                String body = request.getBody() != null ? request.getBody().toStringWithoutCaching() : null;
                if (isNotBlank(body)) {
                    curlString.append(" --data ").append(singleQuote(body));
                }
            } else {
                curlString.append("no host header or remote address specified");
            }
        } else {
            curlString.append("null HttpRequest");
        }
        return curlString.toString();
    }

    private boolean hasDefaultMethod(HttpRequest request) {
        return request.getMethod() == null || isBlank(request.getMethod().getValue()) || request.getMethod().getValue().equalsIgnoreCase("GET");
    }

    private String getUri(HttpRequest request) {
        String uri = new MockServerHttpRequestToFullHttpRequest(mockServerLogger, null).getURI(request, null);
        if (isBlank(uri)) {
            uri = "/";
        } else if (!Strings.CS.startsWith(uri, "/")) {
            uri = "/" + uri;
        }
        return uri;
    }

    private String getHostAndPort(HttpRequest request, InetSocketAddress remoteAddress) {
        String host = request.getFirstHeader("Host");
        if (isBlank(host)) {
            host = remoteAddress.getHostName() + ":" + remoteAddress.getPort();
        }
        return host;
    }

    private String getCookieHeader(HttpRequest request) {
        List<Cookie> cookies = new ArrayList<Cookie>();
        for (org.mockserver.model.Cookie cookie : request.getCookieList()) {
            cookies.add(new DefaultCookie(cookie.getName().getValue(), cookie.getValue().getValue()));
        }
        if (cookies.size() > 0) {
            return " -H " + singleQuote(COOKIE + ": " + ClientCookieEncoder.LAX.encode(cookies));
        } else {
            return "";
        }
    }

    /**
     * Wrap a value so it is safe to embed inside a shell command as a single-quoted string.
     * Uses the standard POSIX idiom: terminate the quoted string, emit an escaped literal
     * single quote ({@code '\''}) for each embedded {@code '}, then resume the quoted string.
     * Everything else (including {@code "}, {@code \}, {@code `} and {@code $}) is literal
     * inside single quotes, so no further escaping is required.
     */
    private static String singleQuote(String value) {
        if (value == null) {
            value = "";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
