package org.mockserver.mock.action.http;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpTemplate;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.javascript.JavaScriptTemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;

/**
 * Shared renderer that templates a single streaming payload (an SSE event's {@code data}, a WebSocket
 * text frame, or a gRPC server-stream message) against the triggering request, reusing exactly the same
 * template engines and request/template context ({@code request.*}, {@code jsonPath}, the built-in
 * helpers, {@code faker} and {@code scenario}) that {@link HttpResponseTemplateActionHandler} uses.
 * <p>
 * <strong>Opt-in, static-preserving:</strong> {@link #render(HttpTemplate.TemplateType, String, HttpRequest)}
 * returns the payload unchanged when {@code templateType} is {@code null} (the default) — non-templated
 * streaming responses are byte-for-byte unchanged. Templating is applied only when a {@code templateType}
 * is explicitly set on the streaming action, and the payload is rendered once per event/message.
 * <p>
 * <strong>Engines:</strong> Velocity and Mustache render the payload text directly. JavaScript executes
 * the template's {@code handle(request)} function and coerces its return value to text
 * ({@link TemplateEngine#renderTemplateText(String, HttpRequest)}); if the GraalJS engine is not on the
 * classpath, a {@code JAVASCRIPT} templateType fails loudly with the same clear error the response-template
 * path uses rather than silently degrading. Engines are created lazily and cached per renderer instance,
 * mirroring {@link HttpResponseTemplateActionHandler}'s lazy engine caching.
 */
public class StreamTemplateRenderer {

    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private volatile VelocityTemplateEngine velocityTemplateEngine;
    private volatile MustacheTemplateEngine mustacheTemplateEngine;
    private volatile JavaScriptTemplateEngine javaScriptTemplateEngine;

    public StreamTemplateRenderer(MockServerLogger mockServerLogger, Configuration configuration) {
        this.configuration = configuration != null ? configuration : Configuration.configuration();
        this.mockServerLogger = mockServerLogger != null ? mockServerLogger : new MockServerLogger(StreamTemplateRenderer.class);
    }

    /**
     * Renders {@code payload} as a response template of the given {@code templateType} against
     * {@code request}. When {@code templateType} or {@code payload} is {@code null}, the payload is
     * returned unchanged (static — no engine is created).
     */
    public String render(HttpTemplate.TemplateType templateType, String payload, HttpRequest request) {
        if (templateType == null || payload == null) {
            return payload;
        }
        return engineFor(templateType).renderTemplateText(payload, request);
    }

    private TemplateEngine engineFor(HttpTemplate.TemplateType templateType) {
        switch (templateType) {
            case VELOCITY:
                return getVelocityTemplateEngine();
            case MUSTACHE:
                return getMustacheTemplateEngine();
            case JAVASCRIPT:
                return getJavaScriptTemplateEngine();
            default:
                throw new IllegalArgumentException("Unknown template engine for templateType " + templateType);
        }
    }

    private VelocityTemplateEngine getVelocityTemplateEngine() {
        VelocityTemplateEngine engine = velocityTemplateEngine;
        if (engine == null) {
            synchronized (this) {
                engine = velocityTemplateEngine;
                if (engine == null) {
                    engine = velocityTemplateEngine = new VelocityTemplateEngine(mockServerLogger, configuration);
                }
            }
        }
        return engine;
    }

    private MustacheTemplateEngine getMustacheTemplateEngine() {
        MustacheTemplateEngine engine = mustacheTemplateEngine;
        if (engine == null) {
            synchronized (this) {
                engine = mustacheTemplateEngine;
                if (engine == null) {
                    engine = mustacheTemplateEngine = new MustacheTemplateEngine(mockServerLogger, configuration);
                }
            }
        }
        return engine;
    }

    private JavaScriptTemplateEngine getJavaScriptTemplateEngine() {
        JavaScriptTemplateEngine engine = javaScriptTemplateEngine;
        if (engine == null) {
            synchronized (this) {
                engine = javaScriptTemplateEngine;
                if (engine == null) {
                    engine = javaScriptTemplateEngine = new JavaScriptTemplateEngine(mockServerLogger, configuration);
                }
            }
        }
        return engine;
    }
}
