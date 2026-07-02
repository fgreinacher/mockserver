package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.formatting.StringFormatter.indentAndToString;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"RedundantSuppression", "FieldMayBeFinal"})
public class JavaScriptTemplateEngine implements TemplateEngine {

    private static final boolean POLYGLOT_AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("org.graalvm.polyglot.Context");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        POLYGLOT_AVAILABLE = available;
    }

    private final ObjectMapper objectMapper;
    private final MockServerLogger mockServerLogger;
    private final HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;
    private final Configuration configuration;
    private final Predicate<String> classFilter;
    private final boolean polyglotAvailable;

    public JavaScriptTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this(mockServerLogger, configuration, POLYGLOT_AVAILABLE);
    }

    /**
     * Visible for testing: allows exercising the polyglot-unavailable (fail-loud) path even when the
     * GraalVM Polyglot API is present on the test classpath. Production always uses the public two-arg
     * constructor, which pins {@code polyglotAvailable} to the real classpath probe {@link #POLYGLOT_AVAILABLE}.
     */
    JavaScriptTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration, boolean polyglotAvailable) {
        this.polyglotAvailable = polyglotAvailable;
        this.configuration = (configuration == null) ? configuration() : configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        this.objectMapper = ObjectMapperFactory.createObjectMapper();
        this.classFilter = className -> isClassAllowed(className, this.configuration);
        if (mockServerLogger != null
            && mockServerLogger.isEnabledForInstance(Level.WARN)
            && !isNotBlank(this.configuration.javascriptDisallowedClasses())) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("JavaScript template engine has no class restrictions (mockserver.javascriptDisallowedClasses is empty). Templates can use Java.type(\"...\") to instantiate arbitrary Java classes including Runtime — only use JavaScript templates from trusted sources, or populate mockserver.javascriptDisallowedClasses with at least java.lang.Runtime,java.lang.ProcessBuilder,java.lang.System.")
            );
        }
    }

    public static boolean isPolyglotAvailable() {
        return POLYGLOT_AVAILABLE;
    }

    private static boolean isClassAllowed(String className, Configuration configuration) {
        if (isNotBlank(configuration.javascriptDisallowedClasses())) {
            Iterable<String> restrictedClasses = Splitter.on(",").trimResults().split(configuration.javascriptDisallowedClasses());
            return StreamSupport.stream(restrictedClasses.spliterator(), false)
                .noneMatch(restrictedClass -> restrictedClass.equalsIgnoreCase(className));
        }
        return true;
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, null, dtoClass, false);
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, response, null, dtoClass, true);
    }

    /**
     * Load-generation only: execute a JavaScript template with a per-iteration variable
     * ({@code iteration}) bound in the script scope. Used by the load executor so a JavaScript
     * load-scenario step can vary its output per iteration. Identical to
     * {@link #executeTemplate(String, HttpRequest, Class)} when {@code iteration} is null.
     */
    public <T> T executeTemplate(String template, HttpRequest request, org.mockserver.load.IterationContext iteration, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, iteration, dtoClass, false);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request) {
        // JavaScript templates are designed to construct and return a full response object, not a text
        // fragment, so they are not supported for FileBody templating. Use httpResponseTemplate (or
        // httpResponseTemplate with templateFile) with a JavaScript template instead. Streaming payload
        // templating uses renderTemplateText(...) below, which executes the template and coerces its
        // return value to text.
        throw new UnsupportedOperationException("JavaScript templates are not supported for file body templating; use a Velocity or Mustache templateType, or an httpResponseTemplate for JavaScript");
    }

    @Override
    public String renderTemplateText(String template, HttpRequest request) {
        assertPolyglotAvailable(request);
        String script = wrapTemplate(template);
        try {
            validateTemplate(template);
            Long executionTimeout = configuration.javascriptTemplateExecutionTimeout();
            // rawText=true: the template's handle(request) return value is coerced to text (a string is
            // used verbatim, any other value is JSON.stringify'd) rather than deserialised into a response.
            return PolyglotRunner.run(
                script,
                false,
                request,
                null,
                null,
                classFilter,
                objectMapper,
                mockServerLogger,
                httpTemplateOutputDeserializer,
                null,
                executionTimeout == null ? 0L : executionTimeout,
                true
            );
        } catch (JavaScriptTemplateTimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
    }

    /**
     * Fail loudly rather than silently degrade. If a JavaScript template is actually used but the
     * GraalVM Polyglot API (GraalJS) is not on the classpath, we cannot render it, so surface a clear,
     * actionable error the same way a template transform failure does (RuntimeException) instead of
     * returning null and producing a confusing empty/degraded response.
     */
    private void assertPolyglotAvailable(HttpRequest request) {
        if (!polyglotAvailable) {
            String message = "JavaScript response templates require the GraalJS engine, which is not on the classpath. " +
                "Add the org.graalvm.polyglot:js (or js-community) dependency, or use the Velocity or Mustache template engine.";
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(request)
                        .setMessageFormat(message)
                );
            }
            throw new RuntimeException(message);
        }
    }

    private <T> T executeTemplateInternal(String template, HttpRequest request, HttpResponse response, org.mockserver.load.IterationContext iteration, Class<? extends DTO<T>> dtoClass, boolean includeResponse) {
        assertPolyglotAvailable(request);
        String script = includeResponse ? wrapTemplateWithResponse(template) : wrapTemplate(template);
        try {
            validateTemplate(template);
            // Delegate to PolyglotRunner (nested holder class). The JVM only resolves the
            // org.graalvm.polyglot.* references inside PolyglotRunner when this branch is
            // reached, so the standard distribution (no GraalVM on classpath) never triggers a
            // NoClassDefFoundError — that case is handled by the fail-loud guard above.
            Long executionTimeout = configuration.javascriptTemplateExecutionTimeout();
            return PolyglotRunner.run(
                script,
                includeResponse,
                request,
                response,
                iteration,
                classFilter,
                objectMapper,
                mockServerLogger,
                httpTemplateOutputDeserializer,
                dtoClass,
                executionTimeout == null ? 0L : executionTimeout,
                false
            );
        } catch (JavaScriptTemplateTimeoutException e) {
            // Surface the timeout as-is (with its clear, already-logged message) rather than wrapping
            // it in the generic transform-failure message, so callers/tests can recognise the cap firing.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
    }

    static String wrapTemplate(String template) {
        return "function handle(request) {" + indentAndToString(template)[0] + "}";
    }

    static String wrapTemplateWithResponse(String template) {
        return "function handle(request, response) {" + indentAndToString(template)[0] + "}";
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.javascriptDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.javascriptDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }

}
