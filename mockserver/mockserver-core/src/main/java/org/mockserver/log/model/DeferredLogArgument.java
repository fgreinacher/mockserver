package org.mockserver.log.model;

import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.model.HttpRequest;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;

import java.net.InetSocketAddress;

/**
 * A log-message argument that is rendered each time its entry is rendered, and never stored as text: the
 * curl form of a logged request.
 * <p>
 * Storing the rendered String would keep a second copy of the request body on every retained entry, which
 * the event-log byte budget does not count, and would carry every header, cookie, the query string and the
 * body past {@code redactSecretsInLog}, which redacts request objects, not text. So the argument holds the
 * request and address, and {@link LogEntry} renders it from the redacted request wherever the stored String
 * used to appear. The request must not be mutated after the entry is logged; the entry already retains it.
 */
public final class DeferredLogArgument {

    private final HttpRequestToCurlSerializer serializer;
    private final HttpRequest request;
    private final InetSocketAddress remoteAddress;

    private DeferredLogArgument(HttpRequestToCurlSerializer serializer, HttpRequest request, InetSocketAddress remoteAddress) {
        this.serializer = serializer;
        this.request = request;
        this.remoteAddress = remoteAddress;
    }

    public static DeferredLogArgument curl(HttpRequestToCurlSerializer serializer, HttpRequest request, InetSocketAddress remoteAddress) {
        return new DeferredLogArgument(serializer, request, remoteAddress);
    }

    /**
     * The curl command for the request as {@code redactor} masks it ({@code null} renders it unredacted), or a
     * short placeholder if rendering fails, so one bad argument cannot break a retrieve or dashboard read of
     * the whole log.
     */
    public String render(FixtureRedactor redactor) {
        try {
            HttpRequest toRender = redactor == null || request == null ? request : (HttpRequest) redactor.redactRequestDefinition(request);
            return serializer.toCurl(toRender, remoteAddress);
        } catch (RuntimeException e) {
            return "<unable to render: " + e.getClass().getSimpleName() + ">";
        }
    }

    /**
     * For {@link LogEntry#equals(Object)} only, which compares this argument as the String it renders; the
     * text never leaves that comparison.
     */
    String renderForEquality() {
        return render(null);
    }

    /**
     * Always redacted, whatever {@code redactSecretsInLog} says: a caller reaching the text through
     * {@code toString()} (string concatenation, a formatter, a debugger) cannot see the effective setting, so it
     * fails closed.
     */
    @Override
    public String toString() {
        return render(LogEntry.alwaysOnLogRedactor());
    }
}
