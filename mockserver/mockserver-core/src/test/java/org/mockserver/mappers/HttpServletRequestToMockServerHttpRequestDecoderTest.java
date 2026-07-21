package org.mockserver.mappers;

import com.google.common.collect.Lists;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.*;
import org.springframework.mock.web.MockHttpServletRequest;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.NottableString.string;

import static org.hamcrest.core.Is.is;
/**
 * @author jamesdbloom
 */
@SuppressWarnings("unchecked")
public class HttpServletRequestToMockServerHttpRequestDecoderTest {

    @Test
    public void shouldMapHttpServletRequestToHttpRequest() {
        // given
        MockHttpServletRequest httpServletRequest = new MockHttpServletRequest("GET", "/requestURI");
        httpServletRequest.setContextPath(null);
        httpServletRequest.setQueryString("queryStringParameterNameOne=queryStringParameterValueOne_One&queryStringParameterNameOne=queryStringParameterValueOne_Two&queryStringParameterNameTwo=queryStringParameterValueTwo_One");
        httpServletRequest.addHeader("headerName1", "headerValue1_1");
        httpServletRequest.addHeader("headerName1", "headerValue1_2");
        httpServletRequest.addHeader("headerName2", "headerValue2");
        httpServletRequest.addHeader("Content-Type", "multipart/form-data");
        httpServletRequest.setCookies(new jakarta.servlet.http.Cookie("cookieName1", "cookieValue1"), new jakarta.servlet.http.Cookie("cookieName2", "cookieValue2"));
        httpServletRequest.setContent("bodyParameterNameOne=bodyParameterValueOne_One&bodyParameterNameOne=bodyParameterValueOne_Two&bodyParameterNameTwo=bodyParameterValueTwo_One".getBytes(UTF_8));
        httpServletRequest.setLocalAddr("local_addr");
        httpServletRequest.setLocalPort(1234);
        httpServletRequest.setRemoteHost("remote_addr");

        // when
        HttpRequest httpRequest = new HttpServletRequestToMockServerHttpRequestDecoder(configuration(), new MockServerLogger()).mapHttpServletRequestToMockServerRequest(httpServletRequest);

        // then
        assertThat(httpRequest.getPath(), is(string("/requestURI")));
        assertThat(httpRequest.getBody().toString(), is(new ParameterBody(
            new Parameter("bodyParameterNameOne", "bodyParameterValueOne_One"),
            new Parameter("bodyParameterNameOne", "bodyParameterValueOne_Two"),
            new Parameter("bodyParameterNameTwo", "bodyParameterValueTwo_One")
        ).toString()));
        assertThat(new HashSet<>(httpRequest.getQueryStringParameterList()), is(new HashSet<>(Arrays.asList(
            new Parameter("queryStringParameterNameOne", "queryStringParameterValueOne_One", "queryStringParameterValueOne_Two"),
            new Parameter("queryStringParameterNameTwo", "queryStringParameterValueTwo_One")
        ))));
        assertThat(httpRequest.getHeaderList(), is(Lists.newArrayList(
            new Header("headerName1", "headerValue1_1", "headerValue1_2"),
            new Header("headerName2", "headerValue2"),
            new Header("Content-Type", "multipart/form-data"),
            new Header("Cookie", "cookieName1=cookieValue1; cookieName2=cookieValue2")
        )));
        assertThat(httpRequest.getCookieList(), is(Lists.newArrayList(
            new Cookie("cookieName1", "cookieValue1"),
            new Cookie("cookieName2", "cookieValue2")
        )));
        assertThat(httpRequest.getLocalAddress(), equalTo("local_addr:1234"));
        assertThat(httpRequest.getRemoteAddress(), equalTo("remote_addr:80"));
        assertThat(httpRequest.getProtocol(), equalTo(Protocol.HTTP_1_1));
    }

    @Test
    public void shouldMapPathForRequestsWithAContextPath() {
        // given
        MockHttpServletRequest httpServletRequest = new MockHttpServletRequest("GET", "/requestURI");
        httpServletRequest.setContextPath("contextPath");
        httpServletRequest.setPathInfo("/pathInfo");
        httpServletRequest.setContent("".getBytes(UTF_8));

        // when
        HttpRequest httpRequest = new HttpServletRequestToMockServerHttpRequestDecoder(configuration(), new MockServerLogger()).mapHttpServletRequestToMockServerRequest(httpServletRequest);

        // then
        assertThat(httpRequest.getPath(), is(string("/pathInfo")));
    }

    @Test
    public void shouldPercentDecodePathWhenFallingBackToRequestURI() {
        // given - a ROOT-context style request where the container reports a null pathInfo, so the
        // decoder falls back to the raw, still-percent-encoded getRequestURI() (reproduces the WAR
        // deployment 404 where /ab%40c.de failed to match an expectation for /ab@c.de)
        MockHttpServletRequest httpServletRequest = new MockHttpServletRequest("GET", "/ab%40c.de");
        httpServletRequest.setContextPath("");
        httpServletRequest.setPathInfo(null);
        httpServletRequest.setRequestURI("/ab%40c.de");
        httpServletRequest.setContent("".getBytes(UTF_8));

        // when
        HttpRequest httpRequest = new HttpServletRequestToMockServerHttpRequestDecoder(configuration(), new MockServerLogger()).mapHttpServletRequestToMockServerRequest(httpServletRequest);

        // then
        assertThat(httpRequest.getPath(), is(string("/ab@c.de")));
    }

    @Test
    public void shouldPreserveLiteralPlusWhenDecodingRequestURIPath() {
        // given - a literal '+' in a path is not a space (unlike a query string), so it must survive
        MockHttpServletRequest httpServletRequest = new MockHttpServletRequest("GET", "/a+b%40c");
        httpServletRequest.setContextPath("");
        httpServletRequest.setPathInfo(null);
        httpServletRequest.setRequestURI("/a+b%40c");
        httpServletRequest.setContent("".getBytes(UTF_8));

        // when
        HttpRequest httpRequest = new HttpServletRequestToMockServerHttpRequestDecoder(configuration(), new MockServerLogger()).mapHttpServletRequestToMockServerRequest(httpServletRequest);

        // then
        assertThat(httpRequest.getPath(), is(string("/a+b@c")));
    }

    @Test(expected = RuntimeException.class)
    public void shouldHandleExceptionWhenReadingBody() throws IOException {
        // given
        HttpServletRequest httpServletRequest = mock(HttpServletRequest.class);
        when(httpServletRequest.getMethod()).thenReturn("GET");
        when(httpServletRequest.getRequestURL()).thenReturn(new StringBuffer("requestURI"));
        when(httpServletRequest.getQueryString()).thenReturn("parameterName=parameterValue");
        Enumeration<String> enumeration = mock(Enumeration.class);
        when(enumeration.hasMoreElements()).thenReturn(false);
        when(httpServletRequest.getHeaderNames()).thenReturn(enumeration);
        when(httpServletRequest.getInputStream()).thenThrow(new IOException("TEST EXCEPTION"));

        // when
        new HttpServletRequestToMockServerHttpRequestDecoder(configuration(), new MockServerLogger()).mapHttpServletRequestToMockServerRequest(httpServletRequest);
    }
}
