package org.mockserver.testing.integration.mock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import org.apache.commons.io.IOUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.MatchType;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.HttpRequestSerializer;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.time.EpochService;
import org.mockserver.verify.VerificationTimes;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.matchers.Times.exactly;
import static org.mockserver.model.BinaryBody.binary;
import static org.mockserver.model.FileBody.file;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.Header.schemaHeader;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.*;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.JsonPathBody.jsonPath;
import static org.mockserver.model.JsonSchemaBody.jsonSchema;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.Parameter.schemaParam;
import static org.mockserver.model.ParameterBody.params;
import static org.mockserver.model.RegexBody.regex;
import static org.mockserver.model.StringBody.exact;
import static org.mockserver.model.StringBody.subString;
import static org.mockserver.model.XPathBody.xpath;
import static org.mockserver.model.XmlBody.xml;
import static org.mockserver.model.XmlSchemaBody.xmlSchema;
import static org.mockserver.model.XmlSchemaBody.xmlSchemaFromResource;

/**
 * @author jamesdbloom
 */
public abstract class AbstractExtendedMockingIntegrationTest extends AbstractBasicMockingSameJVMIntegrationTest {
    @BeforeClass
    public static void fixTime() {
        EpochService.fixedTimeGlobally(true);
    }

    @Test
    public void shouldReturnResponseForRequestInSsl() {
        // when
        mockServerClient.when(request().withSecure(true)).respond(response().withBody("some_body"));

        // then
        // - in http
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("")),
                getHeadersToRemove()
            )
        );
        // - in https
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseForRequestNotInSsl() {
        // when
        mockServerClient.when(request().withSecure(false)).respond(response().withBody("some_body"));

        // then
        // - in http
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("")),
                getHeadersToRemove()
            )
        );
        // - in https
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("")),
                getHeadersToRemove()
            )
        );
    }
    // shouldReturnResponseByMatchingPath — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByMatchingPathExactTimes — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByMatchingOptionalSchemaQueryStringParameter — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    @Test
    public void shouldReturnResponseByMatchingOptionalHeaderWithEitherOr() {
        // when
        mockServerClient
            .when(
                request()
                    .withHeader(schemaHeader(
                        "headerNameOne|headerNameTwo", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"^headerValue[A-z]{3}$\"" + NEW_LINE +
                            "}"
                    ))
                    .withHeader(schemaHeader(
                        "?headerNameOne", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"^headerValueO[a-z]{2}$\"" + NEW_LINE +
                            "}"
                    ))
                    .withHeader(schemaHeader(
                        "?headerNameTwo", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"^headerValueT[a-z]{2}$\"" + NEW_LINE +
                            "}"
                    ))
            )
            .respond(
                response()
                    .withStatusCode(200)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("headerNameTwo", "headerValueTwo"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne")
                    .withHeader("headerNameTwo", "headerValueTwo"),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerOtherValue"),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("headerNameTwo", "headerOtherValue"),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request(),
                getHeadersToRemove()
            )
        );
    }
    // shouldReturnResponseByMatchingHeaderNotPresent — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    @Test
    public void shouldReturnResponseByMatchingOptionalBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A σπίτι door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}").withOptional(true))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"extra ignored field\": \"some value\"," + NEW_LINE +
                        "    \"name\": \"A σπίτι door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST"),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A σπίτι door\"," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithSpaceDelimitedParameters() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(jsonSchema("{" + NEW_LINE +
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
                        "            \"maxItems\": 3," + NEW_LINE +
                        "            \"uniqueItems\": true" + NEW_LINE +
                        "        }" + NEW_LINE +
                        "    }," + NEW_LINE +
                        "    \"required\": [\"id\", \"name\", \"price\"]" + NEW_LINE +
                        "}").withParameterStyles(ImmutableMap.of("tags", ParameterStyle.SPACE_DELIMITED)))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .withBody("" +
                        "id=1" +
                        "&name=A+green+door" +
                        "&price=12.5" +
                        "&tags=home+green"),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withContentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .withBody("" +
                        "id=1" +
                        "&name=A+green+door" +
                        "&price=12.5" +
                        "&tags=home+green+door+book+expensive"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingOptionalParameterBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(params(param("bodyParameterName", "bodyParameterValue"))
                        .withOptional(true)
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(params(param("bodyParameterName", "bodyParameterValue"))),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    ),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(params(param("bodyOtherParameterName", "bodyOtherParameterValue"))),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingMultipleHeadersWithKeyMatchDefault() {
        // when
        mockServerClient
            .when(
                request()
                    .withHeader(schemaHeader(
                        "headerNameOne", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"^headerValueO[a-z]{2}$\"" + NEW_LINE +
                            "}"
                    ))
                    .withHeader(schemaHeader(
                        "headerNameTwo", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"^headerValueT[a-z]{2}$\"" + NEW_LINE +
                            "}"
                    ))
            )
            .respond(
                response()
                    .withStatusCode(200)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne")
                    .withHeader("headerNameTwo", "headerValueTwo"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne", "headerOtherValueOne")
                    .withHeader("headerNameTwo", "headerValueTwo"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne")
                    .withHeader("headerNameTwo", "headerValueTwo", "headerOtherValueTwo"),
                getHeadersToRemove()
            )
        );

        // then
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("headerNameTwo", "headerValueTwo"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request(),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingMultipleHeadersWithKeyMatchByKey() {
        // when
        mockServerClient
            .when(
                request()
                    .withHeaders(
                        new Headers(
                            schemaHeader(
                                "headerNameOne", "{" + NEW_LINE +
                                    "   \"type\": \"string\"," + NEW_LINE +
                                    "   \"pattern\": \"^headerValueO[a-z]{2}$\"" + NEW_LINE +
                                    "}"
                            ),
                            schemaHeader(
                                "headerNameTwo", "{" + NEW_LINE +
                                    "   \"type\": \"string\"," + NEW_LINE +
                                    "   \"pattern\": \"^headerValueT[a-z]{2}$\"" + NEW_LINE +
                                    "}"
                            )
                        ).withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY)
                    )
            )
            .respond(
                response()
                    .withStatusCode(200)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne")
                    .withHeader("headerNameTwo", "headerValueTwo"),
                getHeadersToRemove()
            )
        );

        // then - no match
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne", "headerOtherValueOne")
                    .withHeader("headerNameTwo", "headerValueTwo"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne")
                    .withHeader("headerNameTwo", "headerValueTwo", "headerOtherValueTwo"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("headerNameOne", "headerValueOne"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request()
                    .withHeader("headerNameTwo", "headerValueTwo"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            localNotFoundResponse(),
            makeRequest(
                request(),
                getHeadersToRemove()
            )
        );
    }
    // shouldReturnResponseByMatchingPathInOrderOfCreationExactTimes — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByMatchingPathInOrderOfCreationBeforeExpiry — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByMatchingPathInOrderOfPriorityExactTimes — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByMatchingPathInOrderOfPriorityWithNegativePriorities — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByMatchingPathInOrderOfPriorityWithPriorityUpdate — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByMatchingPathInOrderOfPriorityWithPriorityUpdateAndExactTimes — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByMatchingPathInOrderOfInsertionAfterUpdate — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldUpdateExistingExpectation — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseWhenTimeToLiveHasNotExpired — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    @Test
    public void shouldReturnMatchRequestWithBodyInUTF16() {
        // when
        String body = "我说中国话";
        mockServerClient
            .when(
                request()
                    .withBody(body, StandardCharsets.UTF_16)
            )
            .respond(
                response()
                    .withBody(body, StandardCharsets.UTF_8)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), MediaType.PLAIN_TEXT_UTF_8.toString())
                .withBody(body, MediaType.PLAIN_TEXT_UTF_8),
            makeRequest(
                request()
                    .withPath(calculatePath(""))
                    .withBody(body, StandardCharsets.UTF_16),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnMatchRequestWithBodyInUTF8WithContentTypeHeader() {
        // when
        String body = "我说中国话";
        mockServerClient
            .when(
                request()
                    .withHeader(CONTENT_TYPE.toString(), MediaType.PLAIN_TEXT_UTF_8.toString())
                    .withBody(body)
            )
            .respond(
                response()
                    .withBody(body, StandardCharsets.UTF_8)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), MediaType.PLAIN_TEXT_UTF_8.toString())
                .withBody(body, MediaType.PLAIN_TEXT_UTF_8),
            makeRequest(
                request()
                    .withHeader(CONTENT_TYPE.toString(), MediaType.PLAIN_TEXT_UTF_8.toString())
                    .withPath(calculatePath(""))
                    .withBody(body, StandardCharsets.UTF_8),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseWithBodyInUTF16() {
        // when
        String body = "我说中国话";
        mockServerClient
            .when(
                request()
                    .withHeader(CONTENT_TYPE.toString(), MediaType.create("text", "plain").withCharset(StandardCharsets.UTF_16).toString())
                    .withBody(body)
            )
            .respond(
                response()
                    .withHeader(CONTENT_TYPE.toString(), MediaType.create("text", "plain").withCharset(StandardCharsets.UTF_16).toString())
                    .withBody(body, StandardCharsets.UTF_16)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), MediaType.create("text", "plain").withCharset(StandardCharsets.UTF_16).toString())
                .withBody(body, StandardCharsets.UTF_16),
            makeRequest(
                request()
                    .withHeader(CONTENT_TYPE.toString(), MediaType.create("text", "plain").withCharset(StandardCharsets.UTF_16).toString())
                    .withPath(calculatePath(""))
                    .withBody(body),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseWithBodyInUTF8WithContentTypeHeader() {
        // when
        String body = "我说中国话";
        mockServerClient
            .when(
                request()
            )
            .respond(
                response()
                    .withHeader(CONTENT_TYPE.toString(), MediaType.PLAIN_TEXT_UTF_8.toString())
                    .withBody(body)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), MediaType.PLAIN_TEXT_UTF_8.toString())
                .withBody(body, MediaType.PLAIN_TEXT_UTF_8),
            makeRequest(
                request()
                    .withPath(calculatePath("")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseWithBodyInUTF8WithNoContentTypeHeader() {
        // when
        String body = "我说中国话";
        mockServerClient
            .when(
                request()
            )
            .respond(
                response()
                    .withBody(body, StandardCharsets.UTF_8)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), MediaType.PLAIN_TEXT_UTF_8.toString())
                .withBody(body, StandardCharsets.UTF_8),
            makeRequest(
                request()
                    .withPath(calculatePath("")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingSubStringBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(
                        subString("random")
                    ),
                exactly(2)
            )
            .respond(
                response()
                    .withBody("some_sub_string_body_response")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_sub_string_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withBody("some_random_body"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingNotRegexBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withBody(Body.not(regex("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")))
            )
            .respond(
                response()
                    .withBody("some_not_regex_body_response")
            );

        // then
        // should not match (because body matches regex)
        assertEquals(
            response()
                .withStatusCode(NOT_FOUND_404.code())
                .withReasonPhrase(NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withBody("10.2.3.123"),
                getHeadersToRemove()
            )
        );
        // should match (because body doesn't matches regex)
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_not_regex_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withBody("10.2.3.1234"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingNotSubStringBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withBody(Body.not(subString("some_body")))
            )
            .respond(
                response()
                    .withBody("some_not_regex_body_response")
            );

        // then
        // should not match (because body matches regex)
        assertEquals(
            response()
                .withStatusCode(NOT_FOUND_404.code())
                .withReasonPhrase(NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withBody("some_body_full_string"),
                getHeadersToRemove()
            )
        );
        // should match (because body doesn't matches regex)
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_not_regex_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withBody("some_other_body_full_string"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingNotExactBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withBody(Body.not(exact("some_body")))
            )
            .respond(
                response()
                    .withBody("some_not_regex_body_response")
            );

        // then
        // should not match (because body matches regex)
        assertEquals(
            response()
                .withStatusCode(NOT_FOUND_404.code())
                .withReasonPhrase(NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withBody("some_body"),
                getHeadersToRemove()
            )
        );
        // should match (because body doesn't matches regex)
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_not_regex_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withBody("some_other_body"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithXPath() {
        // when
        mockServerClient.when(request().withBody(xpath("/bookstore/book[price>30]/price")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(new StringBody("" +
                        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
                        "<bookstore>" + NEW_LINE +
                        "  <book category=\"COOKING\">" + NEW_LINE +
                        "    <title lang=\"en\">Everyday Italian</title>" + NEW_LINE +
                        "    <author>Giada De Laurentiis</author>" + NEW_LINE +
                        "    <year>2005</year>" + NEW_LINE +
                        "    <price>30.00</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "  <book category=\"CHILDREN\">" + NEW_LINE +
                        "    <title lang=\"en\">Harry Potter</title>" + NEW_LINE +
                        "    <author>J K. Rowling</author>" + NEW_LINE +
                        "    <year>2005</year>" + NEW_LINE +
                        "    <price>29.99</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "  <book category=\"WEB\">" + NEW_LINE +
                        "    <title lang=\"en\">Learning XML</title>" + NEW_LINE +
                        "    <author>Erik T. Ray</author>" + NEW_LINE +
                        "    <year>2003</year>" + NEW_LINE +
                        "    <price>31.95</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "</bookstore>")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithXmlSchema() {
        // when
        mockServerClient.when(request()
                .withBody(
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
                        "</xs:schema>")), exactly(2)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(NOT_FOUND_404.code())
                .withReasonPhrase(NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withBody("<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
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
                        "</notes>"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
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
                        "</notes>"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithXmlSchemaByClasspath() {
        // when
        mockServerClient.when(request()
                .withBody(
                    xmlSchemaFromResource("org/mockserver/model/testXmlSchema.xsd")), exactly(2)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(NOT_FOUND_404.code())
                .withReasonPhrase(NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withBody("<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
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
                        "</notes>"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("<?xml version=\"1.0\" encoding=\"utf-8\"?>" + NEW_LINE +
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
                        "</notes>"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithXmlWithSpecialCharactersDefaultingToUTF8() {
        // when
        mockServerClient.when(request().withBody(xml("" +
            "<bookstore>" + NEW_LINE +
            "  <book nationality=\"ITALIAN\" category=\"COOKING\"><title lang=\"en\">Everyday Italian</title><author>ÄÑçîüÏ</author><year>2005</year><price>30.00</price></book>" + NEW_LINE +
            "</bookstore>")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(new StringBody("" +
                        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
                        "<bookstore>" + NEW_LINE +
                        "  <book category=\"COOKING\" nationality=\"ITALIAN\">" + NEW_LINE +
                        "    <title lang=\"en\">Everyday Italian</title>" + NEW_LINE +
                        "    <author>ÄÑçîüÏ</author>" + NEW_LINE +
                        "    <year>2005</year>" + NEW_LINE +
                        "    <price>30.00</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "</bookstore>")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithXmlWithSpecialCharactersAndCharset() {

        // when
        mockServerClient
            .when(
                request()
                    .withBody(
                        xml(
                            "" +
                                "<bookstore>" + NEW_LINE +
                                "  <book nationality=\"ITALIAN\" category=\"COOKING\"><title>Everyday Italian</title><author>我说中国话</author></book>" + NEW_LINE +
                                "</bookstore>",
                            StandardCharsets.UTF_8
                        )
                    ),
                exactly(2)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withHeader("Content-Type", "application/xml; charset=utf-8")
                    .withBody(new StringBody("" +
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
                        "<bookstore>" + NEW_LINE +
                        "  <book category=\"COOKING\" nationality=\"ITALIAN\">" + NEW_LINE +
                        "    <title>Everyday Italian</title>" + NEW_LINE +
                        "    <author>我说中国话</author>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "</bookstore>")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithXmlWithSpecialCharactersClientCharsetDifferent() {

        // when
        mockServerClient
            .when(
                request()
                    .withBody(
                        xml(
                            "" +
                                "<bookstore>" + NEW_LINE +
                                "  <book nationality=\"ITALIAN\" category=\"COOKING\"><title>Everyday Italian</title><author>ÄÑçîüÏ</author></book>" + NEW_LINE +
                                "</bookstore>",
                            StandardCharsets.UTF_8
                        )
                    ),
                exactly(2)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withHeader("Content-Type", "application/xml; charset=" + StandardCharsets.ISO_8859_1.name())
                    .withBody(binary(("" +
                        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
                        "<bookstore>" + NEW_LINE +
                        "  <book category=\"COOKING\" nationality=\"ITALIAN\">" + NEW_LINE +
                        "    <title>Everyday Italian</title>" + NEW_LINE +
                        "    <author>ÄÑçîüÏ</author>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "</bookstore>").getBytes(StandardCharsets.ISO_8859_1))),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnXmlResponseWithUTF8() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody("some_body"),
                exactly(2)
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody(xml("" +
                        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
                        "<bookstore>" + NEW_LINE +
                        "  <book category=\"COOKING\" nationality=\"ITALIAN\">" + NEW_LINE +
                        "    <title>Everyday Italian</title>" + NEW_LINE +
                        "    <author>ÄÑçîüÏ</author>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "</bookstore>")
                    )
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withHeader("content-type", "application/xml; charset=utf-8")
                .withBody(xml("" +
                    "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
                    "<bookstore>" + NEW_LINE +
                    "  <book category=\"COOKING\" nationality=\"ITALIAN\">" + NEW_LINE +
                    "    <title>Everyday Italian</title>" + NEW_LINE +
                    "    <author>ÄÑçîüÏ</author>" + NEW_LINE +
                    "  </book>" + NEW_LINE +
                    "</bookstore>")
                ),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("some_body"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonWithSpecialCharactersDefaultingToUTF8() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A σπίτι door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}")),
                exactly(2)
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"extra ignored field\": \"some value\"," + NEW_LINE +
                        "    \"name\": \"A σπίτι door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonAsRawBody() {
        // when
        HttpResponse httpResponse = makeRequest(
            request()
                .withPath(calculatePath("mockserver/expectation"))
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withBody("{" + NEW_LINE +
                    "  \"httpRequest\" : {" + NEW_LINE +
                    "    \"body\" : {" + NEW_LINE +
                    "        \"id\": 1," + NEW_LINE +
                    "        \"name\": \"A green door\"," + NEW_LINE +
                    "        \"price\": 12.50," + NEW_LINE +
                    "        \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                    "    }" + NEW_LINE +
                    "  }," + NEW_LINE +
                    "  \"httpResponse\" : {" + NEW_LINE +
                    "    \"body\" : {" + NEW_LINE +
                    "        \"id\": 1," + NEW_LINE +
                    "        \"name\": \"A green door\"," + NEW_LINE +
                    "        \"price\": 12.50," + NEW_LINE +
                    "        \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                    "    }" + NEW_LINE +
                    "  }" + NEW_LINE +
                    "}"),
            getHeadersToRemove()
        );
        assertThat(httpResponse.getStatusCode(), equalTo(201));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString())
                .withBody(json("{" + NEW_LINE +
                    "  \"id\" : 1," + NEW_LINE +
                    "  \"name\" : \"A green door\"," + NEW_LINE +
                    "  \"price\" : 12.5," + NEW_LINE +
                    "  \"tags\" : [ \"home\", \"green\" ]" + NEW_LINE +
                    "}")),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("{" + NEW_LINE +
                        "  \"id\" : 1," + NEW_LINE +
                        "  \"name\" : \"A green door\"," + NEW_LINE +
                        "  \"price\" : 12.5," + NEW_LINE +
                        "  \"tags\" : [ \"home\", \"green\" ]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonWithBlankFields() {
        // when
        HttpResponse httpResponse = makeRequest(
            request()
                .withPath(calculatePath("mockserver/expectation"))
                .withMethod("PUT")
                .withSecure(isSecureControlPlane())
                .withBody("{" + NEW_LINE +
                    "  \"httpRequest\" : {" + NEW_LINE +
                    "    \"body\" : {" + NEW_LINE +
                    "        \"id\": 1," + NEW_LINE +
                    "        \"name\": \"\"," + NEW_LINE +
                    "        \"price\": 0," + NEW_LINE +
                    "        \"null\": null," + NEW_LINE +
                    "        \"tags\": []" + NEW_LINE +
                    "    }" + NEW_LINE +
                    "  }," + NEW_LINE +
                    "  \"httpResponse\" : {" + NEW_LINE +
                    "    \"body\" : {" + NEW_LINE +
                    "        \"id\": 1," + NEW_LINE +
                    "        \"name\": \"\"," + NEW_LINE +
                    "        \"price\": 0," + NEW_LINE +
                    "        \"null\": null," + NEW_LINE +
                    "        \"tags\": []" + NEW_LINE +
                    "    }" + NEW_LINE +
                    "  }" + NEW_LINE +
                    "}"),
            getHeadersToRemove()
        );
        assertThat(httpResponse.getStatusCode(), equalTo(201));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), MediaType.APPLICATION_JSON_UTF_8.toString())
                .withBody(json("{" + NEW_LINE +
                    "  \"id\" : 1," + NEW_LINE +
                    "  \"name\" : \"\"," + NEW_LINE +
                    "  \"price\" : 0," + NEW_LINE +
                    "  \"null\" : null," + NEW_LINE +
                    "  \"tags\" : [ ]" + NEW_LINE +
                    "}")),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(json("{" + NEW_LINE +
                        "  \"id\" : 1," + NEW_LINE +
                        "  \"name\" : \"\"," + NEW_LINE +
                        "  \"price\" : 0," + NEW_LINE +
                        "  \"null\" : null," + NEW_LINE +
                        "  \"tags\" : [ ]" + NEW_LINE +
                        "}")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonWithCharsetUTF16() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"ταυτότητα\": 1," + NEW_LINE +
                        "    \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "    \"τιμή\": 12.50," + NEW_LINE +
                        "    \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}", StandardCharsets.UTF_16, MatchType.ONLY_MATCHING_FIELDS)),
                exactly(2)
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(json("{" + NEW_LINE +
                        "    \"ταυτότητα\": 1," + NEW_LINE +
                        "    \"επιπλέον αγνοούνται τομέα\": \"κάποια αξία\"," + NEW_LINE +
                        "    \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "    \"τιμή\": 12.50," + NEW_LINE +
                        "    \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}", StandardCharsets.UTF_16, MatchType.ONLY_MATCHING_FIELDS)),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonWithContentTypeHeaderAndCharsetUTF16() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"ταυτότητα\": 1," + NEW_LINE +
                        "    \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "    \"τιμή\": 12.50," + NEW_LINE +
                        "    \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}", StandardCharsets.UTF_16, MatchType.ONLY_MATCHING_FIELDS)),
                exactly(2)
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withHeader(CONTENT_TYPE.toString(), MediaType.create("text", "plain").withCharset(StandardCharsets.UTF_16).toString())
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(json("{" + NEW_LINE +
                        "    \"ταυτότητα\": 1," + NEW_LINE +
                        "    \"επιπλέον αγνοούνται τομέα\": \"κάποια αξία\"," + NEW_LINE +
                        "    \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "    \"τιμή\": 12.50," + NEW_LINE +
                        "    \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}", StandardCharsets.UTF_16)),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonWithUTF8() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"ταυτότητα\": 1," + NEW_LINE +
                        "    \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "    \"τιμή\": 12.50," + NEW_LINE +
                        "    \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}", MatchType.ONLY_MATCHING_FIELDS)),
                exactly(2)
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(json("{" + NEW_LINE +
                        "    \"ταυτότητα\": 1," + NEW_LINE +
                        "    \"επιπλέον αγνοούνται τομέα\": \"κάποια αξία\"," + NEW_LINE +
                        "    \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "    \"τιμή\": 12.50," + NEW_LINE +
                        "    \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}", StandardCharsets.UTF_8)),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonWithNoCharset() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"ταυτότητα\": 1," + NEW_LINE +
                        "    \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "    \"τιμή\": 12.50," + NEW_LINE +
                        "    \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}", MatchType.ONLY_MATCHING_FIELDS)),
                exactly(2)
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(json("{" + NEW_LINE +
                        "    \"ταυτότητα\": 1," + NEW_LINE +
                        "    \"επιπλέον αγνοούνται τομέα\": \"κάποια αξία\"," + NEW_LINE +
                        "    \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "    \"τιμή\": 12.50," + NEW_LINE +
                        "    \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnJsonResponseWithJsonWithUTF8() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody("some_body"),
                exactly(2)
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody(json("{" + NEW_LINE +
                        "  \"ταυτότητα\": 1," + NEW_LINE +
                        "  \"όνομα\": \"μια πράσινη πόρτα\"," + NEW_LINE +
                        "  \"τιμή\": 12.50," + NEW_LINE +
                        "  \"ετικέτες\": [\"σπίτι\", \"πράσινος\"]" + NEW_LINE +
                        "}")
                    )
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withHeader("content-type", "application/json; charset=utf-8")
                .withBody(json("{" + NEW_LINE +
                    "  \"ταυτότητα\" : 1," + NEW_LINE +
                    "  \"όνομα\" : \"μια πράσινη πόρτα\"," + NEW_LINE +
                    "  \"τιμή\" : 12.5," + NEW_LINE +
                    "  \"ετικέτες\" : [ \"σπίτι\", \"πράσινος\" ]" + NEW_LINE +
                    "}")),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("some_body"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonWithMatchType() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}", MatchType.ONLY_MATCHING_FIELDS)),
                exactly(2)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"extra field\": \"some value\"," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonSchema() {
        // when
        mockServerClient.when(request().withBody(jsonSchema("{" + NEW_LINE +
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
            "}")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingBodyWithJsonPath() {
        // when
        mockServerClient.when(request().withBody(jsonPath("$..book[?(@.price <= $['expensive'])]")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(new StringBody("" +
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
                        "}")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void shouldReturnPDFResponseByMatchingPath() throws IOException {
        // when
        byte[] pdfBytes = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream("test.pdf"));
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("ws/rest/user/[0-9]+/document/[0-9]+\\.pdf"))
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withReasonPhrase(OK_200.reasonPhrase())
                    .withHeaders(
                        header(CONTENT_TYPE.toString(), MediaType.PDF.toString()),
                        header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.pdf\"; filename=\"test.pdf\""),
                        header(CACHE_CONTROL.toString(), "must-revalidate, post-check=0, pre-check=0")
                    )
                    .withBody(binary(pdfBytes))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.pdf\"; filename=\"test.pdf\""),
                    header(CACHE_CONTROL.toString(), "must-revalidate, post-check=0, pre-check=0"),
                    header(CONTENT_TYPE.toString(), MediaType.PDF.toString())
                )
                .withBody(binary(pdfBytes, MediaType.PDF)),
            makeRequest(
                request()
                    .withPath(calculatePath("ws/rest/user/1/document/2.pdf"))
                    .withMethod("GET"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void shouldReturnPNGResponseByMatchingPath() throws IOException {
        // when
        byte[] pngBytes = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream("test.png"));
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("ws/rest/user/[0-9]+/icon/[0-9]+\\.png"))
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withReasonPhrase(OK_200.reasonPhrase())
                    .withHeaders(
                        header(CONTENT_TYPE.toString(), MediaType.PNG.toString()),
                        header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.png\"; filename=\"test.png\"")
                    )
                    .withBody(binary(pngBytes))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.png\"; filename=\"test.png\""),
                    header(CONTENT_TYPE.toString(), MediaType.PNG.toString())
                )
                .withBody(binary(pngBytes, MediaType.PNG)),
            makeRequest(
                request()
                    .withPath(calculatePath("ws/rest/user/1/icon/1.png"))
                    .withMethod("GET"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void shouldReturnVerbatimBinaryFileBodyResponse() throws IOException {
        // when - the response body is a FILE (a classpath filePath) with NO template engine: MockServer must
        // read the file and serve its raw bytes verbatim over the wire, not emit the filePath string (#2450).
        // Runs over a real socket for every transport, and over a deployed servlet for the WAR suite (which
        // inherits this abstract test), covering the WAR/servlet path.
        byte[] pngBytes = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream("test.png"));
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("ws/rest/user/[0-9]+/verbatim/[0-9]+\\.png"))
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withReasonPhrase(OK_200.reasonPhrase())
                    .withHeaders(
                        header(CONTENT_TYPE.toString(), MediaType.PNG.toString()),
                        header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.png\"; filename=\"test.png\"")
                    )
                    .withBody(file("test.png", MediaType.PNG))
            );

        // then - the client receives the exact file bytes, not the "test.png" path string
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.png\"; filename=\"test.png\""),
                    header(CONTENT_TYPE.toString(), MediaType.PNG.toString())
                )
                .withBody(binary(pngBytes, MediaType.PNG)),
            makeRequest(
                request()
                    .withPath(calculatePath("ws/rest/user/1/verbatim/1.png"))
                    .withMethod("GET"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void shouldReturnPDFResponseByMatchingBinaryPDFBody() throws IOException {
        // when
        byte[] pdfBytes = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream("test.pdf"));
        mockServerClient
            .when(
                request()
                    .withBody(binary(pdfBytes, MediaType.PDF))
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withReasonPhrase(OK_200.reasonPhrase())
                    .withHeaders(
                        header(CONTENT_TYPE.toString(), MediaType.PDF.toString()),
                        header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.pdf\"; filename=\"test.pdf\""),
                        header(CACHE_CONTROL.toString(), "must-revalidate, post-check=0, pre-check=0")
                    )
                    .withBody(binary(pdfBytes))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.pdf\"; filename=\"test.pdf\""),
                    header(CACHE_CONTROL.toString(), "must-revalidate, post-check=0, pre-check=0"),
                    header(CONTENT_TYPE.toString(), MediaType.PDF.toString())
                )
                .withBody(binary(pdfBytes, MediaType.PDF)),
            makeRequest(
                request()
                    .withPath(calculatePath("ws/rest/user/1/document/2.pdf"))
                    .withBody(binary(pdfBytes, MediaType.PDF))
                    .withMethod("POST"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    public void shouldReturnPNGResponseByMatchingBinaryPNGBody() throws IOException {
        // when
        byte[] pngBytes = IOUtils.toByteArray(getClass().getClassLoader().getResourceAsStream("test.png"));
        mockServerClient
            .when(
                request()
                    .withBody(binary(pngBytes, MediaType.ANY_IMAGE_TYPE))
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withReasonPhrase(OK_200.reasonPhrase())
                    .withHeaders(
                        header(CONTENT_TYPE.toString(), MediaType.PNG.toString()),
                        header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.png\"; filename=\"test.png\"")
                    )
                    .withBody(binary(pngBytes))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header(CONTENT_DISPOSITION.toString(), "form-data; name=\"test.png\"; filename=\"test.png\""),
                    header(CONTENT_TYPE.toString(), MediaType.PNG.toString())
                )
                .withBody(binary(pngBytes, MediaType.PNG)),
            makeRequest(
                request()
                    .withPath(calculatePath("ws/rest/user/1/icon/1.png"))
                    .withBody(binary(pngBytes, MediaType.ANY_IMAGE_TYPE))
                    .withMethod("POST"),
                getHeadersToRemove()
            )
        );
    }
    // shouldReturnResponseByNotMatchingPathWithNotOperator — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldReturnResponseByNotMatchingMethodWithNotOperator — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    @Test
    public void shouldReturnResponseByMatchingPathAndMethodAndBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody("some_bodyRequest")
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withHeaders(header("headerNameResponse", "headerValueResponse"))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathAndMethodAndQueryStringParameters() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathAndMethodAndHeaders() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("requestHeaderNameOne", "requestHeaderValueOne_One", "requestHeaderValueOne_Two"),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("requestHeaderNameOne", "requestHeaderValueOne_One", "requestHeaderValueOne_Two"),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    )
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathAndMethodAndCookies() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie("requestCookieNameOne", "requestCookieValueOne"),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withCookies(
                        cookie("responseCookieNameOne", "responseCookieValueOne"),
                        cookie("responseCookieNameTwo", "responseCookieValueTwo")
                    )
            );

        // then
        // - cookie objects
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(
                    cookie("responseCookieNameOne", "responseCookieValueOne"),
                    cookie("responseCookieNameTwo", "responseCookieValueTwo")
                )
                .withHeaders(
                    header(SET_COOKIE.toString(), "responseCookieNameOne=responseCookieValueOne", "responseCookieNameTwo=responseCookieValueTwo")
                ),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("headerNameRequest", "headerValueRequest"),
                        header(CONTENT_TYPE.toString(), MediaType.create("text", "plain").toString())
                    )
                    .withCookies(
                        cookie("requestCookieNameOne", "requestCookieValueOne"),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
        // - cookie header
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(
                    cookie("responseCookieNameOne", "responseCookieValueOne"),
                    cookie("responseCookieNameTwo", "responseCookieValueTwo")
                )
                .withHeaders(
                    header(SET_COOKIE.toString(), "responseCookieNameOne=responseCookieValueOne", "responseCookieNameTwo=responseCookieValueTwo")
                ),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("headerNameRequest", "headerValueRequest"),
                        header("Cookie", "requestCookieNameOne=requestCookieValueOne; requestCookieNameTwo=requestCookieValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathAndMethodAndQueryStringParametersAndBodyParameters() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(params(param("bodyParameterName", "bodyParameterValue")))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        // - in http - url query string
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(params(param("bodyParameterName", "bodyParameterValue"))),
                getHeadersToRemove()
            )
        );
        // - in https - query string parameter objects
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(params(param("bodyParameterName", "bodyParameterValue"))),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathAndMethodAndQueryStringParametersAndBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withHeaders(header("headerNameResponse", "headerValueResponse"))
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        // - in http - url query string
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // - in http - query string parameter objects
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // - in https - url string and query string parameter objects
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathAndMethodAndBodyParameters() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withHeaders(header("headerNameResponse", "headerValueResponse"))
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        // - in http - body string
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterOneName=Parameter+One+Value+One" +
                        "&bodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // - in http - body string - different order
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterTwoName=Parameter+Two" +
                        "&bodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterOneName=Parameter+One+Value+One"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // - in http - body parameter objects
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // - in http - body parameter objects - different order
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterTwoName", "Parameter Two"),
                        param("bodyParameterOneName", "Parameter One Value Two", "Parameter One Value One")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathAndMethodAndParametersAndHeadersAndCookies() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("PUT")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterOneName=Parameter+One+Value+One" +
                        "&bodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest"))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withHeaders(header("headerNameResponse", "headerValueResponse"))
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        // - body string
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("PUT")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterOneName=Parameter+One+Value+One" +
                        "&bodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(
                        header("headerNameRequest", "headerValueRequest"),
                        header("Cookie", "cookieNameRequest=cookieValueRequest")
                    ),
                getHeadersToRemove()
            )
        );
        // - body parameter objects
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")
                .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
                .withHeaders(
                    header("headerNameResponse", "headerValueResponse"),
                    header(SET_COOKIE.toString(), "cookieNameResponse=cookieValueResponse")
                ),
            makeRequest(
                request()
                    .withMethod("PUT")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingBodyParameterWithNotOperatorForName() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param(not("bodyParameterOneName"), string("Parameter One Value One"), string("Parameter One Value Two")),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("OTHERBodyParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("OTHERBodyParameterOneName=Parameter+One+Value+One" +
                        "&OTHERBodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingBodyParameterWithNotOperatorForValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param(string("bodyParameterOneName"), string("!Parameter One Value One"), not("Parameter One Value Two")),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter first value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Other Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter first value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterOneName=Other+Parameter+One+Value+One" +
                        "&bodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter second value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Parameter One Value One", "Other Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter second value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterOneName=Parameter+One+Value+One" +
                        "&bodyParameterOneName=Other+Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong body parameter values
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Other Parameter One Value One", "Other Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong body parameter values
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterOneName=Other+Parameter+One+Value+One" +
                        "&bodyParameterOneName=Other+Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingQueryStringParameterWithNotOperatorForNameAndValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param(string("queryStringParameterOneName"), not("Parameter One Value One"), string("!Parameter One Value Two")),
                        param("queryStringParameterTwoName", "Parameter Two")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "Other Parameter One Value One", "Parameter One Value Two"),
                        param("queryStringParameterTwoName", "Parameter Two")
                    ),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "Parameter One Value One", "Other Parameter One Value Two"),
                        param("queryStringParameterTwoName", "Parameter Two")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingQueryStringParameterWithNotOperatorForName() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param(not("queryStringParameterOneName"), string("Parameter One Value One"), string("Parameter One Value Two")),
                        param("queryStringParameterTwoName", "Parameter Two")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("OTHERQueryStringParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("queryStringParameterTwoName", "Parameter Two")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingQueryStringParameterWithNotOperatorForValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param(string("queryStringParameterOneName"), not("Parameter One Value One"), string("!Parameter One Value Two")),
                        param("queryStringParameterTwoName", "Parameter Two")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "Other Parameter One Value One", "Other Parameter One Value Two"),
                        param("queryStringParameterTwoName", "Parameter Two")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingCookieWithNotOperatorForNameAndValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie("requestCookieNameOne", "!requestCookieValueOne"),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string cookie value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie("requestCookieNameOne", "OTHERrequestCookieValueOne"),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingCookieWithNotOperatorForName() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie(not("requestCookieNameOne"), string("requestCookieValueOne")),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string cookie name
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie("OTHERrequestCookieNameOne", "requestCookieValueOne"),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingCookieWithNotOperatorForValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie(string("requestCookieNameOne"), not("requestCookieValueOne")),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string cookie value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie("requestCookieNameOne", "OTHERrequestCookieValueOne"),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingHeaderWithNotOperatorForNameAndValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header(not("requestHeaderNameOne"), not("requestHeaderValueOne")),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string header name
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("OTHERrequestHeaderNameOne", "requestHeaderValueOne"),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
        // wrong query string header value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("OTHERrequestHeaderNameOne", "OTHERrequestHeaderValueOne"),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingHeaderWithNotOperatorForName() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header(not("requestHeaderNameOne"), string("requestHeaderValueOne")),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string header name
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("OTHERrequestHeaderNameOne", "requestHeaderValueOne"),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByNotMatchingHeaderWithNotOperatorForValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header(string("requestHeaderNameOne"), not("requestHeaderValueOne")),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string header value
        assertEquals(
            response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response"),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("requestHeaderNameOne", "OTHERrequestHeaderValueOne"),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }
    // shouldNotReturnResponseForWhenTimeToLiveExpired — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    @Test
    public void shouldNotReturnResponseForMatchingBodyWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(Not.not(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"))),
                exactly(2)
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"extra_ignored_field\": \"some value\"," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingXPathBody() {
        // when
        mockServerClient.when(request().withBody(new XPathBody("/bookstore/book[price>35]/price")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(new StringBody("" +
                        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
                        "<bookstore>" + NEW_LINE +
                        "  <book category=\"COOKING\">" + NEW_LINE +
                        "    <title lang=\"en\">Everyday Italian</title>" + NEW_LINE +
                        "    <author>Giada De Laurentiis</author>" + NEW_LINE +
                        "    <year>2005</year>" + NEW_LINE +
                        "    <price>30.00</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "  <book category=\"CHILDREN\">" + NEW_LINE +
                        "    <title lang=\"en\">Harry Potter</title>" + NEW_LINE +
                        "    <author>J K. Rowling</author>" + NEW_LINE +
                        "    <year>2005</year>" + NEW_LINE +
                        "    <price>29.99</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "  <book category=\"WEB\">" + NEW_LINE +
                        "    <title lang=\"en\">Learning XML</title>" + NEW_LINE +
                        "    <author>Erik T. Ray</author>" + NEW_LINE +
                        "    <year>2003</year>" + NEW_LINE +
                        "    <price>31.95</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "</bookstore>")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingXmlBody() {
        // when
        mockServerClient.when(request().withBody(xml("" +
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
            "<bookstore>" + NEW_LINE +
            "  <book category=\"COOKING\" nationality=\"ITALIAN\">" + NEW_LINE +
            "    <title lang=\"en\">Everyday Italian</title>" + NEW_LINE +
            "    <author>Giada De Laurentiis</author>" + NEW_LINE +
            "    <year>2005</year>" + NEW_LINE +
            "    <price>30.00</price>" + NEW_LINE +
            "  </book>" + NEW_LINE +
            "</bookstore>")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(new StringBody("" +
                        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
                        "<bookstore>" + NEW_LINE +
                        "  <book category=\"COOKING\">" + NEW_LINE +
                        "    <title lang=\"en\">Everyday Italian</title>" + NEW_LINE +
                        "    <author>Giada De Laurentiis</author>" + NEW_LINE +
                        "    <year>2005</year>" + NEW_LINE +
                        "    <price>30.00</price>" + NEW_LINE +
                        "  </book>" + NEW_LINE +
                        "</bookstore>")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingJsonBody() {
        // when
        mockServerClient.when(request().withBody(json("{" + NEW_LINE +
            "    \"id\": 1," + NEW_LINE +
            "    \"name\": \"A green door\"," + NEW_LINE +
            "    \"price\": 12.50," + NEW_LINE +
            "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
            "}")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"---- XXXX WRONG VALUE XXXX ----\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingJsonBodyWithMatchType() {
        // when
        mockServerClient
            .when(
                request()
                    .withBody(json("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}", MatchType.STRICT)),
                exactly(2))
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"extra field\": \"some value\"," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingJsonSchema() {
        // when
        mockServerClient.when(request().withBody(jsonSchema("{" + NEW_LINE +
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
            "}")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"wrong field name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingJsonPathBody() {
        // when
        mockServerClient.when(request().withBody(new JsonPathBody("$..book[?(@.price > $['expensive'])]")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path"))
                    .withMethod("POST")
                    .withBody(new StringBody("" +
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
                        "}")),
                getHeadersToRemove()
            )
        );
    }
    // shouldNotReturnResponseForMatchingPathWithNotOperator — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldNotReturnResponseForMatchingMethodWithNotOperator — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    @Test
    public void shouldNotReturnResponseForNonMatchingBodyParameterName() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withHeaders(header("headerNameResponse", "headerValueResponse"))
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("OTHERBodyParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("OTHERBodyParameterOneName=Parameter+One+Value+One" +
                        "&bodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForMatchingBodyParameterWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param(not("bodyParameterOneName"), not("Parameter One Value One"), not("Parameter One Value Two")),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterOneName=Other Parameter+One+Value+One" +
                        "&bodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingBodyParameterValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withHeaders(header("headerNameResponse", "headerValueResponse"))
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        // wrong body parameter value
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(params(
                        param("bodyParameterOneName", "Other Parameter One Value One", "Parameter One Value Two"),
                        param("bodyParameterTwoName", "Parameter Two")
                    ))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
        // wrong body parameter value
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withBody(new StringBody("bodyParameterOneName=Other Parameter+One+Value+One" +
                        "&bodyParameterOneName=Parameter+One+Value+Two" +
                        "&bodyParameterTwoName=Parameter+Two"))
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingQueryStringParameterName() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withHeaders(header("headerNameResponse", "headerValueResponse"))
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("OTHERQueryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
                    .withHeaders(
                        header("headerNameRequest", "headerValueRequest")
                    )
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingQueryStringParameterValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
                    .withHeaders(header("headerNameResponse", "headerValueResponse"))
                    .withCookies(cookie("cookieNameResponse", "cookieValueResponse"))
            );

        // then
        // wrong query string parameter value
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "OTHERqueryStringParameterOneValueOne", "queryStringParameterOneValueTwo"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody("some_bodyRequest")
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForMatchingQueryStringParameterWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param(not("queryStringParameterOneName"), not("Parameter One Value One"), not("Parameter One Value Two")),
                        param("queryStringParameterTwoName", "Parameter Two")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("queryStringParameterTwoName", "Parameter Two")
                    ),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_pathRequest"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "Parameter One Value One", "Parameter One Value Two"),
                        param("queryStringParameterTwoName", "Parameter Two")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingCookieName() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_path"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(exact("some_body"))
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue"))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue"))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_path"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(exact("some_body"))
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieOtherName", "cookieValue")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingCookieValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_path"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(exact("some_body"))
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue"))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue"))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_path"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(exact("some_body"))
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieOtherValue")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForMatchingCookieWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie(not("requestCookieNameOne"), not("requestCookieValueOne")),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie("requestCookieNameOne", "requestCookieValueOne"),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withCookies(
                        cookie("requestCookieNameOne", "requestCookieValueOne"),
                        cookie("requestCookieNameTwo", "requestCookieValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingHeaderName() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_path"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(exact("some_body"))
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue"))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue"))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_path"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(exact("some_body"))
                    .withHeaders(header("headerOtherName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForNonMatchingHeaderValue() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_path"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(exact("some_body"))
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue"))
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body")
                    .withHeaders(header("headerName", "headerValue"))
                    .withCookies(cookie("cookieName", "cookieValue"))
            );

        // then
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_path"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withBody(exact("some_body"))
                    .withHeaders(header("headerName", "headerOtherValue"))
                    .withCookies(cookie("cookieName", "cookieValue")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldNotReturnResponseForMatchingHeaderWithNotOperator() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header(string("requestHeaderNameOne"), not("requestHeaderValueOne")),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    )
            )
            .respond(
                response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("requestHeaderNameOne", "requestHeaderValueOne"),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
        // wrong query string parameter name
        assertEquals(
            response()
                .withStatusCode(HttpStatusCode.NOT_FOUND_404.code())
                .withReasonPhrase(HttpStatusCode.NOT_FOUND_404.reasonPhrase()),
            makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("some_pathRequest"))
                    .withHeaders(
                        header("requestHeaderNameOne", "requestHeaderValueOne"),
                        header("requestHeaderNameTwo", "requestHeaderValueTwo")
                    ),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldVerifyReceivedRequestInSsl() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some.*path")), exactly(2)
            )
            .respond(
                response()
                    .withBody("some_body")
            );

        // then
        // - in http
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        mockServerClient
            .verify(
                request()
                    .withPath(calculatePath("some_path"))
                    .withSecure(false)
            );
        mockServerClient
            .verify(
                request()
                    .withPath(calculatePath("some_path"))
                    .withSecure(false), VerificationTimes.exactly(1)
            );

        // - in https
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_secure_path"))
                    .withSecure(true),
                getHeadersToRemove()
            )
        );
        mockServerClient
            .verify(
                request()
                    .withPath(calculatePath("some_secure_path"))
                    .withSecure(true)
            );
        mockServerClient
            .verify(
                request()
                    .withPath(calculatePath("some_secure_path"))
                    .withSecure(true), VerificationTimes.exactly(1)
            );
    }

    @Test
    public void shouldVerifyReceivedRequestsWithRegexBody() {
        // when
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withBody("{type: 'some_random_type', value: 'some_random_value'}"),
                exactly(2)
            )
            .respond(
                response()
                    .withBody("some_response")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withBody("{type: 'some_random_type', value: 'some_random_value'}"),
                getHeadersToRemove()
            )
        );
        mockServerClient.verify(
            request()
                .withBody(regex("\\{type\\: \\'some_random_type\\'\\, value\\: \\'some_random_value\\'\\}"))
        );
        mockServerClient.verify(
            request()
                .withBody(regex("\\{type\\: \\'some_random_type\\'\\, value\\: \\'some_random_value\\'\\}")),
            VerificationTimes.exactly(1)
        );
    }

    @Test
    public void shouldVerifyNoMatchingRequestsReceivedInSsl() {
        // when
        mockServerClient.when(request().withPath(calculatePath("some.*path")), exactly(2)).respond(response().withBody("some_body"));

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path")),
                getHeadersToRemove()
            )
        );
        try {
            mockServerClient.verify(
                request()
                    .withPath(calculatePath("some_path"))
                    .withSecure(true),
                VerificationTimes.atLeast(1)
            );
            fail("expected exception to be thrown");
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request not found at least once, expected:<{" + NEW_LINE +
                "  \"path\" : \"" + calculatePath("some_path") + "\"," + NEW_LINE +
                "  \"secure\" : true" + NEW_LINE +
                "}> but was:<{"));
        }
    }
    // shouldVerifySequenceOfRequestsReceivedIncludingThoseNotMatchingAnException — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldRetrieveRecordedRequestsAsJson — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    @Test
    public void shouldRetrieveRecordedRequestsAsJsonWithJsonBody() {
        // when
        mockServerClient
            .when(
                request()
            )
            .respond(
                response()
                    .withBody("some_body")
            );
        assertEquals(
            response("some_body"),
            makeRequest(
                request()
                    .withBody("{\"digests\": [ ]}"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response("some_body"),
            makeRequest(
                request()
                    .withBody("{\"digests\": [\"sha256:one\"]}"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response("some_body"),
            makeRequest(
                request()
                    .withBody("{\"digests\": [ ]}"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response("some_body"),
            makeRequest(
                request()
                    .withBody("{\"digests\": [\"sha256:two\"]}"),
                getHeadersToRemove()
            )
        );

        // then
        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request().withBody("{\"digests\": [ ]}"), Format.JSON)),
            request().withBody("{\"digests\": [ ]}"),
            request().withBody("{\"digests\": [ ]}")
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request().withBody(json("{\"digests\": [ ]}")), Format.JSON)),
            request().withBody("{\"digests\": [ ]}"),
            request().withBody("{\"digests\": [\"sha256:one\"]}"),
            request().withBody("{\"digests\": [ ]}"),
            request().withBody("{\"digests\": [\"sha256:two\"]}")
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request().withBody("{\"digests\": [\"sha256:one\"]}"), Format.JSON)),
            request().withBody("{\"digests\": [\"sha256:one\"]}")
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request().withBody(json("{\"digests\": [\"sha256:one\"]}")), Format.JSON)),
            request().withBody("{\"digests\": [\"sha256:one\"]}")
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request().withBody("{\"digests\": [\"sha256:two\"]}"), Format.JSON)),
            request().withBody("{\"digests\": [\"sha256:two\"]}")
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request().withBody(json("{\"digests\": [\"sha256:two\"]}")), Format.JSON)),
            request().withBody("{\"digests\": [\"sha256:two\"]}")
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request().withBody(json("{ }")), Format.JSON)),
            request().withBody("{\"digests\": [ ]}"),
            request().withBody("{\"digests\": [\"sha256:one\"]}"),
            request().withBody("{\"digests\": [ ]}"),
            request().withBody("{\"digests\": [\"sha256:two\"]}")
        );

        verifyRequestsMatches(
            new HttpRequestSerializer(new MockServerLogger()).deserializeArray(mockServerClient.retrieveRecordedRequests(request(), Format.JSON)),
            request().withBody("{\"digests\": [ ]}"),
            request().withBody("{\"digests\": [\"sha256:one\"]}"),
            request().withBody("{\"digests\": [ ]}"),
            request().withBody("{\"digests\": [\"sha256:two\"]}")
        );
    }

    @Test
    public void shouldRetrieveRecordedRequestsAsLogEntries() throws JsonProcessingException {
        // given
        mockServerClient.when(request().withPath(calculatePath("some_path.*")), exactly(4)).respond(response().withBody("some_body"));
        assertEquals(
            response("some_body"),
            makeRequest(
                request().withPath(calculatePath("some_path_one")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request().withPath(calculatePath("not_found")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response("some_body"),
            makeRequest(
                request().withPath(calculatePath("some_path_three")),
                getHeadersToRemove()
            )
        );

        // when
        String logEntriesActual = mockServerClient.retrieveRecordedRequests(request().withPath(calculatePath("some_path.*")), Format.LOG_ENTRIES);

        HttpRequest requestOne = request("/some_path_one")
            .withMethod("GET")
            .withHeader("host", "localhost:" + this.getServerPort())
            .withHeader("accept-encoding", "gzip,deflate")
            .withHeader("content-length", "0")
            .withHeader("connection", "keep-alive")
            .withKeepAlive(true)
            .withSecure(false)
            .withProtocol(Protocol.HTTP_1_1)
            .withLocalAddress("127.0.0.1:" + getServerPort())
            .withRemoteAddress("127.0.0.1:" + getRequestTcpPortForPath("/some_path_one"));
        HttpRequest requestTwo = request("/some_path_three")
            .withMethod("GET")
            .withHeader("host", "localhost:" + this.getServerPort())
            .withHeader("accept-encoding", "gzip,deflate")
            .withHeader("content-length", "0")
            .withHeader("connection", "keep-alive")
            .withKeepAlive(true)
            .withSecure(false)
            .withProtocol(Protocol.HTTP_1_1)
            .withLocalAddress("127.0.0.1:" + getServerPort())
            .withRemoteAddress("127.0.0.1:" + getRequestTcpPortForPath("/some_path_three"));

        List<LogEntry> logEntriesExpected = Arrays.asList(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(requestOne)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(requestOne),
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setHttpRequest(requestTwo)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(requestTwo)
        );

        // then
        JsonNode actualArray = ObjectMapperFactory.createObjectMapper().readTree(logEntriesActual);
        assertThat(actualArray.size(), is(2));
        for (int i = 0; i < 2; i++) {
            LogEntry expected = logEntriesExpected.get(i);
            JsonNode actual = actualArray.get(i);
            assertThat(actual.get("type").asText(), is(expected.getType().name()));
            assertThat(actual.get("logLevel").asText(), is(expected.getLogLevel().name()));
            assertThat(actual.get("messageFormat").asText(), is(expected.getMessageFormat()));
            assertThat(actual.has("httpRequest"), is(true));
            assertThat(actual.get("httpRequest").get("path").asText(), is(((HttpRequest) expected.getHttpRequest()).getPath().getValue()));
        }
    }

    public String getRequestTcpPortForPath(String path) throws JsonProcessingException {
        final Iterator<JsonNode> foundRequests = ObjectMapperFactory.createObjectMapper().readTree(
                mockServerClient.retrieveRecordedRequests(request().withPath(calculatePath(path)),
                    Format.JAVA))
            .elements();
        JsonNode currentRequests;
        do {
            currentRequests = foundRequests.next();
        } while (foundRequests.hasNext());
        return currentRequests
            .get("remoteAddress").asText()
            .split(":")[1];
    }
    // shouldRetrieveActiveExpectationsAsJson — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldRetrieveActiveExpectationsAsJava — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    @Test
    public void shouldRetrieveRecordedExpectationsAsJson() {
        // when
        mockServerClient.when(request().withPath(calculatePath("some_path.*")), exactly(4)).forward(
            forward()
                .withHost("127.0.0.1")
                .withPort(insecureEchoServer.getPort())
        );
        assertEquals(
            response("some_body_one"),
            makeRequest(
                request().withPath(calculatePath("some_path_one")).withBody("some_body_one"),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response("some_body_three"),
            makeRequest(
                request().withPath(calculatePath("some_path_three")).withBody("some_body_three"),
                getHeadersToRemove()
            )
        );

        // then
        Expectation[] recordedExpectations = new ExpectationSerializer(new MockServerLogger()).deserializeArray(
            mockServerClient.retrieveRecordedExpectations(request().withPath(calculatePath("some_path_one")), Format.JSON),
            false
        );
        assertThat(recordedExpectations.length, is(1));
        verifyRequestsMatches(
            new RequestDefinition[]{
                recordedExpectations[0].getHttpRequest()
            },
            request(calculatePath("some_path_one")).withBody("some_body_one")
        );
        assertThat(recordedExpectations[0].getHttpResponse().getBodyAsString(), is("some_body_one"));
        // and
        recordedExpectations = new ExpectationSerializer(new MockServerLogger()).deserializeArray(
            mockServerClient.retrieveRecordedExpectations(request(), Format.JSON),
            false
        );
        assertThat(recordedExpectations.length, is(2));
        verifyRequestsMatches(
            new RequestDefinition[]{
                recordedExpectations[0].getHttpRequest(),
                recordedExpectations[1].getHttpRequest()
            },
            request(calculatePath("some_path_one")).withBody("some_body_one"),
            request(calculatePath("some_path_three")).withBody("some_body_three")
        );
        assertThat(recordedExpectations[0].getHttpResponse().getBodyAsString(), is("some_body_one"));
        assertThat(recordedExpectations[1].getHttpResponse().getBodyAsString(), is("some_body_three"));
        // and
        recordedExpectations = new ExpectationSerializer(new MockServerLogger()).deserializeArray(
            mockServerClient.retrieveRecordedExpectations(null, Format.JSON),
            false
        );
        assertThat(recordedExpectations.length, is(2));
        verifyRequestsMatches(
            new RequestDefinition[]{
                recordedExpectations[0].getHttpRequest(),
                recordedExpectations[1].getHttpRequest()
            },
            request(calculatePath("some_path_one")).withBody("some_body_one"),
            request(calculatePath("some_path_three")).withBody("some_body_three")
        );
        assertThat(recordedExpectations[0].getHttpResponse().getBodyAsString(), is("some_body_one"));
        assertThat(recordedExpectations[1].getHttpResponse().getBodyAsString(), is("some_body_three"));
    }

    @Test
    public void shouldClearExpectationsWithXPathBody() {
        // given
        mockServerClient
            .when(
                request()
                    .withBody(xpath("/bookstore/book[year=2005]/price"))
            )
            .respond(
                response()
                    .withBody("some_body1")
            );
        mockServerClient
            .when(
                request()
                    .withBody(xpath("/bookstore/book[year=2006]/price"))
            )
            .respond(
                response()
                    .withBody("some_body2")
            );

        // and
        StringBody xmlBody = new StringBody("" +
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + NEW_LINE +
            "<bookstore>" + NEW_LINE +
            "  <book category=\"COOKING\">" + NEW_LINE +
            "    <title lang=\"en\">Everyday Italian</title>" + NEW_LINE +
            "    <author>Giada De Laurentiis</author>" + NEW_LINE +
            "    <year>2005</year>" + NEW_LINE +
            "    <price>30.00</price>" + NEW_LINE +
            "  </book>" + NEW_LINE +
            "  <book category=\"CHILDREN\">" + NEW_LINE +
            "    <title lang=\"en\">Harry Potter</title>" + NEW_LINE +
            "    <author>J K. Rowling</author>" + NEW_LINE +
            "    <year>2006</year>" + NEW_LINE +
            "    <price>29.99</price>" + NEW_LINE +
            "  </book>" + NEW_LINE +
            "  <book category=\"WEB\">" + NEW_LINE +
            "    <title lang=\"en\">Learning XML</title>" + NEW_LINE +
            "    <author>Erik T. Ray</author>" + NEW_LINE +
            "    <year>2003</year>" + NEW_LINE +
            "    <price>31.95</price>" + NEW_LINE +
            "  </book>" + NEW_LINE +
            "</bookstore>");

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body1"),
            makeRequest(
                request()
                    .withBody(xmlBody),
                getHeadersToRemove()
            )
        );

        // when
        mockServerClient
            .clear(
                request()
                    .withBody(xpath("/bookstore/book[year=2005]/price"))
            );

        // then
        assertThat(
            mockServerClient.retrieveActiveExpectations(null),
            arrayContaining(
                new Expectation(request()
                    .withBody(xpath("/bookstore/book[year=2006]/price")))
                    .thenRespond(
                        response()
                            .withBody("some_body2")
                    )
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body2"),
            makeRequest(
                request()
                    .withBody(xmlBody),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldClearExpectationsWithJsonSchemaBody() {
        // given
        JsonSchemaBody jsonSchemaBodyOne = jsonSchema("{" + NEW_LINE +
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
            "}");
        JsonSchemaBody jsonSchemaBodyTwo = jsonSchema("{" + NEW_LINE +
            "  \"$schema\" : \"http://json-schema.org/draft-04/schema#\"," + NEW_LINE +
            "  \"title\" : \"Product\"," + NEW_LINE +
            "  \"description\" : \"A product from Acme's catalog\"," + NEW_LINE +
            "  \"type\" : \"object\"," + NEW_LINE +
            "  \"properties\" : {" + NEW_LINE +
            "    \"id\" : {" + NEW_LINE +
            "      \"description\" : \"The unique identifier for a product\"," + NEW_LINE +
            "      \"type\" : \"integer\"" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"name\" : {" + NEW_LINE +
            "      \"description\" : \"Name of the product\"," + NEW_LINE +
            "      \"type\" : \"string\"" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"price\" : {" + NEW_LINE +
            "      \"type\" : \"number\"," + NEW_LINE +
            "      \"minimum\" : 10," + NEW_LINE +
            "      \"exclusiveMinimum\" : true" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"tags\" : {" + NEW_LINE +
            "      \"type\" : \"array\"," + NEW_LINE +
            "      \"items\" : {" + NEW_LINE +
            "        \"type\" : \"string\"" + NEW_LINE +
            "      }," + NEW_LINE +
            "      \"minItems\" : 1," + NEW_LINE +
            "      \"uniqueItems\" : true" + NEW_LINE +
            "    }" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"required\" : [ \"id\", \"name\", \"price\" ]" + NEW_LINE +
            "}");
        mockServerClient
            .when(
                request()
                    .withBody(jsonSchemaBodyOne)
            )
            .respond(
                response()
                    .withBody("some_body1")
            );
        mockServerClient
            .when(
                request()
                    .withBody(jsonSchemaBodyTwo)
            )
            .respond(
                response()
                    .withBody("some_body2")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body1"),
            makeRequest(
                request()
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );

        // when
        mockServerClient
            .clear(
                request()
                    .withBody(jsonSchemaBodyOne)
            );

        // then
        assertThat(
            mockServerClient.retrieveActiveExpectations(null),
            arrayContaining(
                new Expectation(request()
                    .withBody(jsonSchemaBodyTwo))
                    .thenRespond(
                        response()
                            .withBody("some_body2")
                    )
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body2"),
            makeRequest(
                request()
                    .withBody("{" + NEW_LINE +
                        "    \"id\": 1," + NEW_LINE +
                        "    \"name\": \"A green door\"," + NEW_LINE +
                        "    \"price\": 12.50," + NEW_LINE +
                        "    \"tags\": [\"home\", \"green\"]" + NEW_LINE +
                        "}"),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldClearExpectationsWithParameterBody() {
        // given
        mockServerClient
            .when(
                request()
                    .withBody(params(param("bodyParameterNameOne", "bodyParameterValueOne")))
            )
            .respond(
                response()
                    .withBody("some_body1")
            );
        mockServerClient
            .when(
                request()
                    .withBody(params(param("bodyParameterNameTwo", "bodyParameterValueTwo")))
            )
            .respond(
                response()
                    .withBody("some_body2")
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body1"),
            makeRequest(
                request()
                    .withBody(params(param("bodyParameterNameOne", "bodyParameterValueOne"))),
                getHeadersToRemove()
            )
        );

        // when
        mockServerClient
            .clear(
                request()
                    .withBody(params(param("bodyParameterNameOne", "bodyParameterValueOne")))
            );

        // then
        assertThat(
            mockServerClient.retrieveActiveExpectations(null),
            arrayContaining(
                new Expectation(request()
                    .withBody(params(param("bodyParameterNameTwo", "bodyParameterValueTwo"))))
                    .thenRespond(
                        response()
                            .withBody("some_body2")
                    )
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_body2"),
            makeRequest(
                request()
                    .withBody(params(param("bodyParameterNameTwo", "bodyParameterValueTwo"))),
                getHeadersToRemove()
            )
        );
    }

    // shouldEnsureThatInterruptedRequestsAreVerifiable — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)
    // shouldEnsureThatRequestDelaysDoNotAffectOtherRequests — moved to AbstractTransportAgnosticSemanticsIntegrationTest (step 2)

    // --- tests moved up from AbstractExtendedNettyMockingIntegrationTest ---
    // so they run across ALL L3+ subclasses (netty, WAR servlet, HTTP/2)

    @Test
    public void shouldReturnResponseByMatchingQueryParametersWithPipeDelimitedParameters() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath("/some/path")
                    .withQueryStringParameters(new Parameters(
                        schemaParam("variableO[a-z]{2}", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"variableOneV[a-z]{4}$\"" + NEW_LINE +
                            "}").withStyle(ParameterStyle.PIPE_DELIMITED),
                        schemaParam("?variableTwo", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"variableTwoV[a-z]{4}$\"" + NEW_LINE +
                            "}").withStyle(ParameterStyle.PIPE_DELIMITED)
                    ).withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY))
            )
            .respond(
                response()
                    .withStatusCode(200)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValaa|variableOneValbb|variableOneValcc" +
                                                "&variableTwo=variableTwoValue|variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValab" +
                                                "&variableTwo=variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValaa|variableOneValbb|variableOneValcc")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValaax|variableOneValbb|variableOneValcc")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValaa|variableOneValbbx|variableOneValcc")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValaa|variableOneValbb|variableOneValccx")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValaa|variableOneValbb|variableOneValcc" +
                                                "&variableTwo=variableTwoOtherValue|variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValaa|variableOneValbb|variableOneValcc" +
                                                "&variableTwo=variableTwoValue|variableTwoOtherValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "?variableOne=variableOneValaax|variableOneValbb|variableOneValcc" +
                                                "&variableTwo=variableTwoValue|variableTwoOtherValue")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingPathParametersWithMatrixStyleParameters() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath("/some/path/{variableOne}/{variableTwo}")
                    .withPathParameters(new Parameters(
                        schemaParam("variableO[a-z]{2}", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"variableOneV[a-z]{4}$\"" + NEW_LINE +
                            "}").withStyle(ParameterStyle.MATRIX_EXPLODED),
                        schemaParam("variableTwo", "{" + NEW_LINE +
                            "   \"type\": \"string\"," + NEW_LINE +
                            "   \"pattern\": \"variableTwoV[a-z]{4}$\"" + NEW_LINE +
                            "}").withStyle(ParameterStyle.MATRIX)
                    ).withKeyMatchStyle(KeyMatchStyle.MATCHING_KEY))
            )
            .respond(
                response()
                    .withStatusCode(200)
            );

        // then
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "/;variableOne=variableOneValaa;variableOne=variableOneValbb;variableOne=variableOneValcc" +
                                                "/;variableTwo=variableTwoValue,variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase()),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "/;variableOne=variableOneValab" +
                                                "/;variableTwo=variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "/;variableOne=variableOneValaa;variableOne=variableOneValbb;variableOne=variableOneValcc" +
                                                "/;variableTwo=variableTwoOtherValue,variableTwoValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "/;variableOne=variableOneValaa;variableOne=variableOneValbb;variableOne=variableOneValcc" +
                                                "/;variableTwo=variableTwoValue,variableTwoOtherValue")),
                getHeadersToRemove()
            )
        );
        assertEquals(
            notFoundResponse(),
            makeRequest(
                request()
                    .withPath(calculatePath("some/path" +
                                                "/;variableOne=variableOneValaax;variableOne=variableOneValbb;variableOne=variableOneValcc" +
                                                "/;variableTwo=variableTwoValue,variableTwoOtherValue")),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldReturnResponseByMatchingVeryLargeHeader() {
        // when
        char[] chars = new char[1024 * 2 * 2 * 2 * 2];
        Arrays.fill(chars, 'a');
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) ('a' + (i % 26));
        }
        String largeHeaderValue = new String(chars);
        mockServerClient
            .when(
                request()
                    .withHeader("largeHeader", largeHeaderValue)
            )
            .respond(
                response()
                    .withBody("some_string_body_response")
            );

        // then
        // - in http
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_string_body_response"),
            makeRequest(
                request()
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withHeader("largeHeader", largeHeaderValue),
                getHeadersToRemove()
            )
        );
        // - in https
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("some_string_body_response"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withMethod("POST")
                    .withPath(calculatePath("some_path"))
                    .withHeader("largeHeader", largeHeaderValue),
                getHeadersToRemove()
            )
        );
    }

    @Test
    public void shouldAllowMatchingAgainstContentEncodingHeader() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("some_path"))
                    .withHeader(CONTENT_ENCODING.toString(), "gzip")
            )
            .respond(
                response()
                    .withBody("context_encoded_matched")
            );

        // then
        // - in http
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("context_encoded_matched"),
            makeRequest(
                request()
                    .withPath(calculatePath("some_path"))
                    .withHeader(CONTENT_ENCODING.toString(), "gzip"),
                getHeadersToRemove()
            )
        );
        // - in https
        assertEquals(
            response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withBody("context_encoded_matched"),
            makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("some_path"))
                    .withHeader(CONTENT_ENCODING.toString(), "gzip"),
                getHeadersToRemove()
            )
        );
    }

}
