package org.mockserver.mappers;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.mockserver.codec.BodyServletDecoderEncoder;
import org.mockserver.codec.ExpandedParameterDecoder;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.NottableString.strings;

/**
 * @author jamesdbloom
 */
public class HttpServletRequestToMockServerHttpRequestDecoder {

    private final BodyServletDecoderEncoder bodyDecoderEncoder;
    private final ExpandedParameterDecoder formParameterParser;

    public HttpServletRequestToMockServerHttpRequestDecoder(Configuration configuration, MockServerLogger mockServerLogger) {
        bodyDecoderEncoder = new BodyServletDecoderEncoder(mockServerLogger);
        formParameterParser = new ExpandedParameterDecoder(configuration, mockServerLogger);
    }

    public HttpRequest mapHttpServletRequestToMockServerRequest(HttpServletRequest httpServletRequest) {
        HttpRequest request = new HttpRequest();
        setMethod(request, httpServletRequest);

        setPath(request, httpServletRequest);
        setQueryString(request, httpServletRequest);

        setBody(request, httpServletRequest);
        setHeaders(request, httpServletRequest);
        setCookies(request, httpServletRequest);
        setSocketAddress(request, httpServletRequest);

        request.withKeepAlive(isKeepAlive(httpServletRequest));
        request.withSecure(httpServletRequest.isSecure());
        request.withProtocol(Protocol.HTTP_1_1);
        request.withLocalAddress(httpServletRequest.getLocalAddr() + ":" + httpServletRequest.getLocalPort());
        request.withRemoteAddress(httpServletRequest.getRemoteHost() + ":" + httpServletRequest.getRemotePort());
        return request;
    }

    private void setMethod(HttpRequest httpRequest, HttpServletRequest httpServletRequest) {
        httpRequest.withMethod(httpServletRequest.getMethod());
    }

    private void setPath(HttpRequest httpRequest, HttpServletRequest httpServletRequest) {
        httpRequest.withPath(httpServletRequest.getPathInfo() != null && httpServletRequest.getContextPath() != null ? httpServletRequest.getPathInfo() : httpServletRequest.getRequestURI());
    }

    private void setQueryString(HttpRequest httpRequest, HttpServletRequest httpServletRequest) {
        if (isNotBlank(httpServletRequest.getQueryString())) {
            httpRequest.withQueryStringParameters(formParameterParser.retrieveQueryParameters(httpServletRequest.getQueryString(), false));
        }
    }

    private void setBody(HttpRequest httpRequest, HttpServletRequest httpServletRequest) {
        httpRequest.withBody(bodyDecoderEncoder.servletRequestToBody(httpServletRequest));
    }

    private void setHeaders(HttpRequest httpRequest, HttpServletRequest httpServletRequest) {
        Enumeration<String> headerNames = httpServletRequest.getHeaderNames();
        if (headerNames.hasMoreElements()) {
            Headers headers = new Headers();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                List<String> mappedHeaderValues = new ArrayList<>();
                Enumeration<String> headerValues = httpServletRequest.getHeaders(headerName);
                while (headerValues.hasMoreElements()) {
                    mappedHeaderValues.add(headerValues.nextElement());
                }
                // literal name and values — an actual incoming request, not a matcher, so a header
                // named or valued "!foo" is recorded verbatim rather than read as a negation
                headers.withEntry(string(headerName, false), strings(mappedHeaderValues, false));
            }
            httpRequest.withHeaders(headers);
        }
    }

    private void setCookies(HttpRequest httpRequest, HttpServletRequest httpServletRequest) {
        jakarta.servlet.http.Cookie[] httpServletRequestCookies = httpServletRequest.getCookies();
        if (httpServletRequestCookies != null && httpServletRequestCookies.length > 0) {
            Cookies cookies = new Cookies();
            for (jakarta.servlet.http.Cookie cookie : httpServletRequestCookies) {
                // literal name and value — see the header note above
                cookies.withEntry(new Cookie(string(cookie.getName(), false), string(stripSurroundingQuotes(cookie.getValue()), false)));
            }
            httpRequest.withCookies(cookies);
        }
    }

    /**
     * Servlet 6 (Tomcat 11+) preserves RFC 6265 surrounding double quotes
     * on cookie values (e.g. {@code "value"} instead of {@code value}).
     * Strip them so MockServer stores the logical value.
     */
    private static String stripSurroundingQuotes(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private void setSocketAddress(HttpRequest httpRequest, HttpServletRequest httpServletRequest) {
        httpRequest.withSocketAddress(httpServletRequest.isSecure(), httpServletRequest.getHeader("host"), httpServletRequest.getLocalPort());
    }

    public boolean isKeepAlive(HttpServletRequest httpServletRequest) {
        CharSequence connection = httpServletRequest.getHeader(HttpHeaderNames.CONNECTION.toString());
        if (HttpHeaderValues.CLOSE.contentEqualsIgnoreCase(connection)) {
            return false;
        }

        if (httpServletRequest.getProtocol().equals("HTTP/1.1")) {
            return !HttpHeaderValues.CLOSE.contentEqualsIgnoreCase(connection);
        } else {
            return HttpHeaderValues.KEEP_ALIVE.contentEqualsIgnoreCase(connection);
        }
    }
}
