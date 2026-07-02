package org.mockserver.templates.engine.mustache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matcher;
import org.junit.*;
import org.junit.rules.ExpectedException;
import org.mockserver.time.FixedTime;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpRequestDTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.time.TimeService;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATION_FAILED;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.slf4j.event.Level.INFO;

/**
 * @author jamesdbloom
 */
public class MustacheTemplateEngineTest {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    private static final Configuration configuration = configuration();

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Mock
    private MockServerLogger mockServerLogger;

    @Before
    public void setupTestFixture() {
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
    }

    @Test
    public void shouldRenderLoadIterationContextVariable() {
        // given a load-generation iteration context injected under "iteration"
        HttpRequest request = request().withPath("/item");
        org.mockserver.load.IterationContext iteration =
            new org.mockserver.load.IterationContext(7, 2, 3, 1234, 42);

        // when
        String rendered = new MustacheTemplateEngine(mockServerLogger, configuration)
            .renderTemplate("/item/{{iteration.index}}/vu/{{iteration.vuId}}/count/{{iteration.count}}", request, iteration);

        // then the iteration bean getters resolve
        assertThat(rendered, org.hamcrest.Matchers.is("/item/7/vu/2/count/42"));
    }

    @Test
    public void shouldRenderWithoutIterationContextWhenNull() {
        HttpRequest request = request().withPath("/item").withMethod("GET");
        String rendered = new MustacheTemplateEngine(mockServerLogger, configuration)
            .renderTemplate("method={{request.method}}", request, null);
        assertThat(rendered, org.hamcrest.Matchers.is("method=GET"));
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithMethodPathAndHeader() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'method': '{{ request.method }}', 'path': '{{ request.path }}', 'headers': '{{ request.headers.host.0 }}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldLogTemplateGenerationFailedWhenRenderedResponseIsNotAValidHttpResponse() {
        // given - a template that renders valid JSON which is NOT a valid HttpResponse ("path" is a
        // request-only field, so it fails HttpResponse schema validation with "is not defined in the schema")
        String template = "{\"path\": \"{{ request.path }}\"}";
        HttpRequest request = request()
            .withPath("/api/orders/42")
            .withMethod("GET");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request, HttpResponseDTO.class);

        // then - no valid response could be produced, so the action degrades to its 404 fallback (null here)
        assertThat(actualHttpResponse, is(nullValue()));

        // and - the failure is logged as a properly-classified, request-correlated TEMPLATE_GENERATION_FAILED
        // event (NOT an EXPECTATION_RESPONSE and NOT a generic ERROR) carrying the validation error text and
        // the offending rendered output
        ArgumentCaptor<LogEntry> logEntryCaptor = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(logEntryCaptor.capture());
        LogEntry failure = logEntryCaptor.getAllValues().stream()
            .filter(entry -> entry.getType() == TEMPLATE_GENERATION_FAILED)
            .findFirst()
            .orElse(null);
        assertThat("expected a TEMPLATE_GENERATION_FAILED log event", failure, is(notNullValue()));
        assertThat(failure.getLogLevel(), is(Level.ERROR));
        assertThat(failure.getHttpRequest(), is(request));
        String message = failure.getMessage();
        assertThat(message, containsString("schema"));
        assertThat(message, containsString("path"));
        assertThat(message, containsString("/api/orders/42"));
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithLoopOverEntrySet() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'headers': [{{#request.headers.entrySet}}{{^-first}}, {{/-first}}'{{ key }}={{ value.0 }}'{{/request.headers.entrySet}}]}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withHeader(CONTENT_TYPE.toString(), "plain/text")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'headers': ['host=mock-server.com', 'content-type=plain/text']}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'headers': ['host=mock-server.com', 'content-type=plain/text']}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithLoopOverValuesUsingThis() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'headers': [{{#request.headers.values}}{{^-first}}, {{/-first}}'{{ this.0 }}'{{/request.headers.values}}]}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withHeader(CONTENT_TYPE.toString(), "plain/text")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'headers': ['mock-server.com', 'plain/text']}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'headers': ['mock-server.com', 'plain/text']}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithLoopOverKeysUsingThis() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'headers': [{{#request.headers.keySet}}{{^-first}}, {{/-first}}'{{ -index }}:{{ this }}'{{/request.headers.keySet}}]}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withHeader(CONTENT_TYPE.toString(), "plain/text")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'headers': ['1:host', '2:content-type']}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'headers': ['1:host', '2:content-type']}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithIf() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{{#request.body}}inside_if_{{ this }}{{/request.body}}\"" + NEW_LINE +
            "}";
        HttpRequest nonEmptyBodyRequest = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withHeader(CONTENT_TYPE.toString(), "plain/text")
            .withBody("some_body");

        // when
        HttpResponse nonEmptyBodyActualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, nonEmptyBodyRequest, HttpResponseDTO.class);

        // then
        assertThat(nonEmptyBodyActualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("inside_if_some_body")
        ));

        // then
        HttpRequest emptyBodyRequest = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withHeader(CONTENT_TYPE.toString(), "plain/text");

        // when
        HttpResponse emptyBodyActualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, emptyBodyRequest, HttpResponseDTO.class);

