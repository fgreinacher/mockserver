package org.mockserver.log.model;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.JsonBody;
import org.mockserver.model.LogEntryBody;
import org.mockserver.serialization.LogEntrySerializer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

/**
 * Guards the deferred (render-time) conversion of {@link LogEntry} message arguments introduced to stop a logged
 * entry pinning a parsed Jackson {@code JsonNode} tree per JSON body for its whole life in the event-log deque.
 * <p>
 * The two acceptance criteria for that change are covered here:
 * <ul>
 *     <li><b>Output is byte-for-byte unchanged</b> — {@link #getArguments()} still yields the converted
 *     {@link LogEntryBody} form, so both consumers of the arguments (the JSON log serializer and the rendered
 *     message text) reproduce the same output they did when the conversion was performed eagerly in
 *     {@code setArguments}.</li>
 *     <li><b>Nothing is retained</b> — the entry stores the raw request/response references (unparsed body),
 *     and the {@link LogEntryBody}/{@code JsonNode} form is materialised transiently on each read and discarded.</li>
 * </ul>
 */
public class LogEntryDeferredArgumentConversionTest {

    private static final String REQUEST_JSON = "{\"outer\":{\"inner\":\"request-value\"},\"count\":3}";
    private static final String RESPONSE_JSON = "{\"status\":{\"ok\":true},\"items\":[1,2]}";

    private static LogEntry entryWithJsonBodyArguments() {
        return new LogEntry()
            .setHttpRequest(request().withPath("/api/thing"))
            .setMessageFormat("received request:{}and responded:{}")
            .setArguments(
                request().withPath("/api/thing").withBody(json(REQUEST_JSON)),
                response().withStatusCode(200).withBody(json(RESPONSE_JSON))
            );
    }

    // ---- Risk 1: byte-for-byte output unchanged at BOTH consumers ----

    @Test
    public void jsonLogSerializerRendersArgumentBodiesAsInlineJson() {
        // given
        String serialized = new LogEntrySerializer(new MockServerLogger()).serialize(entryWithJsonBodyArguments());

        // then - the request/response argument bodies render as structured (parsed) JSON, exactly as the
        // eagerly-converted LogEntryBody(JsonNode) form always did, NOT as a raw JsonBody wrapper object
        assertThat(serialized, containsString("\"inner\" : \"request-value\""));
        assertThat(serialized, containsString("\"count\" : 3"));
        assertThat(serialized, containsString("\"ok\" : true"));
        // a raw (unconverted) JsonBody would have serialised with its wrapper discriminator
        assertThat(serialized, not(containsString("\"type\" : \"JSON\"")));
    }

    @Test
    public void renderedMessageContainsBothArgumentBodies() {
        // given
        String message = entryWithJsonBodyArguments().getMessage();

        // then - the message text embeds the same rendered JSON for each argument
        assertThat(message, containsString("received request:"));
        assertThat(message, containsString("\"inner\" : \"request-value\""));
        assertThat(message, containsString("and responded:"));
        assertThat(message, containsString("\"ok\" : true"));
    }

    @Test
    public void getArgumentsYieldsConvertedLogEntryBodyForm() {
        // when
        Object[] arguments = entryWithJsonBodyArguments().getArguments();

        // then
        assertThat(arguments, is(arrayWithSize(2)));
        assertThat(arguments[0], instanceOf(HttpRequest.class));
        assertThat(((HttpRequest) arguments[0]).getBody(), instanceOf(LogEntryBody.class));
        assertThat(arguments[1], instanceOf(HttpResponse.class));
        assertThat(((HttpResponse) arguments[1]).getBody(), instanceOf(LogEntryBody.class));
    }

    // ---- Risk: nothing retained — the stored form is the raw, unparsed body ----

    @Test
    public void storedArgumentsRetainRawUnparsedBodiesNotParsedTrees() {
        // given
        LogEntry logEntry = entryWithJsonBodyArguments();

        // then - the retained field holds the ORIGINAL request/response with an unparsed JsonBody: no
        // LogEntryBody / JsonNode tree is pinned for the life of the entry
        Object[] raw = logEntry.getRawArguments();
        assertThat(raw, is(arrayWithSize(2)));
        assertThat(((HttpRequest) raw[0]).getBody(), instanceOf(JsonBody.class));
        assertThat(((HttpRequest) raw[0]).getBody(), is(not(instanceOf(LogEntryBody.class))));
        assertThat(((HttpResponse) raw[1]).getBody(), instanceOf(JsonBody.class));
        assertThat(((HttpResponse) raw[1]).getBody(), is(not(instanceOf(LogEntryBody.class))));
    }

    @Test
    public void conversionIsTransientAndDoesNotMutateStoredArguments() {
        // given
        LogEntry logEntry = entryWithJsonBodyArguments();
        Body<?> rawRequestBodyBefore = ((HttpRequest) logEntry.getRawArguments()[0]).getBody();

        // when - reading the converted view twice
        Object[] first = logEntry.getArguments();
        Object[] second = logEntry.getArguments();

        // then - each read is a freshly materialised array (nothing memoised/retained) ...
        assertThat(first, is(not(sameInstance(second))));
        // ... and reading the converted view has not mutated the stored raw body
        assertThat(((HttpRequest) logEntry.getRawArguments()[0]).getBody(), is(sameInstance(rawRequestBodyBefore)));
        assertThat(((HttpRequest) logEntry.getRawArguments()[0]).getBody(), instanceOf(JsonBody.class));
    }

    // ---- clone()/translateTo carry the raw form (retention fix survives the Disruptor copy) ----

    @Test
    public void cloneCarriesRawArgumentsAndStillRendersConvertedForm() {
        // given
        LogEntry clone = entryWithJsonBodyArguments().clone();

        // then - the clone retains raw bodies (not parsed trees) ...
        assertThat(((HttpRequest) clone.getRawArguments()[0]).getBody(), instanceOf(JsonBody.class));
        assertThat(((HttpRequest) clone.getRawArguments()[0]).getBody(), is(not(instanceOf(LogEntryBody.class))));
        // ... yet still renders the converted form on read
        assertThat(((HttpRequest) clone.getArguments()[0]).getBody(), instanceOf(LogEntryBody.class));
        assertThat(clone.getMessage(), containsString("\"inner\" : \"request-value\""));
    }

    @Test
    public void nullArgumentIsNormalisedToEmptyStringAsBefore() {
        // given
        LogEntry logEntry = new LogEntry()
            .setMessageFormat("value:{}")
            .setArguments((Object) null);

        // then - preserved legacy behaviour: a null argument becomes an empty string
        assertThat(logEntry.getRawArguments(), is(arrayWithSize(1)));
        assertThat(logEntry.getRawArguments()[0], is(""));
        assertThat(logEntry.getArguments()[0], is(""));
    }
}
