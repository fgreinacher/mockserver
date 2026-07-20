package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.llm.StreamingFormat;
import org.mockserver.llm.codec.BedrockEventStreamEncoder;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;

public class HttpSseResponseActionHandler {

    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final StreamTemplateRenderer templateRenderer;

    public HttpSseResponseActionHandler(MockServerLogger mockServerLogger, Scheduler scheduler, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.templateRenderer = new StreamTemplateRenderer(mockServerLogger, configuration);
    }

    public void handle(HttpSseResponse httpSseResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request) {
        handle(httpSseResponse, ctx, request, StreamingFormat.SSE);
    }

    public void handle(HttpSseResponse httpSseResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request, StreamingFormat format) {
        int statusCode = httpSseResponse.getStatusCode() != null ? httpSseResponse.getStatusCode() : 200;
        DefaultHttpResponse initialResponse = new DefaultHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(statusCode)
        );

        String defaultContentType;
        switch (format) {
            case NDJSON:
                defaultContentType = "application/x-ndjson";
                break;
            case AWS_EVENT_STREAM:
                defaultContentType = BedrockEventStreamEncoder.CONTENT_TYPE;
                break;
            default:
                defaultContentType = "text/event-stream";
                break;
        }
        initialResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, defaultContentType);
        initialResponse.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        initialResponse.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        initialResponse.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");

        if (httpSseResponse.getHeaders() != null) {
            httpSseResponse.getHeaders().getEntries().forEach(header ->
                header.getValues().forEach(value ->
                    initialResponse.headers().set(header.getName().getValue(), value.getValue())
                )
            );
        }

        ctx.writeAndFlush(initialResponse);

        List<SseEvent> events = httpSseResponse.getEvents();
        if (events != null && !events.isEmpty()) {
            scheduleEvents(events, 0, ctx, httpSseResponse, request, format);
        } else {
            finishStream(ctx, httpSseResponse);
        }
    }

    private void scheduleEvents(List<SseEvent> events, int index, ChannelHandlerContext ctx, HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request, StreamingFormat format) {
        if (index >= events.size() || !ctx.channel().isActive()) {
            finishStream(ctx, httpSseResponse);
            return;
        }

        SseEvent event = events.get(index);
        Delay delay = event.getDelay();

        Runnable writeEvent = () -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }
                byte[] chunkBytes = formatChunkBytes(renderEvent(event, httpSseResponse, request), format);
                DefaultHttpContent content = new DefaultHttpContent(
                    Unpooled.wrappedBuffer(chunkBytes)
                );
                ctx.writeAndFlush(content).addListener(future -> {
                    if (future.isSuccess()) {
                        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.DEBUG)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setMessageFormat("sent streaming chunk {} of {} for request:{}")
                                    .setArguments(index + 1, events.size(), request)
                            );
                        }
                        scheduleEvents(events, index + 1, ctx, httpSseResponse, request, format);
                    } else {
                        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.WARN)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setMessageFormat("async write failure for streaming chunk {} for request:{}")
                                    .setArguments(index + 1, request)
                                    .setThrowable(future.cause())
                            );
                        }
                        finishStream(ctx, httpSseResponse);
                    }
                });
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception sending streaming chunk {} for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(e)
                    );
                }
                finishStream(ctx, httpSseResponse);
            }
        };

        if (delay != null) {
            scheduler.schedule(writeEvent, false, delay);
        } else {
            writeEvent.run();
        }
    }

    /**
     * When the SSE response has a {@code templateType}, render the event's {@code data} payload as a
     * response template against the triggering request, returning a copy of the event with the rendered
     * data (the original event is never mutated, so a reused expectation renders freshly per request).
     * When there is no {@code templateType} (or no data), the original event is returned unchanged so
     * static responses are byte-for-byte identical.
     */
    private SseEvent renderEvent(SseEvent event, HttpSseResponse httpSseResponse, org.mockserver.model.HttpRequest request) {
        if (httpSseResponse.getTemplateType() == null || event.getData() == null) {
            return event;
        }
        String renderedData = templateRenderer.render(httpSseResponse.getTemplateType(), event.getData(), request);
        return SseEvent.sseEvent()
            .withEvent(event.getEvent())
            .withData(renderedData)
            .withId(event.getId())
            .withRetry(event.getRetry());
    }

    private void finishStream(ChannelHandlerContext ctx, HttpSseResponse httpSseResponse) {
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(future -> {
                if (httpSseResponse.getCloseConnection() == null || httpSseResponse.getCloseConnection()) {
                    ctx.close();
                }
            });
        }
    }

    /**
     * Format a chunk as bytes for the given streaming format. SSE and NDJSON
     * produce UTF-8 text; AWS_EVENT_STREAM produces a binary event-stream
     * message wrapping the chunk data.
     */
    private byte[] formatChunkBytes(SseEvent event, StreamingFormat format) {
        if (format == StreamingFormat.AWS_EVENT_STREAM) {
            String data = event.getData();
            if (data == null) {
                data = "";
            }
            return BedrockEventStreamEncoder.encodeChunk(data);
        }
        return formatChunk(event, format).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Format a chunk for the given streaming format. SSE uses standard
     * {@code data:}/{@code event:} framing; NDJSON emits the raw data
     * payload followed by a single newline.
     */
    private String formatChunk(SseEvent event, StreamingFormat format) {
        if (format == StreamingFormat.NDJSON) {
            return formatNdjsonLine(event);
        }
        return formatSseEvent(event);
    }

    /**
     * Format a single NDJSON line: the raw JSON data followed by {@code \n}.
     * Ignores SSE-specific fields (event, id, retry) which have no NDJSON
     * equivalent.
     */
    private String formatNdjsonLine(SseEvent event) {
        String data = event.getData();
        if (data == null) {
            return "\n";
        }
        return data + "\n";
    }

    private String sanitizeSseFieldValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\n", "").replace("\r", "");
    }

    /** Package-private for testing. */
    String formatSseEvent(SseEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getId() != null) {
            sb.append("id: ").append(sanitizeSseFieldValue(event.getId())).append("\n");
        }
        if (event.getEvent() != null) {
            sb.append("event: ").append(sanitizeSseFieldValue(event.getEvent())).append("\n");
        }
        if (event.getRetry() != null) {
            sb.append("retry: ").append(event.getRetry()).append("\n");
        }
        if (event.getData() != null) {
            // Per WHATWG, an SSE stream is split on CRLF, CR *or* LF. Splitting on "\n" alone
            // left a lone CR embedded in the emitted `data:` line, where the client treats it as
            // a line terminator -- so everything after the CR was parsed as a new (unrecognised)
            // field and silently dropped. Every line terminator must become its own `data:` line;
            // the client rejoins them with "\n", which is the only form a bare CR can survive in.
            for (String line : event.getData().split("\r\n|\r|\n")) {
                sb.append("data: ").append(line).append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }
}