        // then
        assertThat(emptyBodyActualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithParametersCookiesAndBody() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'queryStringParameters': '{{ request.queryStringParameters.nameOne.0 }},{{ request.queryStringParameters.nameTwo.0 }},{{ request.queryStringParameters.nameTwo.1 }}'," +
            " 'pathParameters': '{{ request.pathParameters.nameOne.0 }},{{ request.pathParameters.nameTwo.0 }},{{ request.pathParameters.nameTwo.1 }}'," +
            " 'cookies': '{{ request.cookies.session }}'," +
            " 'body': '{{ request.body }}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withQueryStringParameter("nameOne", "queryValueOne")
            .withQueryStringParameter("nameTwo", "queryValueTwoOne", "queryValueTwoTwo")
            .withPathParameter("nameOne", "pathValueOne")
            .withPathParameter("nameTwo", "pathValueTwoOne", "pathValueTwoTwo")
            .withMethod("POST")
            .withCookie("session", "some_session_id")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody(
                    "{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateReferencingPathGroups() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'whole': '{{ request.pathGroups.0 }}', 'first': '{{ request.pathGroups.1 }}', 'second': '{{ request.pathGroups.2 }}', 'named': '{{ request.namedPathGroups.userId }}'}\"" + NEW_LINE +
            "}";
        // path groups are populated post-match by the matcher; set them directly here to drive the template
        HttpRequest request = request()
            .withPath("/users/42/orders/abc")
            .withMethod("GET")
            .withPathGroups(java.util.Arrays.asList("/users/42/orders/abc", "42", "abc"))
            .withNamedPathGroups(java.util.Collections.singletonMap("userId", "42"));

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'whole': '/users/42/orders/abc', 'first': '42', 'second': 'abc', 'named': '42'}")
        ));
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithDynamicValuesDateAndUUID() throws JsonProcessingException {
        boolean originalFixedUUID = UUIDService.fixedUUID();
        boolean originalFixedTime = TimeService.fixedTime();
        try {
            // given
            UUIDService.fixedUUID(true);
            TimeService.fixedTime(true);
            String template = "{" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': \"{'date': '{{ now }}', 'date_epoch': '{{ now_epoch }}', 'date_iso_8601': '{{ now_iso_8601 }}', 'date_rfc_1123': '{{ now_rfc_1123 }}', 'uuids': ['{{ uuid }}', '{{ uuid }}'] }\"" + NEW_LINE +
                "}";
            HttpRequest request = request()
                .withPath("/somePath")
                .withQueryStringParameter("nameOne", "valueOne")
                .withQueryStringParameter("nameTwo", "valueTwoOne", "valueTwoTwo")
                .withMethod("POST")
                .withCookie("session", "some_session_id")
                .withBody("some_body");

            // when
            HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

            // then
            assertThat(actualHttpResponse, is(
                response()
                    .withStatusCode(200)
                    .withBody("{'date': '" + TimeService.now() + "', 'date_epoch': '" + TimeService
                        .now()
                        .getEpochSecond() + "', 'date_iso_8601': '" + DateTimeFormatter.ISO_INSTANT.format(TimeService.now()) + "', 'date_rfc_1123': '" + DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow()) + "', 'uuids': ['" + UUIDService.getUUID() + "', '" + UUIDService.getUUID() + "'] }")
            ));
            verify(mockServerLogger).logEvent(
                new LogEntry()
                    .setType(TEMPLATE_GENERATED)
                    .setLogLevel(INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("generated output:{}from template:{}for request:{}")
                    .setArguments(
                        OBJECT_MAPPER.readTree("" +
                                                   "{" + NEW_LINE +
                                                   "    'statusCode': 200," + NEW_LINE +
                                                   "    'body': \"{'date': '" + TimeService.now() + "', 'date_epoch': '" + TimeService
                            .now()
                            .getEpochSecond() + "', 'date_iso_8601': '" + DateTimeFormatter.ISO_INSTANT.format(TimeService.now()) + "', 'date_rfc_1123': '" + DateTimeFormatter.RFC_1123_DATE_TIME.format(TimeService.offsetNow()) + "', 'uuids': ['" + UUIDService.getUUID() + "', '" + UUIDService.getUUID() + "'] }\"" + NEW_LINE +
                                                   "}" + NEW_LINE),
                        template,
                        request
                    )
            );

        } finally {
            UUIDService.fixedUUID(originalFixedUUID);
            TimeService.fixedTime(originalFixedTime);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithDynamicValuesRandom() {
        shouldPopulateRandomValue("{{ rand_int }}", equalTo(1));
        shouldPopulateRandomValue("{{ rand_int_10 }}", allOf(greaterThan(0), lessThan(3)));
        shouldPopulateRandomValue("{{ rand_int_100 }}", allOf(greaterThan(0), lessThan(4)));
        shouldPopulateRandomValue("{{ rand_bytes }}", allOf(greaterThan(20), lessThan(50)));
        shouldPopulateRandomValue("{{ rand_bytes_16 }}", allOf(greaterThan(20), lessThan(50)));
        shouldPopulateRandomValue("{{ rand_bytes_32 }}", allOf(greaterThan(40), lessThan(60)));
        shouldPopulateRandomValue("{{ rand_bytes_64 }}", allOf(greaterThan(80), lessThan(120)));
        shouldPopulateRandomValue("{{ rand_bytes_128 }}", allOf(greaterThan(160), lessThan(300)));
    }

    private void shouldPopulateRandomValue(String function, Matcher<Integer> matcher) {
        // given
        String template = "{ 'body': '" + function + "' }";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse.getBodyAsString(), not(equalTo("")));
        assertThat(actualHttpResponse.getBodyAsString().length(), matcher);
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithXPath() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'key': '{{#xPath}}/element/key{{/xPath}}', 'value': '{{#xPath}}/element/value{{/xPath}}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("<element>" + NEW_LINE +
                          "   <key>some_key</key>" + NEW_LINE +
                          "   <value>some_value</value>" + NEW_LINE +
                          "</element>");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'key': 'some_key', 'value': 'some_value'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'key': 'some_key', 'value': 'some_value'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithXPathNode() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'titles': ['{{#xPath}}/store/book/title{{/xPath}}', '{{#xPath}}//book[2]/title{{/xPath}}'], 'bikeColor': '{{#xPath}}//bicycle/color{{/xPath}}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>" + NEW_LINE +
                          "<store>" + NEW_LINE +
                          "  <book>" + NEW_LINE +
                          "    <category>reference</category>" + NEW_LINE +
                          "    <author>Nigel Rees</author>" + NEW_LINE +
                          "    <title>Sayings of the Century</title>" + NEW_LINE +
                          "    <price>18.95</price>" + NEW_LINE +
                          "  </book>" + NEW_LINE +
                          "  <book>" + NEW_LINE +
                          "    <category>fiction</category>" + NEW_LINE +
                          "    <author>Herman Melville</author>" + NEW_LINE +
                          "    <title>Moby Dick</title>" + NEW_LINE +
                          "    <isbn>0-553-21311-3</isbn>" + NEW_LINE +
                          "    <price>8.99</price>" + NEW_LINE +
                          "  </book>" + NEW_LINE +
                          "  <bicycle>" + NEW_LINE +
                          "    <color>red</color>" + NEW_LINE +
                          "    <price>19.95</price>" + NEW_LINE +
                          "  </bicycle>" + NEW_LINE +
                          "  <expensive>10</expensive>" + NEW_LINE +
                          "</store>");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'titles': ['Sayings of the Century', 'Moby Dick'], 'bikeColor': 'red'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'titles': ['Sayings of the Century', 'Moby Dick'], 'bikeColor': 'red'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithXPathWithJsonBody() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'key': '{{#xPath}}/element/key{{/xPath}}', 'value': '{{#xPath}}/element/value{{/xPath}}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("{" + NEW_LINE +
                          "   \"element\": {" + NEW_LINE +
                          "      \"key\": \"some_key\"," + NEW_LINE +
                          "      \"value\": \"some_value\"" + NEW_LINE +
                          "   }" + NEW_LINE +
                          "}");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'key': '', 'value': ''}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'key': '', 'value': ''}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithXPathWithStringBody() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'key': '{{#xPath}}/element/key{{/xPath}}', 'value': '{{#xPath}}/element/value{{/xPath}}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_string_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'key': '', 'value': ''}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'key': '', 'value': ''}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithJsonPath() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'titles': {{#jsonPath}}$.store.book{{/jsonPath}}[{{#jsonPathResult}}{{^-first}}, {{/-first}}'{{title}}'{{/jsonPathResult}}], 'bikeColor': '{{#jsonPath}}$.store.bicycle.color{{/jsonPath}}{{jsonPathResult}}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody(json("{" + NEW_LINE +
                               "    \"store\": {" + NEW_LINE +
                               "        \"book\": [" + NEW_LINE +
                               "            {" + NEW_LINE +
                               "                \"category\": \"reference\"," + NEW_LINE +
                               "                \"author\": \"Nigel Rees\"," + NEW_LINE +
                               "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
                               "                \"price\": 18.95" + NEW_LINE +
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
                               "}"));

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'titles': ['Sayings of the Century', 'Moby Dick'], 'bikeColor': 'red'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'titles': ['Sayings of the Century', 'Moby Dick'], 'bikeColor': 'red'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithJsonPathWithXmlBody() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'key': '{{#jsonPath}}$.store.book[0].title{{/jsonPath}}', 'value': '{{#jsonPath}}$.store.bicycle.color{{/jsonPath}}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>" + NEW_LINE +
                          "<root>" + NEW_LINE +
                          "  <store>" + NEW_LINE +
                          "    <book>" + NEW_LINE +
                          "      <category>reference</category>" + NEW_LINE +
                          "      <author>Nigel Rees</author>" + NEW_LINE +
                          "      <title>Sayings of the Century</title>" + NEW_LINE +
                          "      <price>18.95</price>" + NEW_LINE +
                          "    </book>" + NEW_LINE +
                          "    <book>" + NEW_LINE +
                          "      <category>fiction</category>" + NEW_LINE +
                          "      <author>Herman Melville</author>" + NEW_LINE +
                          "      <title>Moby Dick</title>" + NEW_LINE +
                          "      <isbn>0-553-21311-3</isbn>" + NEW_LINE +
                          "      <price>8.99</price>" + NEW_LINE +
                          "    </book>" + NEW_LINE +
                          "    <bicycle>" + NEW_LINE +
                          "      <color>red</color>" + NEW_LINE +
                          "      <price>19.95</price>" + NEW_LINE +
                          "    </bicycle>" + NEW_LINE +
                          "  </store>" + NEW_LINE +
                          "  <expensive>10</expensive>" + NEW_LINE +
                          "</root>");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'key': '', 'value': ''}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'key': '', 'value': ''}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateWithJsonPathWithStringBody() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'key': '{{#jsonPath}}$.store.book[0].title{{/jsonPath}}', 'value': '{{#jsonPath}}$.store.bicycle.color{{/jsonPath}}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_string_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'key': '', 'value': ''}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'key': '', 'value': ''}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateBinaryBody() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'body': '{{ request.body }}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body".getBytes(StandardCharsets.UTF_8));

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'body': 'c29tZV9ib2R5'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'body': 'c29tZV9ib2R5'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheForwardTemplateWithPathBodyParametersAndCookies() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'path': '{{ request.path }}'," + NEW_LINE +
            "    'body': \"{'queryStringParameters': '{{ request.queryStringParameters.nameOne.0 }},{{ request.queryStringParameters.nameTwo.0 }},{{ request.queryStringParameters.nameTwo.1 }}'," +
            " 'pathParameters': '{{ request.pathParameters.nameOne.0 }},{{ request.pathParameters.nameTwo.0 }},{{ request.pathParameters.nameTwo.1 }}'," +
            " 'cookies': '{{ request.cookies.session }}'," +
            " 'body': '{{ request.body }}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withQueryStringParameter("nameOne", "queryValueOne")
            .withQueryStringParameter("nameTwo", "queryValueTwoOne", "queryValueTwoTwo")
            .withPathParameter("nameOne", "pathValueOne")
            .withPathParameter("nameTwo", "pathValueTwoOne", "pathValueTwoTwo")
            .withMethod("POST")
            .withCookie("session", "some_session_id")
            .withBody("some_body");

        // when
        HttpRequest actualHttpRequest = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpRequestDTO.class);

        // then
        assertThat(actualHttpRequest, is(
            request()
                .withPath("/somePath")
                .withBody(
                    "{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'path' : \"/somePath\"," + NEW_LINE +
                                               "    'body': \"{'queryStringParameters': 'queryValueOne,queryValueTwoOne,queryValueTwoTwo', 'pathParameters': 'pathValueOne,pathValueTwoOne,pathValueTwoTwo', 'cookies': 'some_session_id', 'body': 'some_body'}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheTemplateWithDisallowedText() {
        String originalMustacheDisallowedText = configuration.mustacheDisallowedText();

        try {
            // given
            String template = "{" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': \"{'method': '{{ request.method }}', 'path': '{{ request.path }}', 'headers': '{{ request.headers.host.0 }}'}\"" + NEW_LINE +
                "}";
            HttpRequest request = request()
                .withPath("/somePath")
                .withMethod("POST")
                .withHeader(HOST.toString(), "mock-server.com")
                .withBody("some_body");

            // when
            HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

            // then
            assertThat(actualHttpResponse, is(
                response()
                    .withStatusCode(200)
                    .withBody("{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}")
            ));

            // when
            configuration.mustacheDisallowedText("request.method");

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"request.method\" in template:"));
        } finally {
            configuration.mustacheDisallowedText(originalMustacheDisallowedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheTemplateWithDisallowedTextList() {
        String originalMustacheDisallowedText = configuration.mustacheDisallowedText();

        try {
            // given
            String template = "{" + NEW_LINE +
                "    'statusCode': 200," + NEW_LINE +
                "    'body': \"{'method': '{{ request.method }}', 'path': '{{ request.path }}', 'headers': '{{ request.headers.host.0 }}'}\"" + NEW_LINE +
                "}";
            HttpRequest request = request()
                .withPath("/somePath")
                .withMethod("POST")
                .withHeader(HOST.toString(), "mock-server.com")
                .withBody("some_body");
            configuration.mustacheDisallowedText("request.method , request.path");

            // then
            Exception exception = assertThrows(RuntimeException.class, () -> new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"request.method\" in template:"));

            // when
            configuration.mustacheDisallowedText(" request.path   ,request.method");

            // then
            exception = assertThrows(RuntimeException.class, () -> new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class));
            assertThat(exception.getMessage(), containsString("Found disallowed string \"request.path\" in template:"));
        } finally {
            configuration.mustacheDisallowedText(originalMustacheDisallowedText);
        }
    }

    @Test
    public void shouldHandleHttpRequestsWithMustacheResponseTemplateInvalidFields() throws JsonProcessingException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'method': '{{ request.method.invalid }}', 'path': '{{ request.invalid }}', 'headers': '{{ invalid.headers.host.0 }}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body".getBytes(StandardCharsets.UTF_8));

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then
        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("{'method': '', 'path': '', 'headers': ''}")
        ));
        verify(mockServerLogger).logEvent(
            new LogEntry()
                .setType(TEMPLATE_GENERATED)
                .setLogLevel(INFO)
                .setHttpRequest(request)
                .setMessageFormat("generated output:{}from template:{}for request:{}")
                .setArguments(
                    OBJECT_MAPPER.readTree("" +
                                               "{" + NEW_LINE +
                                               "    'statusCode': 200," + NEW_LINE +
                                               "    'body': \"{'method': '', 'path': '', 'headers': ''}\"" + NEW_LINE +
                                               "}" + NEW_LINE),
                    template,
                    request
                )
        );
    }

    @Test
    public void shouldHandleInvalidMustacheTemplate() {
        // given
        String template = "{{ {{ }} }}";

        // when
        RuntimeException runtimeException = assertThrows(RuntimeException.class, () -> new MustacheTemplateEngine(mockServerLogger, configuration)
            .executeTemplate(template, request()
                                 .withPath("/someOtherPath")
                                 .withQueryStringParameter("queryParameter", "someValue")
                                 .withBody("some_body"),
                             HttpRequestDTO.class
            ));

        // then
        assertThat(
            runtimeException.getMessage(),
            allOf(
                containsString("Exception:" + NEW_LINE),
                containsString(" transforming template:" + NEW_LINE +
                                   "" + NEW_LINE +
                                   "  {{ {{ }} }}" + NEW_LINE +
                                   "" + NEW_LINE +
                                   " for request:" + NEW_LINE +
                                   "" + NEW_LINE +
                                   "  {" + NEW_LINE +
                                   "    \"path\" : \"/someOtherPath\"," + NEW_LINE +
                                   "    \"queryStringParameters\" : {" + NEW_LINE +
                                   "      \"queryParameter\" : [ \"someValue\" ]" + NEW_LINE +
                                   "    }," + NEW_LINE +
                                   "    \"body\" : \"some_body\"" + NEW_LINE +
                                   "  }" + NEW_LINE)
            )
        );
    }

    @Test
    public void shouldHandleResponseTemplateWithMustache() {
        String template = "{" + NEW_LINE +
            "    'statusCode': {{response.statusCode}}," + NEW_LINE +
            "    'body': \"path={{request.path}},originalBody={{response.body}}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/testPath")
            .withMethod("GET");
        HttpResponse httpResponse = response()
            .withStatusCode(200)
            .withBody("hello");

        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, httpResponse, HttpResponseDTO.class);

        assertThat(actualHttpResponse, is(
            response()
                .withStatusCode(200)
                .withBody("path=/testPath,originalBody=hello")
        ));
    }

    @Test
    public void shouldExposeFaker() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'firstName': '{{ faker.name.firstName }}', 'email': '{{ faker.internet.emailAddress }}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withBody("some_body");

        // when
        HttpResponse actualHttpResponse = new MustacheTemplateEngine(mockServerLogger, configuration).executeTemplate(template, request, HttpResponseDTO.class);

        // then — strong assertions: jmustache uses defaultValue(""), so a missing
        // variable silently renders as empty. Verify the faker actually produced
        // non-empty values for both providers rather than just verifying the template ran.
        String body = actualHttpResponse.getBodyAsString();
        assertThat(body, not(emptyString()));
        assertThat(body, not(containsString("{{")));
        assertThat("faker.name.firstName resolved to empty", body, not(containsString("'firstName': ''")));
        assertThat("faker.internet.emailAddress resolved to empty", body, not(containsString("'email': ''")));
        assertThat("email should contain @", body, containsString("@"));
    }

    @Test
    public void shouldProduceDeterministicFakerOutputWhenTemplateFakerSeedSet() {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'firstName': '{{ faker.name.firstName }}', 'email': '{{ faker.internet.emailAddress }}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request().withPath("/somePath");

        // when — two independent engines configured with the SAME non-zero seed
        HttpResponse responseA = new MustacheTemplateEngine(mockServerLogger, configuration().templateFakerSeed(4242L)).executeTemplate(template, request, HttpResponseDTO.class);
        HttpResponse responseB = new MustacheTemplateEngine(mockServerLogger, configuration().templateFakerSeed(4242L)).executeTemplate(template, request, HttpResponseDTO.class);
        // and one engine configured with a DIFFERENT non-zero seed
        HttpResponse responseC = new MustacheTemplateEngine(mockServerLogger, configuration().templateFakerSeed(9999L)).executeTemplate(template, request, HttpResponseDTO.class);
        String bodyA = responseA.getBodyAsString();
        String bodyB = responseB.getBodyAsString();
        String bodyC = responseC.getBodyAsString();

        // then — same seed reproduces byte-identical faker output; a different seed differs
        assertThat("same seed must reproduce identical faker output", bodyA, is(bodyB));
        assertThat("faker must have produced a value", bodyA, not(containsString("'firstName': ''")));
        assertThat("different seed should produce different faker output", bodyC, not(is(bodyA)));
    }

    @Test
    public void shouldHandleMultipleHttpRequestsWithMustacheResponseTemplateInParallel()
        throws InterruptedException, ExecutionException {
        // given
        String template = "{" + NEW_LINE +
            "    'statusCode': 200," + NEW_LINE +
            "    'body': \"{'method': '{{ request.method }}', 'path': '{{ request.path }}', 'headers': '{{ request.headers.host.0 }}'}\"" + NEW_LINE +
            "}";
        HttpRequest request = request()
            .withPath("/somePath")
            .withMethod("POST")
            .withHeader(HOST.toString(), "mock-server.com")
            .withBody("some_body");

        // when
        MustacheTemplateEngine mustacheTemplateEngine = new MustacheTemplateEngine(mockServerLogger, configuration);

        ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(30);

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            futures.add(newFixedThreadPool.submit(() -> {
                assertThat(mustacheTemplateEngine.executeTemplate(template, request, HttpResponseDTO.class), is(
                    response()
                        .withStatusCode(200)
                        .withBody("{'method': 'POST', 'path': '/somePath', 'headers': 'mock-server.com'}")
                ));
                return true;
            }));

        }

        for (Future<Boolean> future : futures) {
            future.get();
        }
        newFixedThreadPool.shutdown();
    }

    // ----- parsed-template cache regression tests -----
    // These prove a given template string is compiled once and the compiled Template is reused across
    // renders (rather than re-compiled per render), that distinct templates are cached separately, and
    // that the cache never changes rendered output or error handling.

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compiledTemplateCache(MustacheTemplateEngine engine) throws Exception {
        java.lang.reflect.Field field = MustacheTemplateEngine.class.getDeclaredField("compiledTemplates");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(engine);
    }

    @Test
    public void shouldReuseCompiledTemplateAcrossRepeatedRenders() throws Exception {
        // given
        MustacheTemplateEngine engine = new MustacheTemplateEngine(mockServerLogger, configuration);
        String template = "path={{request.path}}";
        HttpRequest request = request().withPath("/somePath");
        Map<String, Object> cache = compiledTemplateCache(engine);

        // when - render the same template twice
        String first = engine.renderTemplate(template, request);
        Object compiledAfterFirst = cache.get(template);
        String second = engine.renderTemplate(template, request);
        Object compiledAfterSecond = cache.get(template);

        // then - output is correct and stable, and the SAME compiled Template instance was reused (the
        // template was compiled exactly once, not re-compiled on the second render)
        assertThat(first, is("path=/somePath"));
        assertThat(second, is(first));
        assertThat(cache.size(), is(1));
        assertThat(compiledAfterFirst, notNullValue());
        assertThat(compiledAfterSecond, sameInstance(compiledAfterFirst));
    }

    @Test
    public void shouldReuseCompiledTemplateAcrossExecuteTemplate() throws Exception {
        // given - the executeTemplate path shares the same cache
        MustacheTemplateEngine engine = new MustacheTemplateEngine(mockServerLogger, configuration);
        String template = "{'statusCode': 200, 'body': '{{request.method}}'}";
        HttpRequest request = request().withPath("/somePath").withMethod("POST");
        Map<String, Object> cache = compiledTemplateCache(engine);

        // when
        engine.executeTemplate(template, request, HttpResponseDTO.class);
        Object compiledAfterFirst = cache.get(template);
        engine.executeTemplate(template, request, HttpResponseDTO.class);
        Object compiledAfterSecond = cache.get(template);

        // then - the compiled Template is cached once and reused
        assertThat(cache.size(), is(1));
        assertThat(compiledAfterSecond, sameInstance(compiledAfterFirst));
    }

    @Test
    public void shouldCacheDistinctTemplatesSeparately() throws Exception {
        // given
        MustacheTemplateEngine engine = new MustacheTemplateEngine(mockServerLogger, configuration);
        String templateA = "a={{request.path}}";
        String templateB = "b={{request.path}}";
        HttpRequest request = request().withPath("/somePath");
        Map<String, Object> cache = compiledTemplateCache(engine);

        // when - render two distinct templates
        String renderedA = engine.renderTemplate(templateA, request);
        String renderedB = engine.renderTemplate(templateB, request);

        // then - each distinct template has its own cached compiled Template (keyed by template string)
        assertThat(renderedA, is("a=/somePath"));
        assertThat(renderedB, is("b=/somePath"));
        assertThat(cache.size(), is(2));
        assertThat(cache.get(templateA), notNullValue());
        assertThat(cache.get(templateB), notNullValue());
        assertThat(cache.get(templateA), not(sameInstance(cache.get(templateB))));
    }

    @Test
    public void shouldNotCacheMalformedTemplateAndPreserveErrorHandling() throws Exception {
        // given - a malformed template (unterminated section) that fails to compile
        MustacheTemplateEngine engine = new MustacheTemplateEngine(mockServerLogger, configuration);
        String malformed = "{{#request.path}}unterminated";
        HttpRequest request = request().withPath("/somePath");
        Map<String, Object> cache = compiledTemplateCache(engine);

        // when / then - the compile failure surfaces as a RuntimeException (unchanged error handling)
        assertThrows(RuntimeException.class, () -> engine.renderTemplate(malformed, request));
        // and the malformed template is never cached
        assertThat(cache.containsKey(malformed), is(false));
    }

    @Test
    public void shouldEvictOldestWhenCacheOverflowsAndStillRenderCorrectly() throws Exception {
        // given - render more than PARSED_TEMPLATE_CACHE_MAX distinct templates so the bounded LRU evicts
        MustacheTemplateEngine engine = new MustacheTemplateEngine(mockServerLogger, configuration);
        HttpRequest request = request().withPath("/somePath");
        Map<String, Object> cache = compiledTemplateCache(engine);

        String firstTemplate = "tmpl-0={{request.path}}";
        assertThat(engine.renderTemplate(firstTemplate, request), is("tmpl-0=/somePath"));

        // when - overflow the cache with distinct templates
        int overflow = MustacheTemplateEngine.PARSED_TEMPLATE_CACHE_MAX + 50;
        for (int i = 1; i <= overflow; i++) {
            assertThat(engine.renderTemplate("tmpl-" + i + "={{request.path}}", request), is("tmpl-" + i + "=/somePath"));
        }

        // then - the cache is bounded and the evicted first template still re-compiles and renders correctly
        assertThat(cache.size(), is(MustacheTemplateEngine.PARSED_TEMPLATE_CACHE_MAX));
        assertThat(engine.renderTemplate(firstTemplate, request), is("tmpl-0=/somePath"));
    }

}
