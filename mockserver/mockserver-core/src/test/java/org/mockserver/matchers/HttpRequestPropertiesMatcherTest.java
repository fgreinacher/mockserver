package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.model.*;

import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.jar.Attributes.Name.CONTENT_TYPE;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.Cookie.schemaCookie;
import static org.mockserver.model.Header.schemaHeader;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.JsonPathBody.jsonPath;
import static org.mockserver.model.JsonSchemaBody.jsonSchema;
import static org.mockserver.model.KeyMatchStyle.MATCHING_KEY;
import static org.mockserver.model.KeyMatchStyle.SUB_SET;
import static org.mockserver.model.Not.not;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.Parameter.schemaParam;
import static org.mockserver.model.ParameterBody.params;
import static org.mockserver.model.ParameterStyle.*;
import static org.mockserver.model.RegexBody.regex;
import static org.mockserver.model.StringBody.exact;
import static org.mockserver.model.XPathBody.xpath;
import static org.mockserver.model.XmlBody.xml;
import static org.mockserver.model.XmlSchemaBody.xmlSchema;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class HttpRequestPropertiesMatcherTest {

    /**
     * Test Pattern For Fields:
     * - Nottable Matcher
     * - KeepAlive
     * - SSL
     * - Method
     * - Path
     * - PathParameters
     * - QueryStringParameters
     * - Headers
     * - Cookies
     * - Body:
     * - BinaryBody
     * - JsonBody
     * - JsonPathBody
     * - JsonSchemaBody
     * - ParameterBody
     * - RegexBody
     * - StringBody
     * - XPathBody
     * - XMLBody
     * - XMLSchemaBody
     * Then:
     * - simple
     * - regex
     * - regex control plane
     * - schema
     * - schema control plane
     */

    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(HttpRequestPropertiesMatcherTest.class);

    HttpRequestPropertiesMatcher update(RequestDefinition requestDefinition) {
        HttpRequestPropertiesMatcher httpRequestPropertiesMatcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        httpRequestPropertiesMatcher.update(new Expectation(requestDefinition));
        return httpRequestPropertiesMatcher;
    }

    HttpRequestPropertiesMatcher updateForControlPlane(RequestDefinition requestDefinition) {
        HttpRequestPropertiesMatcher httpRequestPropertiesMatcher = new HttpRequestPropertiesMatcher(configuration, mockServerLogger);
        httpRequestPropertiesMatcher.update(requestDefinition);
        return httpRequestPropertiesMatcher;
    }

    // NOTTED MATCHER

    @Test
    public void shouldMatchWithNottedMatcher() {
        // requests match - matcher HttpRequest notted
        assertThat(update(not(new HttpRequest().withMethod("HEAD"))).matches(null, new HttpRequest().withMethod("HEAD")), is(false));

        // requests match - matched HttpRequest notted
        assertThat(update(new HttpRequest().withMethod("HEAD")).matches(null, not(new HttpRequest().withMethod("HEAD"))), is(false));

        // requests match - matcher HttpRequest notted & HttpRequestMatch notted
        assertThat(notMatcher(update(not(new HttpRequest().withMethod("HEAD")))).matches(null, new HttpRequest().withMethod("HEAD")), is(true));

        // requests match - matched HttpRequest notted & HttpRequestMatch notted
        assertThat(notMatcher(update(new HttpRequest().withMethod("HEAD"))).matches(null, not(new HttpRequest().withMethod("HEAD"))), is(true));

        // requests match - matcher HttpRequest notted & matched HttpRequest notted & HttpRequestMatch notted
        assertThat(notMatcher(update(not(new HttpRequest().withMethod("HEAD")))).matches(null, not(new HttpRequest().withMethod("HEAD"))), is(false));
    }

    @Test
    public void shouldNotMatchWithNottedMatcher() {
        // requests don't match - matcher HttpRequest notted
        assertThat(update(not(new HttpRequest().withMethod("HEAD"))).matches(null, new HttpRequest().withMethod("OPTIONS")), is(true));

        // requests don't match - matched HttpRequest notted
        assertThat(update(new HttpRequest().withMethod("HEAD")).matches(null, not(new HttpRequest().withMethod("OPTIONS"))), is(true));

        // requests don't match - matcher HttpRequest notted & HttpRequestMatch notted
        assertThat(notMatcher(update(not(new HttpRequest().withMethod("HEAD")))).matches(null, new HttpRequest().withMethod("OPTIONS")), is(false));

        // requests don't match - matched HttpRequest notted & HttpRequestMatch notted
        assertThat(notMatcher(update(new HttpRequest().withMethod("HEAD"))).matches(null, not(new HttpRequest().withMethod("OPTIONS"))), is(false));

        // requests don't match - matcher HttpRequest notted & matched HttpRequest notted & HttpRequestMatch notted
        assertThat(notMatcher(update(not(new HttpRequest().withMethod("HEAD")))).matches(null, not(new HttpRequest().withMethod("OPTIONS"))), is(true));
    }

    // KEEP ALIVE

    @Test
    public void shouldMatchKeepAlive() {
        assertThat(update(new HttpRequest().withKeepAlive(true)).matches(null, new HttpRequest().withKeepAlive(true)), is(true));
        assertThat(update(new HttpRequest().withKeepAlive(false)).matches(null, new HttpRequest().withKeepAlive(false)), is(true));
        assertThat(update(new HttpRequest().withKeepAlive(null)).matches(null, new HttpRequest().withKeepAlive(null)), is(true));
        assertThat(update(new HttpRequest().withKeepAlive(null)).matches(null, new HttpRequest().withKeepAlive(false)), is(true));
        assertThat(update(new HttpRequest().withKeepAlive(null)).matches(null, new HttpRequest()), is(true));
        assertThat(update(new HttpRequest()).matches(null, new HttpRequest().withKeepAlive(null)), is(true));
    }

    @Test
    public void shouldNotMatchKeepAlive() {
        assertThat(update(new HttpRequest().withKeepAlive(true)).matches(null, new HttpRequest().withKeepAlive(false)), is(false));
        assertThat(update(new HttpRequest().withKeepAlive(false)).matches(null, new HttpRequest().withKeepAlive(true)), is(false));
        assertThat(update(new HttpRequest().withKeepAlive(true)).matches(null, new HttpRequest().withKeepAlive(null)), is(false));
        assertThat(update(new HttpRequest().withKeepAlive(false)).matches(null, new HttpRequest().withKeepAlive(null)), is(false));
    }

    @Test
    public void shouldMatchKeepAliveForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(true)).matches(null, new HttpRequest().withKeepAlive(true)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(false)).matches(null, new HttpRequest().withKeepAlive(false)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(null)).matches(null, new HttpRequest().withKeepAlive(null)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(null)).matches(null, new HttpRequest().withKeepAlive(false)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(null)).matches(null, new HttpRequest()), is(true));
        assertThat(updateForControlPlane(new HttpRequest()).matches(null, new HttpRequest().withKeepAlive(null)), is(true));
    }

    @Test
    public void shouldNotMatchKeepAliveForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(true)).matches(null, new HttpRequest().withKeepAlive(false)), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(false)).matches(null, new HttpRequest().withKeepAlive(true)), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(true)).matches(null, new HttpRequest().withKeepAlive(null)), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withKeepAlive(false)).matches(null, new HttpRequest().withKeepAlive(null)), is(false));
    }

    // SSL

    @Test
    public void shouldMatchSsl() {
        assertThat(update(new HttpRequest().withSecure(true)).matches(null, new HttpRequest().withSecure(true)), is(true));
        assertThat(update(new HttpRequest().withSecure(false)).matches(null, new HttpRequest().withSecure(false)), is(true));
        assertThat(update(new HttpRequest().withSecure(null)).matches(null, new HttpRequest().withSecure(null)), is(true));
        assertThat(update(new HttpRequest().withSecure(null)).matches(null, new HttpRequest().withSecure(false)), is(true));
        assertThat(update(new HttpRequest().withSecure(null)).matches(null, new HttpRequest()), is(true));
        assertThat(update(new HttpRequest()).matches(null, new HttpRequest().withSecure(null)), is(true));
    }

    @Test
    public void shouldNotMatchSsl() {
        assertThat(update(new HttpRequest().withSecure(true)).matches(null, new HttpRequest().withSecure(false)), is(false));
        assertThat(update(new HttpRequest().withSecure(false)).matches(null, new HttpRequest().withSecure(true)), is(false));
        assertThat(update(new HttpRequest().withSecure(true)).matches(null, new HttpRequest().withSecure(null)), is(false));
        assertThat(update(new HttpRequest().withSecure(false)).matches(null, new HttpRequest().withSecure(null)), is(false));
    }

    @Test
    public void shouldMatchSslForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withSecure(true)).matches(null, new HttpRequest().withSecure(true)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withSecure(false)).matches(null, new HttpRequest().withSecure(false)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withSecure(null)).matches(null, new HttpRequest().withSecure(null)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withSecure(null)).matches(null, new HttpRequest().withSecure(false)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withSecure(null)).matches(null, new HttpRequest()), is(true));
        assertThat(updateForControlPlane(new HttpRequest()).matches(null, new HttpRequest().withSecure(null)), is(true));
    }

    @Test
    public void shouldNotMatchSslForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withSecure(true)).matches(null, new HttpRequest().withSecure(false)), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withSecure(false)).matches(null, new HttpRequest().withSecure(true)), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withSecure(true)).matches(null, new HttpRequest().withSecure(null)), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withSecure(false)).matches(null, new HttpRequest().withSecure(null)), is(false));
    }

    // PROTOCOL

    @Test
    public void shouldMatchProtocol() {
        // exact protocol matches
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_1_1)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_1_1)), is(true));
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_2)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_2)), is(true));
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_3)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_3)), is(true));
        // null expectation protocol matches any request protocol (regression: protocol optional)
        assertThat(update(new HttpRequest().withProtocol(null)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_1_1)), is(true));
        assertThat(update(new HttpRequest().withProtocol(null)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_2)), is(true));
        assertThat(update(new HttpRequest().withProtocol(null)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_3)), is(true));
        assertThat(update(new HttpRequest()).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_2)), is(true));
        assertThat(update(new HttpRequest()).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_3)), is(true));
        // both null still matches
        assertThat(update(new HttpRequest().withProtocol(null)).matches(null, new HttpRequest().withProtocol(null)), is(true));
    }

    @Test
    public void shouldNotMatchProtocol() {
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_2)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_1_1)), is(false));
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_1_1)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_2)), is(false));
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_3)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_2)), is(false));
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_2)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_3)), is(false));
        // expectation specifies protocol, request has none -> no match
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_2)).matches(null, new HttpRequest().withProtocol(null)), is(false));
        assertThat(update(new HttpRequest().withProtocol(Protocol.HTTP_3)).matches(null, new HttpRequest().withProtocol(null)), is(false));
    }

    @Test
    public void shouldMatchProtocolForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withProtocol(Protocol.HTTP_2)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_2)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withProtocol(Protocol.HTTP_3)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_3)), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withProtocol(null)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_3)), is(true));
        assertThat(updateForControlPlane(new HttpRequest()).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_2)), is(true));
    }

    @Test
    public void shouldNotMatchProtocolForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withProtocol(Protocol.HTTP_2)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_3)), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withProtocol(Protocol.HTTP_3)).matches(null, new HttpRequest().withProtocol(Protocol.HTTP_1_1)), is(false));
    }

    // METHOD

    @Test
    public void shouldMatchMethod() {
        assertThat(update(new HttpRequest().withMethod(
            "HEAD"
        )).matches(null, new HttpRequest().withMethod(
            "HEAD"
        )), is(true));
    }

    @Test
    public void shouldNotMatchMethod() {
        assertThat(update(new HttpRequest().withMethod(
            "HEAD"
        )).matches(null, new HttpRequest().withMethod(
            "OPTIONS"
        )), is(false));
    }

    @Test
    public void shouldMatchMethodWithRegex() {
        assertThat(update(new HttpRequest().withMethod(
            "P[A-Z]{2}"
        )).matches(null, new HttpRequest().withMethod(
            "PUT"
        )), is(true));
    }

    @Test
    public void shouldNotMatchMethodWithRegex() {
        assertThat(update(new HttpRequest().withMethod(
            "P[A-Z]{2}"
        )).matches(null, new HttpRequest().withMethod(
            "POST"
        )), is(false));
    }

    @Test
    public void shouldMatchMethodWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withMethod(
            "PUT"
        )).matches(null, new HttpRequest().withMethod(
            "P[A-Z]{2}"
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withMethod(
            "P[A-Z]{2}"
        )).matches(null, new HttpRequest().withMethod(
            "P[A-Z]{2}"
        )), is(true));
        assertThat(update(new HttpRequest().withMethod(
            "PUT"
        )).matches(null, new HttpRequest().withMethod(
            "P[A-Z]{2}"
        )), is(false));
    }

    @Test
    public void shouldNotMatchMethodWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withMethod(
            "POST"
        )).matches(null, new HttpRequest().withMethod(
            "P[A-Z]{2}"
        )), is(false));
    }

    @Test
    public void shouldMatchMethodWithSchema() {
        assertThat(update(new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )).matches(null, new HttpRequest().withMethod(
            "POST"
        )), is(true));
        assertThat(update(new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )).matches(null, new HttpRequest().withMethod(
            "PUT"
        )), is(true));
    }

    @Test
    public void shouldNotMatchMethodWithSchema() {
        assertThat(update(new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )).matches(null, new HttpRequest().withMethod(
            "GET"
        )), is(false));
        assertThat(update(new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )).matches(null, new HttpRequest().withMethod(
            "HEAD"
        )), is(false));
    }

    @Test
    public void shouldMatchMethodWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withMethod(
            "POST"
        )).matches(null, new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withMethod(
            "PUT"
        )).matches(null, new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )).matches(null, new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )), is(true));
        assertThat(update(new HttpRequest().withMethod(
            "POST"
        )).matches(null, new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )), is(false));
        assertThat(update(new HttpRequest().withMethod(
            "PUT"
        )).matches(null, new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )), is(false));
        assertThat(update(new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )).matches(null, new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )), is(false));
    }

    @Test
    public void shouldNotMatchMethodWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withMethod(
            "GET"
        )).matches(null, new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withMethod(
            "HEAD"
        )).matches(null, new HttpRequest().withMethodSchema(
            "{ \"type\": \"string\", \"pattern\": \"^P.{2,3}$\" }"
        )), is(false));
    }

    // PATH

    @Test
    public void shouldMatchPath() {
        assertThat(update(new HttpRequest().withPath(
            "somePath"
        )).matches(null, new HttpRequest().withPath(
            "somePath"
        )), is(true));
    }

    @Test
    public void shouldNotMatchPath() {
        assertThat(update(new HttpRequest().withPath(
            "somePath"
        )).matches(null, new HttpRequest().withPath(
            "someOtherPath"
        )), is(false));
    }

    @Test
    public void shouldMatchEncodedPath() {
        assertThat(update(new HttpRequest().withPath(
            "/dWM%2FdWM+ZA=="
        )).matches(null, new HttpRequest().withPath(
            "/dWM%2FdWM+ZA=="
        )), is(true));
    }

    @Test
    public void shouldNotMatchEncodedPath() {
        assertThat(update(new HttpRequest().withPath(
            "/dWM%2FdWM+ZA=="
        )).matches(null, new HttpRequest().withPath(
            "/dWM/dWM+ZA=="
        )), is(false));
        assertThat(update(new HttpRequest().withPath(
            "/dWM/dWM+ZA=="
        )).matches(null, new HttpRequest().withPath(
            "/dWM%2FdWM+ZA=="
        )), is(false));
    }

    @Test
    public void shouldMatchPathWithRegex() {
        assertThat(update(new HttpRequest().withPath(
            "someP[a-z]{3}"
        )).matches(null, new HttpRequest().withPath(
            "somePath"
        )), is(true));
    }

    @Test
    public void shouldNotMatchPathWithRegex() {
        assertThat(update(new HttpRequest().withPath(
            "someP[a-z]{2}"
        )).matches(null, new HttpRequest().withPath(
            "somePath"
        )), is(false));
    }

    @Test
    public void shouldMatchPathWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withPath(
            "somePath"
        )).matches(null, new HttpRequest().withPath(
            "someP[a-z]{3}"
        )), is(true));
        assertThat(update(new HttpRequest().withPath(
            "somePath"
        )).matches(null, new HttpRequest().withPath(
            "someP[a-z]{3}"
        )), is(false));
    }

    @Test
    public void shouldNotMatchPathWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withPath(
            "somePath"
        )).matches(null, new HttpRequest().withPath(
            "someP[a-z]{2}"
        )), is(false));
    }

    @Test
    public void shouldMatchPathWithSchema() {
        assertThat(update(new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )).matches(null, new HttpRequest().withPath(
            "somePath"
        )), is(true));
    }

    @Test
    public void shouldNotMatchPathWithSchema() {
        assertThat(update(new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )).matches(null, new HttpRequest().withPath(
            "someOtherPath"
        )), is(false));
    }

    @Test
    public void shouldMatchPathWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withPath(
            "somePath"
        )).matches(null, new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )).matches(null, new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )), is(true));
        assertThat(update(new HttpRequest().withPath(
            "somePath"
        )).matches(null, new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )), is(false));
        assertThat(update(new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )).matches(null, new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )), is(false));
    }

    @Test
    public void shouldNotMatchPathWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withPath(
            "someOtherPath"
        )).matches(null, new HttpRequest().withPathSchema(
            "{ \"type\": \"string\", \"pattern\": \"^somePa.{2}$\" }"
        )), is(false));
    }

    // PATH PARAMETERS

    @Test
    public void shouldMatchPathParameterInPath() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ), is(true));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValueOne", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someValueTwo"
            )
        ), is(true));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueTwo")
            )
        ), is(true));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValueOne", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someValueTwo"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueOne", "someValueTwo")
            )
        ), is(true));
    }

    @Test
    public void shouldMatchNottedPathParameterInPath() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "!someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ), is(true));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "!someValueOne", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValueOne,someValueTwo"
            )
        ), is(true));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValueOne", "!someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someOtherValueTwo"
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchPathParameterKeyInPath() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueTwo")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchPathParameterValueInPath() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueTwo")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchNottedPathParameterInPath() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "!someValue")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "!someValueOne", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someValueTwo"
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValueOne", "!someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someValueTwo"
            )
        ), is(false));
    }

    @Test
    public void shouldMatchPathParameterInParameterObject() {
        assertThat(update(new HttpRequest().withPathParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withPathParameter(
            new Parameter("someKey", "someValue")
        )), is(true));
        assertThat(update(new HttpRequest().withPathParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withPathParameter(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )), is(true));
        assertThat(update(new HttpRequest().withPathParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withPathParameters(
            new Parameter("someKey", "someValue"),
            new Parameter("someKeyTwo", "someValueTwo")
        )), is(true));
        assertThat(update(new HttpRequest().withPathParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withPathParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo"),
            new Parameter("someKeyTwo", "someValueOne", "someValueTwo")
        )), is(true));
    }

    @Test
    public void shouldMatchPathParameterInPathForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(true));
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someValueTwo"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValueOne", "someValueTwo")
            )
        ), is(true));
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(false));
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someValueTwo"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueOne", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValueOne", "someValueTwo")
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someValueTwo"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValueOne", "someValueTwo")
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(false));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/someValueOne,someValueTwo"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueOne", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValueOne", "someValueTwo")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchPathParameterKeyInPathForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(false));
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(false));
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchPathParameterValueInPathForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(false));
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
            .withPathParameters(
                new Parameter("someKeyTwo", "someValueTwo")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someKey", "someValue")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchPathParameterWithRegexInPath() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchPathParameterValueWithRegexInPath() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ), is(false));
    }

    @Test
    public void shouldMatchPathParameterWithRegexInParameterObject() {
        assertThat(update(new HttpRequest().withPathParameters(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withPathParameter(
            new Parameter("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldMatchPathParameterWithRegexInPathForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchPathParameterValueWithRegexInPathForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
            )
        ), is(false));
    }

    @Test
    public void shouldMatchPathParameterWithSchemaInPathObject() {
        assertThat(update(new HttpRequest().withPathParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withPathParameters(
            new Parameter("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldMatchPathParameterWithSchemaInParameterObject() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ), is(true));
    }

    @Test
    public void shouldNotMatchPathParameterWithSchemaInParameterObject() {
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ), is(false));
    }

    @Test
    public void shouldMatchPathParameterWithSchemaInPathObjectForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
            )
        ), is(true));
        assertThat(update(new HttpRequest()
            .withPath(
                "/some/path/someValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchPathParameterWithSchemaInPathObjectForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withPath(
                "/some/path/someOtherValue"
            )
        ).matches(null, new HttpRequest()
            .withPath(
                "/some/path/{someKey}"
            )
            .withPathParameters(
                schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
            )
        ), is(false));
    }

    // QUERY STRING PARAMETERS

    @Test
    public void shouldMatchQueryStringParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someValue")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue"),
            new Parameter("someKeyTwo", "someValueTwo")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo"),
            new Parameter("someKeyTwo", "someValueOne", "someValueTwo")
        )), is(true));
    }

    @Test
    public void shouldMatchQueryStringFormStyleParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1,1,1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1,1,1")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM),
                new Parameter("someKeyTwo", "a")
                    .withStyle(FORM)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "1,1,1"),
            new Parameter("someKeyTwo", "a,a,a")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1,1,1", "1,1,1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1,2,3", "1,2,3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM)
            ).withKeyMatchStyle(SUB_SET)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1,2,3", "1,2,3")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM)
            )
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1,2,3", "1,2,3")
        )), is(true));
    }

    @Test
    public void shouldMatchQueryStringFormExpandedStyleParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM_EXPLODED),
                new Parameter("someKeyTwo", "a")
                    .withStyle(FORM_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "1", "1", "1"),
            new Parameter("someKeyTwo", "a", "a", "a")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM_EXPLODED)
            ).withKeyMatchStyle(SUB_SET)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(FORM_EXPLODED)
            )
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(true));
    }

    @Test
    public void shouldMatchQueryStringSpaceDelimitedStyleParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1%201%201")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1+1+1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1 1 1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1%201%201")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1+1+1")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1 1 1")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED),
                new Parameter("someKeyTwo", "a")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "1+1+1"),
            new Parameter("someKeyTwo", "a+a+a")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1+1+1", "1+1+1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1+2+3", "1+2+3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(SUB_SET)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1%202%203", "1%202%203")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(SUB_SET)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1+2+3", "1+2+3")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ).withKeyMatchStyle(SUB_SET)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1 2 3", "1 2 3")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            )
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1%202%203", "1%202%203")
        )), is(true));
    }

    @Test
    public void shouldMatchQueryStringSpaceDelimitedExpandedStyleParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED_EXPLODED),
                new Parameter("someKeyTwo", "a")
                    .withStyle(SPACE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "1", "1", "1"),
            new Parameter("someKeyTwo", "a", "a", "a")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(SUB_SET)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED_EXPLODED)
            )
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(true));
    }

    @Test
    public void shouldMatchQueryStringPipeDelimitedStyleParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1|1|1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1|1|1")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED),
                new Parameter("someKeyTwo", "a")
                    .withStyle(PIPE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "1|1|1"),
            new Parameter("someKeyTwo", "a|a|a")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1|1|1", "1|1|1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1|2|3", "1|2|3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED)
            ).withKeyMatchStyle(SUB_SET)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1|2|3", "1|2|3")
        )), is(true));
    }

    @Test
    public void shouldMatchQueryStringPipeDelimitedExpandedStyleParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED_EXPLODED),
                new Parameter("someKeyTwo", "a")
                    .withStyle(PIPE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "1", "1", "1"),
            new Parameter("someKeyTwo", "a", "a", "a")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "1", "1", "1", "1", "1")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
            ).withKeyMatchStyle(MATCHING_KEY)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED_EXPLODED)
            ).withKeyMatchStyle(SUB_SET)
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameters(
                new Parameter("someKey", "1")
                    .withStyle(PIPE_DELIMITED_EXPLODED)
            )
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        )), is(true));
    }

    @Test
    public void shouldMatchEmptyQueryStringParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldMatchNottedQueryStringParameter() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "!someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someOtherValue")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "!someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someOtherValueOne", "someOtherValueTwo")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("!someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someValue")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someOtherValue"),
            new Parameter("notSomeKey", "someOtherValueOne", "someOtherValueTwo"),
            new Parameter("someOtherKey", "someValue")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("!someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someOtherValue"),
            new Parameter("someKey", "someOtherValueOne", "someOtherValueTwo"),
            new Parameter("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKey() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someOtherKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someValue"),
            new Parameter("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterValue() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someOtherValue")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someOtherValueOne", "someValueTwo")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someValueOne", "someOtherValueTwo")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someOtherValue"),
            new Parameter("someKeyTwo", "someValueTwo")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someOtherValueOne", "someValueTwo"),
            new Parameter("someKeyTwo", "someValueTwoOne", "someValueTwoTwo")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValueOne", "someOtherValueTwo"),
            new Parameter("someKeyOther", "someValueTwoOne", "someValueTwoTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyAndValue() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someOtherKey", "someOtherValue")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someOtherValue"),
            new Parameter("someOtherKeyTwo", "someOtherValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedQueryStringParameterKey() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("!someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("!someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue"),
            new Parameter("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedQueryStringParameterValue() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "!someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "!someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue"),
            new Parameter("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedQueryStringParameterKeyAndValue() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someOtherValue"),
            new Parameter("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchEmptyQueryStringParameterForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
        )), is(false));
    }

    @Test
    public void shouldMatchQueryStringParameterWithRegex() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyWithRegex() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterValueWithRegex() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyAndValueWithRegex() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someOtherKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldMatchQueryStringParameterWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterValueWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someOtherValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyAndValueWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someOtherValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldMatchQueryStringParameterWithSchema() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyWithSchema() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterValueWithSchema() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyAndValueWithSchema() {
        assertThat(update(new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            new Parameter("someOtherKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldMatchQueryStringParameterWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(true));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
        assertThat(update(new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterValueWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someKey", "someOtherValue")
        )).matches(null, new HttpRequest().withQueryStringParameters(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchQueryStringParameterKeyAndValueWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withQueryStringParameters(
            new Parameter("someOtherKey", "someOtherValue")
        )).matches(null, new HttpRequest().withQueryStringParameter(
            schemaParam("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    // HEADERS

    @Test
    public void shouldMatchHeader() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someValue")
        )), is(true));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someValueOne", "someValueTwo")
        )), is(true));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someValue"),
            new Header("someKeyTwo", "someValueTwo")
        )), is(true));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someValueOne", "someValueTwo"),
            new Header("someKeyTwo", "someValueOne", "someValueTwo")
        )), is(true));
    }

    @Test
    public void shouldMatchEmptyHeader() {
        assertThat(update(new HttpRequest().withHeaders(
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldMatchNottedHeader() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "!someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someOtherValue")
        )), is(true));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "!someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someOtherValueOne", "someOtherValueTwo")
        )), is(true));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("!someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someOtherKey", "someValue")
        )), is(true));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someOtherKey", "someOtherValue"),
            new Header("notSomeKey", "someOtherValueOne", "someOtherValueTwo"),
            new Header("someOtherKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldNotMatchHeaderKey() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someOtherKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someOtherKey", "someValue"),
            new Header("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderValue() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someOtherValue")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someOtherValueOne", "someValueTwo")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someValueOne", "someOtherValueTwo")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someOtherValue"),
            new Header("someKeyTwo", "someValueTwo")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someOtherValueOne", "someValueTwo"),
            new Header("someKeyTwo", "someValueTwoOne", "someValueTwoTwo")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValueOne", "someValueTwo")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someValueOne", "someOtherValueTwo"),
            new Header("someKeyOther", "someValueTwoOne", "someValueTwoTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderKeyAndValue() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someOtherKey", "someOtherValue")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someOtherKey", "someOtherValue"),
            new Header("someOtherKeyTwo", "someOtherValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedHeaderKey() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("!someKey", "someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("!someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someValue"),
            new Header("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedHeaderValue() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "!someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "!someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someValue"),
            new Header("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedHeaderKeyAndValue() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someOtherValue"),
            new Header("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchEmptyHeaderForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
        )), is(false));
    }

    @Test
    public void shouldMatchHeaderWithRegex() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldNotMatchHeaderKeyWithRegex() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderValueWithRegex() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderKeyAndValueWithRegex() {
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someOtherKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldMatchHeaderWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(true));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderKeyWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someOtherKey", "someValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderValueWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someKey", "someOtherValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderKeyAndValueWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someOtherKey", "someOtherValue")
        )).matches(null, new HttpRequest().withHeader(
            new Header("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldMatchHeaderWithSchema() {
        assertThat(update(new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldNotMatchHeaderKeyWithSchema() {
        assertThat(update(new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderValueWithSchema() {
        assertThat(update(new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderKeyAndValueWithSchema() {
        assertThat(update(new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withHeaders(
            new Header("someOtherKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldMatchHeaderWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(true));
        assertThat(update(new HttpRequest().withHeaders(
            new Header("someKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
        assertThat(update(new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderKeyWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someOtherKey", "someValue")
        )).matches(null, new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderValueWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someKey", "someOtherValue")
        )).matches(null, new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchHeaderKeyAndValueWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeaders(
            new Header("someOtherKey", "someOtherValue")
        )).matches(null, new HttpRequest().withHeaders(
            schemaHeader("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    // COOKIES

    @Test
    public void shouldMatchCookie() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someValue")
        )), is(true));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookies(
            new Cookie("someKey", "someValue"),
            new Cookie("someKeyTwo", "someValueTwo")
        )), is(true));
    }

    @Test
    public void shouldMatchEmptyCookie() {
        assertThat(update(new HttpRequest().withCookies(
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldMatchNottedCookie() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "!someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someOtherValue")
        )), is(true));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("!someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someOtherKey", "someValue")
        )), is(true));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someOtherKey", "someOtherValue")
        )), is(true));
    }

    @Test
    public void shouldNotMatchCookieValue() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someOtherValue")
        )), is(false));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookies(
            new Cookie("someKey", "someOtherValue"),
            new Cookie("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieKey() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someOtherKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookies(
            new Cookie("someOtherKey", "someValue"),
            new Cookie("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieKeyAndValue() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someOtherKey", "someOtherValue")
        )), is(false));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookies(
            new Cookie("someOtherKey", "someOtherValue"),
            new Cookie("someOtherKeyTwo", "someOtherValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedCookieKey() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("!someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("!someKey", "someValue")
        )).matches(null, new HttpRequest().withCookies(
            new Cookie("someKey", "someValue"),
            new Cookie("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedCookieValue() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "!someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "!someValue")
        )).matches(null, new HttpRequest().withCookies(
            new Cookie("someKey", "someValue"),
            new Cookie("someKeyTwo", "someValueTwo")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedCookieKeyAndValue() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someValue")
        )), is(false));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("!someKey", "!someValue")
        )).matches(null, new HttpRequest().withCookies(
            new Cookie("someKey", "someOtherValue"),
            new Cookie("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchEmptyCookieForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookies(
        )), is(false));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookies(
        )), is(false));
    }

    @Test
    public void shouldMatchCookieWithRegex() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldNotMatchCookieKeyWithRegex() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieValueWithRegex() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieKeyAndValueWithRegex() {
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someOtherKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldMatchCookieWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(true));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieKeyWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someOtherKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieValueWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someKey", "someOtherValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieKeyAndValueWithRegexForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someOtherKey", "someOtherValue")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someK[a-z]{2}", "someV[a-z]{4}")
        )), is(false));
    }

    @Test
    public void shouldMatchCookieWithSchema() {
        assertThat(update(new HttpRequest().withCookies(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someValue")
        )), is(true));
    }

    @Test
    public void shouldNotMatchCookieKeyWithSchema() {
        assertThat(update(new HttpRequest().withCookies(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someOtherKey", "someValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieValueWithSchema() {
        assertThat(update(new HttpRequest().withCookies(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieKeyAndValueWithSchema() {
        assertThat(update(new HttpRequest().withCookies(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withCookie(
            new Cookie("someOtherKey", "someOtherValue")
        )), is(false));
    }

    @Test
    public void shouldMatchCookieWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withCookie(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(true));
        assertThat(update(new HttpRequest().withCookies(
            new Cookie("someKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
        assertThat(update(new HttpRequest().withCookies(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )).matches(null, new HttpRequest().withCookie(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieKeyWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someOtherKey", "someValue")
        )).matches(null, new HttpRequest().withCookie(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieValueWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someKey", "someOtherValue")
        )).matches(null, new HttpRequest().withCookie(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchCookieKeyAndValueWithSchemaForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withCookies(
            new Cookie("someOtherKey", "someOtherValue")
        )).matches(null, new HttpRequest().withCookie(
            schemaCookie("someK[a-z]{2}", "{ \"type\": \"string\", \"pattern\": \"^someV[a-z]{4}$\" }")
        )), is(false));
    }

    // BODY

    // - FileBody (issue #2450): a FILE request body must match the request body against the EXACT file
    //   contents. Previously there was no FILE case, so the body constraint was SILENTLY IGNORED.

    @Test
    public void shouldMatchFileBodyAgainstExactTextFileContents() {
        assertThat(update(new HttpRequest().withBody(
            new FileBody("org/mockserver/mock/action/verbatim_file_body.xml", MediaType.parse("application/xml"))
        )).matches(null, new HttpRequest().withBody(
            "<tag>hello{{ request.path }}</tag>"
        )), is(true));
    }

    @Test
    public void shouldNotMatchFileBodyAgainstDifferentTextBody() {
        // proves the body constraint is no longer silently ignored - a non-matching body is rejected
        assertThat(update(new HttpRequest().withBody(
            new FileBody("org/mockserver/mock/action/verbatim_file_body.xml", MediaType.parse("application/xml"))
        )).matches(null, new HttpRequest().withBody(
            "<tag>some other body</tag>"
        )), is(false));
    }

    @Test
    public void shouldMatchFileBodyAgainstExactBinaryFileContents() {
        assertThat(update(new HttpRequest().withBody(
            new FileBody("org/mockserver/mock/action/verbatim_binary_body.png", MediaType.parse("image/png"))
        )).matches(null, new HttpRequest().withBody(
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF, (byte) 0xFE, 0x01, (byte) 0x80, 0x7F, (byte) 0xC3, 0x28}
        )), is(true));
    }

    @Test
    public void shouldNotMatchFileBodyAgainstDifferentBinaryBody() {
        assertThat(update(new HttpRequest().withBody(
            new FileBody("org/mockserver/mock/action/verbatim_binary_body.png", MediaType.parse("image/png"))
        )).matches(null, new HttpRequest().withBody(
            new byte[]{0x01, 0x02, 0x03, 0x04}
        )), is(false));
    }

    // - BinaryBody

    @Test
    public void shouldMatchBinaryBody() {
        assertThat(update(new HttpRequest().withBody(
            binary("some binary value".getBytes(UTF_8))
        )).matches(null, new HttpRequest().withBody(
            "some binary value".getBytes(UTF_8)
        )), is(true));
    }

    @Test
    public void shouldMatchNottedBinaryBody() {
        assertThat(update(new HttpRequest().withBody(
            not(binary("some binary value".getBytes(UTF_8)))
        )).matches(null, new HttpRequest().withBody(
            "some other binary value".getBytes(UTF_8)
        )), is(true));
    }

    @Test
    public void shouldMatchBinaryBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            binary("some binary value".getBytes(UTF_8))
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            "some binary value".getBytes(UTF_8)
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            binary("some binary value".getBytes(UTF_8))
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldNotMatchBinaryBody() {
        assertThat(update(new HttpRequest().withBody(
            binary("some binary value".getBytes(UTF_8))
        )).matches(null, new HttpRequest().withBody(
            "some other binary value".getBytes(UTF_8)
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedBinaryBody() {
        assertThat(update(new HttpRequest().withBody(
            not(binary("some binary value".getBytes(UTF_8)))
        )).matches(null, new HttpRequest().withBody(
            "some binary value".getBytes(UTF_8)
        )), is(false));
    }

    @Test
    public void shouldNotMatchBinaryBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            binary("some binary value".getBytes(UTF_8))
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            binary("some binary value".getBytes(UTF_8))
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldMatchBinaryBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "some binary value".getBytes(UTF_8)
        )).matches(null, new HttpRequest().withBody(
            new BinaryBodyDTO(binary("some binary value".getBytes(UTF_8))).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            "some binary value".getBytes(UTF_8)
        )).matches(null, new HttpRequest().withBody(
            new BinaryBodyDTO(binary("some binary value".getBytes(UTF_8))).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchBinaryBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "some other binary value".getBytes(UTF_8)
        )).matches(null, new HttpRequest().withBody(
            new BinaryBodyDTO(binary("some binary value".getBytes(UTF_8))).toString()
        )), is(false));
    }

    // - JsonBody

    @Test
    public void shouldMatchJsonBody() {
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"some_value\" }")
        )).matches(null, new HttpRequest().withBody(
            "" +
                "{ " +
                "   \"some_field\": \"some_value\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"some_value\" }")
        )).matches(null, new HttpRequest().withBody(
            json("" +
                "{ " +
                "   \"some_field\": \"some_value\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}")
        )), is(true));
    }

    @Test
    public void shouldMatchNottedJsonBody() {
        assertThat(update(new HttpRequest().withBody(
            not(json("{ \"some_field\": \"some_value\" }"))
        )).matches(null, new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            "{ \"some_other_field\": \"some_other_value\" }"
        )), is(true));
    }

    @Test
    public void shouldMatchJsonBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"some_value\" }")
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            "" +
                "{ " +
                "   \"some_field\": \"some_value\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"some_value\" }")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldMatchJsonArrayBody() {
        assertThat(update(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE)
        )).matches(null, new HttpRequest().withBody(
            "" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE)
        )).matches(null, new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE)
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE)
        )).matches(null, new HttpRequest().withBody(
            "" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE
        )), is(true));
    }

    @Test
    public void shouldMatchJsonBodyWithCharset() {
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"我说中国话\" }", UTF_8, MatchType.ONLY_MATCHING_FIELDS)
        )).matches(null, new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            "" +
                "{ " +
                "   \"some_field\": \"我说中国话\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}", UTF_8
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"我说中国话\" }", UTF_8, MatchType.ONLY_MATCHING_FIELDS)
        )).matches(null, new HttpRequest().withBody(
            json("" +
                "{ " +
                "   \"some_field\": \"我说中国话\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}", UTF_8)
        )), is(true));
    }

    @Test
    public void shouldNotMatchJsonBody() {
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"some_value\" }")
        )).matches(null, new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            "{ \"some_other_field\": \"some_other_value\" }"
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"some_value\" }")
        )).matches(null, new HttpRequest().withBody(
            json("{ \"some_other_field\": \"some_other_value\" }")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedJsonBody() {
        assertThat(update(new HttpRequest().withBody(
            not(json("{ \"some_field\": \"some_value\" }"))
        )).matches(null, new HttpRequest().withBody(
            "" +
                "{ " +
                "   \"some_field\": \"some_value\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}"
        )), is(false));
    }

    @Test
    public void shouldNotMatchJsonBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"some_value\" }")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"some_value\" }")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldNotMatchJsonBodyWithCharset() {
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"我说中国话\" }", UTF_8, MatchType.ONLY_MATCHING_FIELDS)
        )).matches(null, new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            "{ \"some_other_field\": \"我说中国话\" }", UTF_8
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            json("{ \"some_field\": \"我说中国话\" }", UTF_8, MatchType.ONLY_MATCHING_FIELDS)
        )).matches(null, new HttpRequest().withBody(
            json("{ \"some_other_field\": \"我说中国话\" }", UTF_8)
        )), is(false));
    }

    @Test
    public void shouldMatchJsonBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "" +
                "{ " +
                "   \"some_field\": \"some_value\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}"
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"some_value\" }", MediaType.APPLICATION_JSON)).toString()
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("" +
                "{ " +
                "   \"some_field\": \"some_value\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}", MediaType.APPLICATION_JSON)
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"some_value\" }", MediaType.APPLICATION_JSON)).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            "" +
                "{ " +
                "   \"some_field\": \"some_value\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}"
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"some_value\" }", MediaType.APPLICATION_JSON)).toString()
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            json("" +
                "{ " +
                "   \"some_field\": \"some_value\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}", MediaType.APPLICATION_JSON)
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"some_value\" }", MediaType.APPLICATION_JSON)).toString()
        )), is(false));
    }

    @Test
    public void shouldMatchJsonArrayBodyForControlPlane() {
        // matches without being serialised BodyDTO
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE)
        )).matches(null, new HttpRequest().withBody(
            "" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE)
        )).matches(null, new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE)
        )), is(true));
        // matches as serialised BodyDTO for control-plane
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE, MediaType.APPLICATION_JSON)
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE, MediaType.APPLICATION_JSON)).toString()
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE, MediaType.APPLICATION_JSON)
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE, MediaType.APPLICATION_JSON)).toString()
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE, MediaType.APPLICATION_JSON)
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE, MediaType.APPLICATION_JSON)).toString()
        )), is(false));
        // doesn't work as serialised BodyDTO for non-control-plane
        assertThat(update(new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ ]" + NEW_LINE +
                "}" + NEW_LINE, MediaType.APPLICATION_JSON)
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("" +
                "{" + NEW_LINE +
                "  \"digests\" : [ \"sha256:one\" ]" + NEW_LINE +
                "}" + NEW_LINE, MediaType.APPLICATION_JSON)).toString()
        )), is(false));
    }

    @Test
    public void shouldMatchJsonBodyWithCharsetForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            "" +
                "{ " +
                "   \"some_field\": \"我说中国话\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}", UTF_16
        )).matches(null, new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"我说中国话\" }", UTF_16, MatchType.ONLY_MATCHING_FIELDS)).toString()
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("" +
                "{ " +
                "   \"some_field\": \"我说中国话\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}", UTF_16)
        )).matches(null, new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"我说中国话\" }", UTF_16, MatchType.ONLY_MATCHING_FIELDS)).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            "" +
                "{ " +
                "   \"some_field\": \"我说中国话\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}", UTF_16
        )).matches(null, new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"我说中国话\" }", UTF_16, MatchType.ONLY_MATCHING_FIELDS)).toString()
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            json("" +
                "{ " +
                "   \"some_field\": \"我说中国话\", " +
                "   \"some_other_field\": \"some_other_value\" " +
                "}", UTF_16)
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"我说中国话\" }", UTF_16, MatchType.ONLY_MATCHING_FIELDS)).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchJsonBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "{ \"some_other_field\": \"some_other_value\" }"
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"some_value\" }")).toString()
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("{ \"some_other_field\": \"some_other_value\" }")
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"some_value\" }")).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchJsonBodyWithCharsetForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString()).withBody(
            "{ \"some_other_field\": \"我说中国话\" }", UTF_8
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"我说中国话\" }", UTF_8, MatchType.ONLY_MATCHING_FIELDS)).toString()

        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("{ \"some_other_field\": \"我说中国话\" }", UTF_8)
        )).matches(null, new HttpRequest().withBody(
            new JsonBodyDTO(json("{ \"some_field\": \"我说中国话\" }", UTF_8, MatchType.ONLY_MATCHING_FIELDS)).toString()
        )), is(false));
    }

    // - JsonPathBody

    @Test
    public void shouldMatchJsonPathBody() {
        assertThat(update(new HttpRequest().withBody(
            jsonPath("$..book[?(@.price > $['expensive'])]")
        )).matches(null, new HttpRequest().withBody(
            "{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 8.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 18.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 19.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"
        )), is(true));
    }

    @Test
    public void shouldMatchNottedJsonPathBody() {
        assertThat(update(new HttpRequest().withBody(
            not(jsonPath("$..book[?(@.price > $['expensive'])]"))
        )).matches(null, new HttpRequest().withBody(
            "{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 8.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 8.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 9.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"
        )), is(true));
    }

    @Test
    public void shouldMatchJsonPathBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            jsonPath("$..book[?(@.price > $['expensive'])]")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            jsonPath("$..book[?(@.price > $['expensive'])]")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldNotMatchJsonPathBody() {
        assertThat(update(new HttpRequest().withBody(
            jsonPath("$..book[?(@.price > $['expensive'])]")
        )).matches(null, new HttpRequest().withBody(
            "{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 8.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 8.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 9.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedJsonPathBody() {
        assertThat(update(new HttpRequest().withBody(
            not(jsonPath("$..book[?(@.price > $['expensive'])]"))
        )).matches(null, new HttpRequest().withBody(
            "{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 8.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 18.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 19.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"
        )), is(false));
    }

    @Test
    public void shouldNotMatchJsonPathBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            jsonPath("$..book[?(@.price > $['expensive'])]")
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            "{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 8.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 18.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 19.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            jsonPath("$..book[?(@.price > $['expensive'])]")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldMatchJsonPathBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 8.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 18.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 19.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"
        )).matches(null, new HttpRequest().withBody(
            new JsonPathBodyDTO(jsonPath("$..book[?(@.price > $['expensive'])]")).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            "{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 8.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 8.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 19.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"
        )).matches(null, new HttpRequest().withBody(
            new JsonPathBodyDTO(jsonPath("$..book[?(@.price > $['expensive'])]")).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchJsonPathBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "{" + NEW_LINE +
                "    \"store\": {" + NEW_LINE +
                "        \"book\": [" + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"reference\"," + NEW_LINE +
                "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                "                \"price\": 8.95" + NEW_LINE +
                "            }," + NEW_LINE +
                "            {" + NEW_LINE +
                "                \"category\": \"fiction\"," + NEW_LINE +
                "                \"author\": \"Herman Melville\"," + NEW_LINE +
                "                \"title\": \"Moby Dick\"," + NEW_LINE +
                "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
                "                \"price\": 8.99" + NEW_LINE +
                "            }" + NEW_LINE +
                "        ]," + NEW_LINE +
                "        \"bicycle\": {" + NEW_LINE +
                "            \"color\": \"red\"," + NEW_LINE +
                "            \"price\": 9.95" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"expensive\": 10" + NEW_LINE +
                "}"
        )).matches(null, new HttpRequest().withBody(
            new JsonPathBodyDTO(jsonPath("$..book[?(@.price > $['expensive'])]")).toString()
        )), is(false));
    }

    // - JsonSchemaBody

    // - with Json

    @Test
    public void shouldMatchJsonSchemaBody() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest().withBody(
            "\"someBody\""
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest().withBody(
            json("\"someBody\"")
        )), is(true));
    }

    @Test
    public void shouldMatchNottedJsonSchemaBody() {
        assertThat(update(new HttpRequest().withBody(
            not(jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}"))
        )).matches(null, new HttpRequest().withBody(
            "\"someOtherBody\""
        )), is(true));
    }

    @Test
    public void shouldMatchJsonSchemaBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            "\"someBody\""
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldMatchJsonSchemaBodyWithComplexSchema() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest().withBody(
            "" +
                "{" + NEW_LINE +
                "    \"id\": 1," + NEW_LINE +
                "    \"name\": \"A green door\"," + NEW_LINE +
                "    \"price\": 12.50," + NEW_LINE +
                "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                "}"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "    \"id\": 1," + NEW_LINE +
                "    \"name\": \"A green door\"," + NEW_LINE +
                "    \"price\": 12.50," + NEW_LINE +
                "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                "}")
        )), is(true));
    }

    @Test
    public void shouldNotMatchJsonSchemaBody() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest().withBody(
            "\"someOtherBody\""
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest().withBody(
            json("\"someOtherBody\"")
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedJsonSchemaBody() {
        assertThat(update(new HttpRequest().withBody(
            not(jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}"))
        )).matches(null, new HttpRequest().withBody(
            "\"someBody\""
        )), is(false));
    }

    @Test
    public void shouldNotMatchJsonSchemaBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldNotMatchJsonSchemaBodyWithComplexSchema() {
        // too few tags in array
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest().withBody(
            "" +
                "{" + NEW_LINE +
                "    \"id\": 1," + NEW_LINE +
                "    \"name\": \"A green door\"," + NEW_LINE +
                "    \"price\": 12.50," + NEW_LINE +
                "    \"tags\": []" + NEW_LINE +
                "}"
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest().withBody(
            json("" +
                "{" + NEW_LINE +
                "    \"id\": 1," + NEW_LINE +
                "    \"name\": \"A green door\"," + NEW_LINE +
                "    \"price\": 12.50," + NEW_LINE +
                "    \"tags\": []" + NEW_LINE +
                "}")
        )), is(false));
    }

    @Test
    public void shouldMatchJsonSchemaBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "\"someBody\""
        )).matches(null, new HttpRequest().withBody(
            new JsonSchemaBodyDTO(jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")).toString()
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("\"someBody\"")
        )).matches(null, new HttpRequest().withBody(
            new JsonSchemaBodyDTO(jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            "\"someBody\""
        )).matches(null, new HttpRequest().withBody(
            new JsonSchemaBodyDTO(jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")).toString()
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            json("\"someBody\"")
        )).matches(null, new HttpRequest().withBody(
            new JsonSchemaBodyDTO(jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchJsonSchemaBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "\"someOtherBody\""
        )).matches(null, new HttpRequest().withBody(
            new JsonSchemaBodyDTO(jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")).toString()
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            json("\"someOtherBody\"")
        )).matches(null, new HttpRequest().withBody(
            new JsonSchemaBodyDTO(jsonSchema("{" + NEW_LINE +
                "   \"type\": \"string\"," + NEW_LINE +
                "   \"pattern\": \"^someB[a-z]{3}$\"" + NEW_LINE +
                "}")).toString()
        )), is(false));
    }

    // - with Xml

    @Test
    public void shouldMatchJsonSchemaBodyWithXmlBody() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>A green door</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>home</tags>" + NEW_LINE +
                    "    <tags>green</tags>" + NEW_LINE +
                    "</root>"
            )
        ), is(true));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"خانه\", \"سبز\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML_UTF_8)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>یک درب سبز</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>خانه</tags>" + NEW_LINE +
                    "    <tags>سبز</tags>" + NEW_LINE +
                    "</root>"
            )
        ), is(true));
    }

    @Test
    public void shouldMatchNottedJsonSchemaBodyWithXmlBody() {
        // too few tags in array
        assertThat(update(new HttpRequest().withBody(
            not(jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 3," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}"))
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>A green door</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>home</tags>" + NEW_LINE +
                    "    <tags>green</tags>" + NEW_LINE +
                    "</root>"
            )
        ), is(true));
    }

    @Test
    public void shouldMatchJsonSchemaBodyWithXmlBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
                .withOptional(true)
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>A green door</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>home</tags>" + NEW_LINE +
                    "    <tags>green</tags>" + NEW_LINE +
                    "</root>"
            )
        ), is(true));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldNotMatchJsonSchemaBodyWithXmlBody() {
        // too few tags in array
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 3," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>A green door</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>home</tags>" + NEW_LINE +
                    "    <tags>green</tags>" + NEW_LINE +
                    "</root>"
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchNottedJsonSchemaBodyWithXmlBody() {
        assertThat(update(new HttpRequest().withBody(
            not(jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}"))
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>A green door</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>home</tags>" + NEW_LINE +
                    "    <tags>green</tags>" + NEW_LINE +
                    "</root>"
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchJsonSchemaBodyWithXmlBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldMatchJsonSchemaBodyWithXmlBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>A green door</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>home</tags>" + NEW_LINE +
                    "    <tags>green</tags>" + NEW_LINE +
                    "</root>"
            )
        ).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                jsonSchema("{" + NEW_LINE +
                    "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                    "    \"title\": \"Product\"," + NEW_LINE +
                    "    \"type\": \"object\"," + NEW_LINE +
                    "    \"properties\": {" + NEW_LINE +
                    "        \"id\": {" + NEW_LINE +
                    "            \"type\": \"integer\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"name\": {" + NEW_LINE +
                    "            \"type\": \"string\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"price\": {" + NEW_LINE +
                    "            \"type\": \"number\"," + NEW_LINE +
                    "            \"minimum\": 0," + NEW_LINE +
                    "            \"exclusiveMinimum\": true" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"tags\": {" + NEW_LINE +
                    "            \"type\": \"array\"," + NEW_LINE +
                    "            \"items\": {" + NEW_LINE +
                    "                \"type\": \"string\"," + NEW_LINE +
                    "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                    "            }," + NEW_LINE +
                    "            \"minItems\": 1," + NEW_LINE +
                    "            \"uniqueItems\": true" + NEW_LINE +
                    "        }" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                    "}")
            )
        ), is(true));
        assertThat(update(new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>A green door</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>home</tags>" + NEW_LINE +
                    "    <tags>green</tags>" + NEW_LINE +
                    "</root>"
            )
        ).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                jsonSchema("{" + NEW_LINE +
                    "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                    "    \"title\": \"Product\"," + NEW_LINE +
                    "    \"type\": \"object\"," + NEW_LINE +
                    "    \"properties\": {" + NEW_LINE +
                    "        \"id\": {" + NEW_LINE +
                    "            \"type\": \"integer\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"name\": {" + NEW_LINE +
                    "            \"type\": \"string\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"price\": {" + NEW_LINE +
                    "            \"type\": \"number\"," + NEW_LINE +
                    "            \"minimum\": 0," + NEW_LINE +
                    "            \"exclusiveMinimum\": true" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"tags\": {" + NEW_LINE +
                    "            \"type\": \"array\"," + NEW_LINE +
                    "            \"items\": {" + NEW_LINE +
                    "                \"type\": \"string\"," + NEW_LINE +
                    "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                    "            }," + NEW_LINE +
                    "            \"minItems\": 1," + NEW_LINE +
                    "            \"uniqueItems\": true" + NEW_LINE +
                    "        }" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                    "}")
            )
        ), is(false));
    }

    @Test
    public void shouldNotMatchJsonSchemaBodyWithXmlBodyForControlPlane() {
        // too few tags in array
        assertThat(updateForControlPlane(new HttpRequest()
            .withContentType(MediaType.APPLICATION_XML)
            .withBody(
                "" +
                    "<root>" + NEW_LINE +
                    "    <id>1</id>" + NEW_LINE +
                    "    <name>A green door</name>" + NEW_LINE +
                    "    <price>12.5</price>" + NEW_LINE +
                    "    <tags>home</tags>" + NEW_LINE +
                    "    <tags>green</tags>" + NEW_LINE +
                    "</root>"
            )
        ).matches(null, new HttpRequest()
            .withBody(
                jsonSchema("{" + NEW_LINE +
                    "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                    "    \"title\": \"Product\"," + NEW_LINE +
                    "    \"type\": \"object\"," + NEW_LINE +
                    "    \"properties\": {" + NEW_LINE +
                    "        \"id\": {" + NEW_LINE +
                    "            \"type\": \"integer\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"name\": {" + NEW_LINE +
                    "            \"type\": \"string\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"price\": {" + NEW_LINE +
                    "            \"type\": \"number\"," + NEW_LINE +
                    "            \"minimum\": 0," + NEW_LINE +
                    "            \"exclusiveMinimum\": true" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"tags\": {" + NEW_LINE +
                    "            \"type\": \"array\"," + NEW_LINE +
                    "            \"items\": {" + NEW_LINE +
                    "                \"type\": \"string\"," + NEW_LINE +
                    "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                    "            }," + NEW_LINE +
                    "            \"minItems\": 3," + NEW_LINE +
                    "            \"uniqueItems\": true" + NEW_LINE +
                    "        }" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                    "}")
            )
        ), is(false));
    }

    // - with Form Parameters

    @Test
    public void shouldMatchJsonSchemaBodyWithFormParameters() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody("" +
                "id=1" +
                "&name=A+green+door" +
                "&price=12.5" +
                "&tags=home" +
                "&tags=green")
        ), is(true));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"خانه\", \"سبز\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED.withCharset(UTF_8))
            .withBody("" +
                "id=1" +
                "&name=یک درب سبز" +
                "&price=12.5" +
                "&tags=خانه" +
                "&tags=سبز")
        ), is(true));
    }

    @Test
    public void shouldMatchNottedJsonSchemaBodyWithFormParameters() {
        assertThat(update(new HttpRequest().withBody(
            not(jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 3," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}"))
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody("" +
                "id=1" +
                "&name=A+green+door" +
                "&price=12.5" +
                "&tags=home" +
                "&tags=green")
        ), is(true));
    }

    @Test
    public void shouldMatchJsonSchemaBodyWithFormParametersWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
                .withOptional(true)
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody("" +
                "id=1" +
                "&name=A+green+door" +
                "&price=12.5" +
                "&tags=home" +
                "&tags=green")
        ), is(true));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldNotMatchJsonSchemaBodyWithFormParameters() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 3," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody("" +
                "id=1" +
                "&name=A+green+door" +
                "&price=12.5" +
                "&tags=home" +
                "&tags=green")
        ), is(false));
    }

    @Test
    public void shouldNotMatchNottedJsonSchemaBodyWithFormParameters() {
        assertThat(update(new HttpRequest().withBody(
            not(jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}"))
        )).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody("" +
                "id=1" +
                "&name=A+green+door" +
                "&price=12.5" +
                "&tags=home" +
                "&tags=green")
        ), is(false));
    }

    @Test
    public void shouldNotMatchJsonSchemaBodyWithFormParametersWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            jsonSchema("{" + NEW_LINE +
                "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                "    \"title\": \"Product\"," + NEW_LINE +
                "    \"type\": \"object\"," + NEW_LINE +
                "    \"properties\": {" + NEW_LINE +
                "        \"id\": {" + NEW_LINE +
                "            \"type\": \"integer\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"name\": {" + NEW_LINE +
                "            \"type\": \"string\"" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"price\": {" + NEW_LINE +
                "            \"type\": \"number\"," + NEW_LINE +
                "            \"minimum\": 0," + NEW_LINE +
                "            \"exclusiveMinimum\": true" + NEW_LINE +
                "        }," + NEW_LINE +
                "        \"tags\": {" + NEW_LINE +
                "            \"type\": \"array\"," + NEW_LINE +
                "            \"items\": {" + NEW_LINE +
                "                \"type\": \"string\"," + NEW_LINE +
                "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                "            }," + NEW_LINE +
                "            \"minItems\": 1," + NEW_LINE +
                "            \"uniqueItems\": true" + NEW_LINE +
                "        }" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                "}")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldMatchJsonSchemaBodyWithFormParametersForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody("" +
                "id=1" +
                "&name=A+green+door" +
                "&price=12.5" +
                "&tags=home" +
                "&tags=green")
        ).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody(
                jsonSchema("{" + NEW_LINE +
                    "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                    "    \"title\": \"Product\"," + NEW_LINE +
                    "    \"type\": \"object\"," + NEW_LINE +
                    "    \"properties\": {" + NEW_LINE +
                    "        \"id\": {" + NEW_LINE +
                    "            \"type\": \"integer\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"name\": {" + NEW_LINE +
                    "            \"type\": \"string\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"price\": {" + NEW_LINE +
                    "            \"type\": \"number\"," + NEW_LINE +
                    "            \"minimum\": 0," + NEW_LINE +
                    "            \"exclusiveMinimum\": true" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"tags\": {" + NEW_LINE +
                    "            \"type\": \"array\"," + NEW_LINE +
                    "            \"items\": {" + NEW_LINE +
                    "                \"type\": \"string\"," + NEW_LINE +
                    "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                    "            }," + NEW_LINE +
                    "            \"minItems\": 1," + NEW_LINE +
                    "            \"uniqueItems\": true" + NEW_LINE +
                    "        }" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                    "}")
            )
        ), is(true));
        assertThat(update(new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody("" +
                "id=1" +
                "&name=A+green+door" +
                "&price=12.5" +
                "&tags=home" +
                "&tags=green")
        ).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody(
                jsonSchema("{" + NEW_LINE +
                    "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                    "    \"title\": \"Product\"," + NEW_LINE +
                    "    \"type\": \"object\"," + NEW_LINE +
                    "    \"properties\": {" + NEW_LINE +
                    "        \"id\": {" + NEW_LINE +
                    "            \"type\": \"integer\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"name\": {" + NEW_LINE +
                    "            \"type\": \"string\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"price\": {" + NEW_LINE +
                    "            \"type\": \"number\"," + NEW_LINE +
                    "            \"minimum\": 0," + NEW_LINE +
                    "            \"exclusiveMinimum\": true" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"tags\": {" + NEW_LINE +
                    "            \"type\": \"array\"," + NEW_LINE +
                    "            \"items\": {" + NEW_LINE +
                    "                \"type\": \"string\"," + NEW_LINE +
                    "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                    "            }," + NEW_LINE +
                    "            \"minItems\": 1," + NEW_LINE +
                    "            \"uniqueItems\": true" + NEW_LINE +
                    "        }" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                    "}")
            )
        ), is(false));

    }

    @Test
    public void shouldNotMatchJsonSchemaBodyWithFormParametersForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody("" +
                "id=1" +
                "&name=A+green+door" +
                "&price=12.5" +
                "&tags=home" +
                "&tags=green")
        ).matches(null, new HttpRequest()
            .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
            .withBody(
                jsonSchema("{" + NEW_LINE +
                    "    \"$schema\": \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
                    "    \"title\": \"Product\"," + NEW_LINE +
                    "    \"type\": \"object\"," + NEW_LINE +
                    "    \"properties\": {" + NEW_LINE +
                    "        \"id\": {" + NEW_LINE +
                    "            \"type\": \"integer\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"name\": {" + NEW_LINE +
                    "            \"type\": \"string\"" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"price\": {" + NEW_LINE +
                    "            \"type\": \"number\"," + NEW_LINE +
                    "            \"minimum\": 0," + NEW_LINE +
                    "            \"exclusiveMinimum\": true" + NEW_LINE +
                    "        }," + NEW_LINE +
                    "        \"tags\": {" + NEW_LINE +
                    "            \"type\": \"array\"," + NEW_LINE +
                    "            \"items\": {" + NEW_LINE +
                    "                \"type\": \"string\"," + NEW_LINE +
                    "                \"enum\": [\"home\", \"green\"]" + NEW_LINE +
                    "            }," + NEW_LINE +
                    "            \"minItems\": 3," + NEW_LINE +
                    "            \"uniqueItems\": true" + NEW_LINE +
                    "        }" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                    "}")
            )
        ), is(false));
    }

    // - ParameterBody

    @Test
    public void shouldMatchParameterBody() {
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo")
            )
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameTwo", "valueTwo")
            )
        )).matches(null, new HttpRequest().withBody(new ParameterBody(
            new Parameter("nameOne", "valueOne"),
            new Parameter("nameTwo", "valueTwo")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameTwo", "valueTwo", "valueThree")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo"),
                new Parameter("nameTwo", "valueThree")
            )
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameTwo", "valueTwo")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo"),
                new Parameter("nameTwo", "valueThree")
            )
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameTwo", "valueThree")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo"),
                new Parameter("nameTwo", "valueThree")
            )
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameTwo", "valueT[a-z]{0,10}")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo"),
                new Parameter("nameTwo", "valueThree")
            )
        )), is(true));
    }

    @Test
    public void shouldMatchNottedParameterBody() {
        assertThat(update(new HttpRequest().withBody(
            not(new ParameterBody(new Parameter("name", "value"))))
        ).matches(null, new HttpRequest().withBody(
            new ParameterBody(new Parameter("wrongName", "value"))
        )), is(true));
    }

    @Test
    public void shouldMatchParameterBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne")
            )
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo")
            )
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne")
            )
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldMatchParameterBodyWithUrlEncodedBodyParameters() {
        // pass exact match
        assertThat(update(new HttpRequest().withBody(params(param("name one", "value one"), param("nameTwo", "valueTwo"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+one&nameTwo=valueTwo"
        )), is(true));

        // ignore extra parameters
        assertThat(update(new HttpRequest().withBody(params(param("name one", "value one"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+one&nameTwo=valueTwo"
        )), is(true));

        // matches multi-value parameters
        assertThat(update(new HttpRequest().withBody(params(param("name one", "value one one", "value one two"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+one+one&name+one=value+one+two"
        )), is(true));

        // matches multi-value parameters (ignore extra values)
        assertThat(update(new HttpRequest().withBody(params(param("name one", "value one one"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+one+one&name+one=value+one+two"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(params(param("name one", "value one two"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+one+one&name+one=value+one+two"
        )), is(true));

        // matches using regex
        assertThat(update(new HttpRequest().withBody(params(param("name one", "value [a-z]{0,10}"), param("nameTwo", "valueT[a-z]{0,10}"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+one&nameTwo=valueTwo"
        )), is(true));

        // fail no match
        assertThat(update(new HttpRequest().withBody(
            params(param("name one", "value one"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+two"
        )), is(false));
    }

    @Test
    public void shouldMatchBodyFormStyleParameter() {
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1,1,1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1,1,1")
        ))), is(false));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM),
            new Parameter("someKeyTwo", "a")
                .withStyle(FORM)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1,1,1"),
            new Parameter("someKeyTwo", "a,a,a")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1,1,1", "1,1,1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1,2,3", "1,2,3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1,2,3", "1,2,3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
                    .withStyle(FORM)
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "a,2,3")
        ))), is(false));
    }

    @Test
    public void shouldMatchBodyFormExpandedStyleParameter() {
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM_EXPLODED),
            new Parameter("someKeyTwo", "a")
                .withStyle(FORM_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1"),
            new Parameter("someKeyTwo", "a", "a", "a")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(FORM_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "a", "2", "3")
        ))), is(false));
    }

    @Test
    public void shouldMatchBodySpaceDelimitedStyleParameter() {
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1%201%201")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1+1+1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1 1 1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1%201%201")
        ))), is(false));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1+1+1")
        ))), is(false));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1 1 1")
        ))), is(false));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED),
            new Parameter("someKeyTwo", "a")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1+1+1"),
            new Parameter("someKeyTwo", "a+a+a")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1+1+1", "1+1+1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1%202%203", "1%202%203")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1+2+3", "1+2+3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1 2 3", "1 2 3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1%202%203", "1%202%203")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
                    .withStyle(SPACE_DELIMITED)
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "a+2+3")
        ))), is(false));
    }

    @Test
    public void shouldMatchBodySpaceDelimitedExpandedStyleParameter() {
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED_EXPLODED),
            new Parameter("someKeyTwo", "a")
                .withStyle(SPACE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1"),
            new Parameter("someKeyTwo", "a", "a", "a")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(SPACE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "a", "2", "3")
        ))), is(false));
    }

    @Test
    public void shouldMatchBodyPipeDelimitedStyleParameter() {
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1|1|1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1|1|1")
        ))), is(false));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED),
            new Parameter("someKeyTwo", "a")
                .withStyle(PIPE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1|1|1"),
            new Parameter("someKeyTwo", "a|a|a")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1|1|1", "1|1|1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1|2|3", "1|2|3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "a|2|3", "b|2|3")
        ))), is(false));
    }

    @Test
    public void shouldMatchBodyPipeDelimitedExpandedStyleParameter() {
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED_EXPLODED),
            new Parameter("someKeyTwo", "a")
                .withStyle(PIPE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1"),
            new Parameter("someKeyTwo", "a", "a", "a")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "1", "1", "1", "1", "1")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(params(
            new Parameter("someKey", "1")
                .withStyle(PIPE_DELIMITED_EXPLODED)
        ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "1", "2", "3", "1", "2", "3")
        ))), is(true));
        assertThat(update(new HttpRequest().withBody(
            params(
                new Parameter("someKey", "1")
            ))).matches(null, new HttpRequest().withBody(params(
            new Parameter("someKey", "a", "2", "3")
        ))), is(false));
    }

    @Test
    public void shouldNotMatchParameterBody() {
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(new Parameter("name", "value")))
        ).matches(null, new HttpRequest().withBody(
            new ParameterBody(new Parameter("wrongName", "value"))
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(new Parameter("name", "value"))
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(new Parameter("name", "wrongValue"))
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(new Parameter("name", "value"))
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(new Parameter("wrongName", "wrongValue"))
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(new Parameter("name", "va[0-9]{1}ue"))
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(new Parameter("name", "wrongValue"))
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedParameterBody() {
        assertThat(update(new HttpRequest().withBody(
            not(new ParameterBody(
                new Parameter("nameOne", "valueOne")
            ))
        )).matches(null, new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo")
            )
        )), is(false));
    }

    @Test
    public void shouldNotMatchParameterBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne")
            )
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne")
            )
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldNotMatchParameterBodyWithUrlEncodedBodyParameters() {
        assertThat(update(new HttpRequest().withBody(
            params(param("name one", "wrong value"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+one"
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            params(param("wrong name", "value one"))
        )).matches(null, new HttpRequest().withBody(
            "name+one=value+one"
        )), is(false));
    }

    @Test
    public void shouldMatchParameterBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(
                new Parameter("nameOne", "valueOne")
            )).toString()
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo"),
                new Parameter("nameTwo", "valueThree")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(
                new Parameter("nameTwo", "valueTwo", "valueThree")
            )).toString()
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo"),
                new Parameter("nameTwo", "valueThree")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(
                new Parameter("nameTwo", "valueT[a-z]{0,10}")
            )).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(
                new Parameter("nameOne", "valueOne")
            )).toString()
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo"),
                new Parameter("nameTwo", "valueThree")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(
                new Parameter("nameTwo", "valueTwo", "valueThree")
            )).toString()
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            new ParameterBody(
                new Parameter("nameOne", "valueOne"),
                new Parameter("nameTwo", "valueTwo"),
                new Parameter("nameTwo", "valueThree")
            )
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(
                new Parameter("nameTwo", "valueT[a-z]{0,10}")
            )).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchParameterBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            new ParameterBody(new Parameter("wrongName", "value"))
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(new Parameter("name", "value"))).toString()
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            new ParameterBody(new Parameter("name", "wrongValue"))
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(new Parameter("name", "value"))).toString()
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            new ParameterBody(new Parameter("wrongName", "wrongValue"))
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(new Parameter("name", "value"))).toString()
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            new ParameterBody(new Parameter("name", "wrongValue"))
        )).matches(null, new HttpRequest().withBody(
            new ParameterBodyDTO(new ParameterBody(new Parameter("name", "va[0-9]{1}ue"))).toString()
        )), is(false));
    }

    // - RegexBody

    @Test
    public void shouldMatchRegexBody() {
        assertThat(update(new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )).matches(null, new HttpRequest().withBody(
            "somebody"
        )), is(true));
    }

    @Test
    public void shouldMatchNottedRegexBody() {
        assertThat(update(new HttpRequest().withBody(
            not(new RegexBody("someb[a-z]{3}"))
        )).matches(null, new HttpRequest().withBody(
            "wrongBody"
        )), is(true));
    }

    @Test
    public void shouldMatchRegexBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            "somebody"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldNotMatchRegexBody() {
        assertThat(update(new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )).matches(null, new HttpRequest().withBody(
            "wrongBody"
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )).matches(null, new HttpRequest().withBody(
            (String) null
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )).matches(null, new HttpRequest().withBody(
            ""
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedRegexBody() {
        assertThat(update(new HttpRequest().withBody(
            not(new RegexBody("someb[a-z]{3}"))
        )).matches(null, new HttpRequest().withBody(
            "somebody"
        )), is(false));
    }

    @Test
    public void shouldNotMatchRegexBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldMatchRegexBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "somebody"
        )).matches(null, new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            (String) null
        )).matches(null, new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            ""
        )).matches(null, new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            "somebody"
        )).matches(null, new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            (String) null
        )).matches(null, new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            ""
        )).matches(null, new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )), is(true));
    }

    @Test
    public void shouldNotMatchRegexBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "wrongBody"
        )).matches(null, new HttpRequest().withBody(
            new RegexBody("someb[a-z]{3}")
        )), is(false));
    }

    // - StringBody

    @Test
    public void shouldMatchStringBody() {
        assertThat(update(new HttpRequest().withBody(
            new StringBody("somebody")
        )).matches(null, new HttpRequest().withBody(
            "somebody"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new StringBody("somebody")
        )).matches(null, new HttpRequest().withBody(
            exact("somebody")
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            (String) null
        )).matches(null, new HttpRequest().withBody(
            "somebody"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            (String) null
        )).matches(null, new HttpRequest().withBody(
            exact("somebody")
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            ""
        )).matches(null, new HttpRequest().withBody(
            "somebody"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            ""
        )).matches(null, new HttpRequest().withBody(
            exact("somebody")
        )), is(true));
    }

    @Test
    public void shouldMatchNottedStringBody() {
        assertThat(update(new HttpRequest().withBody(
            not(new StringBody("somebody"))
        )).matches(null, new HttpRequest().withBody(
            "wrongBody"
        )), is(true));
    }

    @Test
    public void shouldMatchStringBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            new StringBody("somebody")
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            "somebody"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            new StringBody("somebody")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldNotMatchStringBody() {
        assertThat(update(new HttpRequest().withBody(
            new StringBody("somebody")
        )).matches(null, new HttpRequest().withBody(
            "wrongBody"
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            exact("somebody")
        )).matches(null, new HttpRequest().withBody(
            (String) null
        )), is(false));
        assertThat(update(new HttpRequest().withBody(
            exact("somebody")
        )).matches(null, new HttpRequest().withBody(
            ""
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedStringBody() {
        assertThat(update(new HttpRequest().withBody(
            not(new StringBody("somebody"))
        )).matches(null, new HttpRequest().withBody(
            "somebody"
        )), is(false));
    }

    @Test
    public void shouldNotMatchStringBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            new StringBody("somebody")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            new StringBody("somebody")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldMatchStringBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "somebody"
        )).matches(null, new HttpRequest().withBody(
            new StringBody("somebody")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            exact("somebody")
        )).matches(null, new HttpRequest().withBody(
            new StringBody("somebody")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            (String) null
        )).matches(null, new HttpRequest().withBody(
            exact("somebody")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            ""
        )).matches(null, new HttpRequest().withBody(
            exact("somebody")
        )), is(true));
    }

    @Test
    public void shouldNotMatchStringBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "wrongBody"
        )).matches(null, new HttpRequest().withBody(
            new StringBody("somebody")
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "somebody"
        )).matches(null, new HttpRequest().withBody(
            (String) null
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            exact("somebody")
        )).matches(null, new HttpRequest().withBody(
            (String) null
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "somebody"
        )).matches(null, new HttpRequest().withBody(
            ""
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            exact("somebody")
        )).matches(null, new HttpRequest().withBody(
            ""
        )), is(false));
    }

    @Test
    public void shouldNotMatchLiteralNullStringBodyAgainstEmptyBodyForControlPlane() {
        // W3-3a: the control-plane body fast path stringifies both sides via String.valueOf and
        // compares them with equalsIgnoreCase. The fast path now additionally requires the
        // candidate request body to be non-null before stringifying, so String.valueOf((Body) null)
        // can never collapse to the literal "null" and create a spurious fast-path match. Here the
        // candidate request carries an empty body (getBody() non-null, getBodyAsString() == ""), so
        // a literal "null" filter must not match. (This verdict is also enforced by the fallback
        // body matcher, so it holds with or without the fast-path guard — the guard removes the
        // latent String.valueOf(null) -> "null" foot-gun without changing this observable result.)
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            exact("null")
        )).matches(null, new HttpRequest().withBody(
            (String) null
        )), is(false));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "null"
        )).matches(null, new HttpRequest().withBody(
            (String) null
        )), is(false));
    }

    @Test
    public void shouldMatchLiteralNullStringBodyAgainstLiteralNullForControlPlane() {
        // W3-3a guard must not regress the genuine case: a control-plane body filter of literal
        // "null" still matches a request whose body really is the literal text "null".
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            exact("null")
        )).matches(null, new HttpRequest().withBody(
            new StringBody("null")
        )), is(true));
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "null"
        )).matches(null, new HttpRequest().withBody(
            new StringBody("null")
        )), is(true));
        // and a genuine body filter against a matching genuine body still works
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            exact("somebody")
        )).matches(null, new HttpRequest().withBody(
            new StringBody("somebody")
        )), is(true));
    }

    @Test
    public void matchesMatchingBodyRegex() {
        assertThat(update(new HttpRequest().withBody(regex("some[a-z]{4}")
        )).matches(null, new HttpRequest().withBody("somebody")), is(true));
    }

    @Test
    public void doesNotMatchIncorrectBodyRegex() {
        assertThat(update(new HttpRequest().withBody(regex("some[a-z]{3}")
        )).matches(null, new HttpRequest().withBody("bodysome")), is(false));
    }

    // - XPathBody

    @Test
    public void shouldMatchXPathBody() {
        assertThat(update(new HttpRequest().withBody(
            xpath("/element[key = 'some_key' and value = 'some_value']")
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )), is(true));
    }

    @Test
    public void shouldMatchNottedXPathBody() {
        assertThat(update(new HttpRequest().withBody(
            not(xpath("/element[key = 'some_key' and value = 'some_value']"))
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "</element>"
        )), is(true));
    }

    @Test
    public void shouldMatchXPathBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            xpath("/element[key = 'some_key' and value = 'some_value']")
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            xpath("/element[key = 'some_key' and value = 'some_value']")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldNotMatchXPathBody() {
        assertThat(update(new HttpRequest().withBody(
            xpath("/element[key = 'some_key' and value = 'some_value']")
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "</element>"
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedXPathBody() {
        assertThat(update(new HttpRequest().withBody(
            not(xpath("/element[key = 'some_key' and value = 'some_value']"))
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )), is(false));
    }

    @Test
    public void shouldNotMatchXPathBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            xpath("/element[key = 'some_key' and value = 'some_value']")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            xpath("/element[key = 'some_key' and value = 'some_value']")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldMatchXPathBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )).matches(null, new HttpRequest().withBody(
            new XPathBodyDTO(xpath("/element[key = 'some_key' and value = 'some_value']")).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )).matches(null, new HttpRequest().withBody(
            new XPathBodyDTO(xpath("/element[key = 'some_key' and value = 'some_value']")).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchXPathBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "</element>"
        )).matches(null, new HttpRequest().withBody(
            new XPathBodyDTO(xpath("/element[key = 'some_key' and value = 'some_value']")).toString()
        )), is(false));
    }

    // - XMLBody

    @Test
    public void shouldMatchXmlBody() {
        assertThat(update(new HttpRequest().withBody(
            xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )), is(true));
    }

    @Test
    public void shouldMatchNottedXmlBody() {
        assertThat(update(new HttpRequest().withBody(
            not(xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"))
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "</element>"
        )), is(true));
    }

    @Test
    public void shouldMatchXmlBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldNotMatchXmlBody() {
        assertThat(update(new HttpRequest().withBody(
            xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "</element>"
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedXmlBody() {
        assertThat(update(new HttpRequest().withBody(
            not(xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"))
        )).matches(null, new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )), is(false));
    }

    @Test
    public void shouldNotMatchXmlBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldMatchXmlBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )).matches(null, new HttpRequest().withBody(
            new XmlBodyDTO(xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>"
        )).matches(null, new HttpRequest().withBody(
            new XmlBodyDTO(xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchXmlBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "" +
                "<element>" +
                "   <key>some_key</key>" +
                "</element>"
        )).matches(null, new HttpRequest().withBody(
            new XmlBodyDTO(xml("" +
                "<element>" +
                "   <key>some_key</key>" +
                "   <value>some_value</value>" +
                "</element>")).toString()
        )), is(false));
    }

    // - XMLSchemaBody

    @Test
    public void shouldMatchXMLSchemaBody() {
        assertThat(update(new HttpRequest().withBody(
            xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")
        )).matches(null, new HttpRequest().withBody("" +
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
            "<notes>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Bob</to>" + NEW_LINE +
            "        <from>Bill</from>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Buy Bread</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Jack</to>" + NEW_LINE +
            "        <from>Jill</from>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Wash Shirts</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "</notes>"
        )), is(true));
    }

    @Test
    public void shouldMatchNottedXMLSchemaBody() {
        // from missing in first note
        assertThat(update(new HttpRequest().withBody(
            not(xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>"))
        )).matches(null, new HttpRequest().withBody("" +
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
            "<notes>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Bob</to>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Buy Bread</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Jack</to>" + NEW_LINE +
            "        <from>Jill</from>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Wash Shirts</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "</notes>"
        )), is(true));
    }

    @Test
    public void shouldMatchXMLSchemaBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")
                .withOptional(true)
        )).matches(null, new HttpRequest().withBody("" +
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
            "<notes>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Bob</to>" + NEW_LINE +
            "        <from>Bill</from>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Buy Bread</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Jack</to>" + NEW_LINE +
            "        <from>Jill</from>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Wash Shirts</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "</notes>"
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")
                .withOptional(true)
        )).matches(null, new HttpRequest()), is(true));
    }

    @Test
    public void shouldNotMatchXMLSchemaBody() {
        // from missing in first note
        assertThat(update(new HttpRequest().withBody(
            xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")
        )).matches(null, new HttpRequest().withBody("" +
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
            "<notes>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Bob</to>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Buy Bread</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Jack</to>" + NEW_LINE +
            "        <from>Jill</from>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Wash Shirts</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "</notes>"
        )), is(false));
    }

    @Test
    public void shouldNotMatchNottedXMLSchemaBody() {
        assertThat(update(new HttpRequest().withBody(
            not(xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>"))
        )).matches(null, new HttpRequest().withBody("" +
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
            "<notes>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Bob</to>" + NEW_LINE +
            "        <from>Bill</from>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Buy Bread</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "    <note>" + NEW_LINE +
            "        <to>Jack</to>" + NEW_LINE +
            "        <from>Jill</from>" + NEW_LINE +
            "        <heading>Reminder</heading>" + NEW_LINE +
            "        <body>Wash Shirts</body>" + NEW_LINE +
            "    </note>" + NEW_LINE +
            "</notes>"
        )), is(false));
    }

    @Test
    public void shouldNotMatchXMLSchemaBodyWithOptional() {
        assertThat(update(new HttpRequest().withBody(
            xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")
                .withOptional(false)
        )).matches(null, new HttpRequest()), is(false));
        assertThat(update(new HttpRequest().withBody(
            xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")
        )).matches(null, new HttpRequest()), is(false));
    }

    @Test
    public void shouldMatchXMLSchemaBodyForControlPlane() {
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "" +
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
                "<notes>" + NEW_LINE +
                "    <note>" + NEW_LINE +
                "        <to>Bob</to>" + NEW_LINE +
                "        <from>Bill</from>" + NEW_LINE +
                "        <heading>Reminder</heading>" + NEW_LINE +
                "        <body>Buy Bread</body>" + NEW_LINE +
                "    </note>" + NEW_LINE +
                "    <note>" + NEW_LINE +
                "        <to>Jack</to>" + NEW_LINE +
                "        <from>Jill</from>" + NEW_LINE +
                "        <heading>Reminder</heading>" + NEW_LINE +
                "        <body>Wash Shirts</body>" + NEW_LINE +
                "    </note>" + NEW_LINE +
                "</notes>"
        )).matches(null, new HttpRequest().withBody(
            new XmlSchemaBodyDTO(xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")).toString()
        )), is(true));
        assertThat(update(new HttpRequest().withBody(
            "" +
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
                "<notes>" + NEW_LINE +
                "    <note>" + NEW_LINE +
                "        <to>Bob</to>" + NEW_LINE +
                "        <from>Bill</from>" + NEW_LINE +
                "        <heading>Reminder</heading>" + NEW_LINE +
                "        <body>Buy Bread</body>" + NEW_LINE +
                "    </note>" + NEW_LINE +
                "    <note>" + NEW_LINE +
                "        <to>Jack</to>" + NEW_LINE +
                "        <from>Jill</from>" + NEW_LINE +
                "        <heading>Reminder</heading>" + NEW_LINE +
                "        <body>Wash Shirts</body>" + NEW_LINE +
                "    </note>" + NEW_LINE +
                "</notes>"
        )).matches(null, new HttpRequest().withBody(
            new XmlSchemaBodyDTO(xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")).toString()
        )), is(false));
    }

    @Test
    public void shouldNotMatchXMLSchemaBodyForControlPlane() {
        // from missing in first note
        assertThat(updateForControlPlane(new HttpRequest().withBody(
            "" +
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
                "<notes>" + NEW_LINE +
                "    <note>" + NEW_LINE +
                "        <to>Bob</to>" + NEW_LINE +
                "        <heading>Reminder</heading>" + NEW_LINE +
                "        <body>Buy Bread</body>" + NEW_LINE +
                "    </note>" + NEW_LINE +
                "    <note>" + NEW_LINE +
                "        <to>Jack</to>" + NEW_LINE +
                "        <from>Jill</from>" + NEW_LINE +
                "        <heading>Reminder</heading>" + NEW_LINE +
                "        <body>Wash Shirts</body>" + NEW_LINE +
                "    </note>" + NEW_LINE +
                "</notes>"
        )).matches(null, new HttpRequest().withBody(
            new XmlSchemaBodyDTO(xmlSchema("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
                "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
                "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
                "    <xs:element name=\"notes\">" + NEW_LINE +
                "        <xs:complexType>" + NEW_LINE +
                "            <xs:sequence>" + NEW_LINE +
                "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
                "                    <xs:complexType>" + NEW_LINE +
                "                        <xs:sequence>" + NEW_LINE +
                "                            <xs:element name=\"to\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"from\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"heading\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                            <xs:element name=\"body\" minOccurs=\"1\" maxOccurs=\"1\" type=\"xs:string\"></xs:element>" + NEW_LINE +
                "                        </xs:sequence>" + NEW_LINE +
                "                    </xs:complexType>" + NEW_LINE +
                "                </xs:element>" + NEW_LINE +
                "            </xs:sequence>" + NEW_LINE +
                "        </xs:complexType>" + NEW_LINE +
                "    </xs:element>" + NEW_LINE +
                "</xs:schema>")).toString()
        )), is(false));
    }

    @Test
    public void shouldReturnFormattedRequestWithStringBodyInToString() {
        assertThat(update(request()
                .withMethod("GET")
                .withPath("/some/path")
                .withQueryStringParameters(param("parameterOneName", "parameterOneValue"))
                .withBody("some_body")
                .withHeaders(
                    new Header("name", "value"))
                .withCookies(new Cookie("name", "[A-Z]{0,10}"))
            ).toString(), is("{" + NEW_LINE +
                "  \"method\" : \"GET\"," + NEW_LINE +
                "  \"path\" : \"/some/path\"," + NEW_LINE +
                "  \"queryStringParameters\" : {" + NEW_LINE +
                "    \"parameterOneName\" : [ \"parameterOneValue\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"headers\" : {" + NEW_LINE +
                "    \"name\" : [ \"value\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"cookies\" : {" + NEW_LINE +
                "    \"name\" : \"[A-Z]{0,10}\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"body\" : \"some_body\"" + NEW_LINE +
                "}"));
    }
}
