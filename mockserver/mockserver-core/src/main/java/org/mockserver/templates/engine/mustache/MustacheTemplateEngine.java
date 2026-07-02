package org.mockserver.templates.engine.mustache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.helpers.RequestBodyExtractionHelper;
import org.mockserver.templates.engine.helpers.ScenarioTemplateHelper;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * See: https://github.com/samskivert/jmustache or http://mustache.github.io/mustache.5.html for syntax
 *
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class MustacheTemplateEngine implements TemplateEngine {

    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final Mustache.Compiler compiler;
    private HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;
    // Per-engine seeded faker when templateFakerSeed is non-zero, else null (the shared unseeded faker
    // from BUILT_IN_HELPERS is used). Resolved once so the seeded sequence is deterministic across renders.
    private final Object seededFaker;

    // jmustache cannot invoke a helper method with an argument the way Velocity ($scenario.get('x')) and
    // JavaScript (scenario.get('x')) can, so scenario state is exposed the same way as jsonPath/xPath: as a
    // section lambda whose section body is the argument. "scenario" is a Map holding a "get" lambda, so a
    // template reads captured/scenario state by name with {{#scenario.get}}name{{/scenario.get}}. The map
    // and lambda are stateless (the lambda resolves the live ScenarioManager lazily on each call via
    // ScenarioTemplateHelper) so a single immutable instance is shared safely across all render threads.
    private final Map<String, Object> scenarioMustacheHelper;

    // ----- parsed-template cache (compile once, render many) -----
    // Templates are mostly static for the lifetime of a mock — the same template string is rendered on
    // every matching request. The original path re-compiled the template string into a fresh parsed
    // Template on every render via compiler.compile(template), which dominated render cost. We instead
    // compile each distinct template string at most once and reuse the resulting jmustache Template
    // across renders. A jmustache Template is immutable once compiled and is rendered (Template.execute)
    // concurrently with a fresh per-render data map, so a single shared compiled Template is safe to
    // reuse across request threads — mirroring how the Velocity engine reuses its parsed Templates.
    //
    // The cache is keyed by the template content string itself, so distinct templates can never collide
    // onto the same compiled Template. Bound rationale: distinct template strings are bounded by the
    // number of expectations/actions a user configures, but a misbehaving client (e.g. unique generated
    // templates per request) could otherwise grow the cache without limit. We cap the number of cached
    // templates at PARSED_TEMPLATE_CACHE_MAX (matching the Velocity engine's bound) so memory stays
    // bounded; 1000 comfortably covers realistic mock configurations while remaining cheap. The bound is
    // enforced by compiledTemplates, an access-ordered LRU wrapped in Collections.synchronizedMap so
    // concurrent renders read and insert safely (the same concurrency approach Velocity uses for its
    // registeredTemplates LRU).
    static final int PARSED_TEMPLATE_CACHE_MAX = 1000;
    private final Map<String, Template> compiledTemplates = Collections.synchronizedMap(new LinkedHashMap<String, Template>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Template> eldest) {
            return size() > PARSED_TEMPLATE_CACHE_MAX;
        }
    });

    public MustacheTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        compiler = Mustache
            .compiler()
            .emptyStringIsFalse(true)
            .zeroIsFalse(true)
            .strictSections(false)
            .defaultValue("")
            .withCollector(new ExtendedCollector());
        ScenarioTemplateHelper scenarioTemplateHelper = new ScenarioTemplateHelper();
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("get", (Mustache.Lambda) (frag, out) -> {
            String state = scenarioTemplateHelper.get(frag.execute());
            out.write(state == null ? "" : state);
        });
        this.scenarioMustacheHelper = Collections.unmodifiableMap(scenario);
        this.seededFaker = configuration.templateFakerSeed() != 0L
            ? TemplateFunctions.resolveFaker(configuration.templateFakerSeed())
            : null;
    }

    /**
     * Return the jmustache {@link Template} for the given template string, compiling it at most once per
     * distinct string and reusing the cached compiled Template on subsequent renders. The compiled
     * Template is immutable and rendered concurrently with a fresh per-render data map, so a single
     * shared instance is safe across request threads. A compilation failure (malformed template) is not
     * cached — it propagates to the caller exactly as {@code compiler.compile(template)} would, so error
     * handling is unchanged.
     */
    private Template compiledTemplate(String template) {
        Template cached = compiledTemplates.get(template);
        if (cached == null) {
            // compile outside the map mutation so a malformed template throws (and is never cached);
            // a benign race where two threads compile the same string concurrently just discards one
            // identical compiled Template, which is harmless.
            cached = compiler.compile(template);
            compiledTemplates.put(template, cached);
        }
        return cached;
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, dtoClass);
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, response, dtoClass);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request) {
        return renderTemplate(template, request, null);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request, org.mockserver.load.IterationContext iteration) {
        try {
            validateTemplate(template);
            Writer writer = new StringWriter();
            Template compiledTemplate = compiledTemplate(template);
            Map<String, Object> data = new ConcurrentHashMap<>();
            data.put("request", new HttpRequestTemplateObject(request));
            if (iteration != null) {
                data.put("iteration", iteration);
            }
            data.putAll(TemplateFunctions.BUILT_IN_FUNCTIONS);
            data.putAll(TemplateFunctions.BUILT_IN_HELPERS);
            if (seededFaker != null) {
                data.put("faker", seededFaker);
            }
            data.put("scenario", scenarioMustacheHelper);
            data.put("xPath", (Mustache.Lambda) (frag, out) -> evaluatedXPath(frag.execute(), request, out));
            data.put("jsonPath", (Mustache.Lambda) (frag, out) -> evaluateJsonPath(data, frag.execute(), request, out));
            compiledTemplate.execute(data, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
    }

    private <T> T executeTemplateInternal(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        T result;
        try {
            validateTemplate(template);
            Writer writer = new StringWriter();
            Template compiledTemplate = compiledTemplate(template);
            Map<String, Object> data = new ConcurrentHashMap<>();
            data.put("request", new HttpRequestTemplateObject(request));
            if (response != null) {
                data.put("response", new HttpResponseTemplateObject(response));
            }
            data.putAll(TemplateFunctions.BUILT_IN_FUNCTIONS);
            data.putAll(TemplateFunctions.BUILT_IN_HELPERS);
            if (seededFaker != null) {
                data.put("faker", seededFaker);
            }
            data.put("scenario", scenarioMustacheHelper);
            data.put("xPath", (Mustache.Lambda) (frag, out) -> evaluatedXPath(frag.execute(), request, out));
            data.put("jsonPath", (Mustache.Lambda) (frag, out) -> evaluateJsonPath(data, frag.execute(), request, out));
            compiledTemplate.execute(data, writer);
            JsonNode generatedObject = null;
            try {
                generatedObject = objectMapper.readTree(writer.toString());
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setHttpRequest(request)
                            .setMessageFormat("exception deserialising generated content:{}into json node for request:{}")
                            .setArguments(writer.toString(), request)
                    );
                }
            }
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(TEMPLATE_GENERATED)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat(TEMPLATE_GENERATED_MESSAGE_FORMAT)
                        .setArguments(generatedObject != null ? generatedObject : writer.toString(), template, request)
                );
            }
            result = httpTemplateOutputDeserializer.deserializer(request, writer.toString(), dtoClass);
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
        return result;
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.mustacheDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.mustacheDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }

    private void evaluateJsonPath(Map<String, Object> data, String jsonPath, HttpRequest request, Writer out) throws IOException {
        // Delegate the actual extraction to the shared helper so the Mustache, Velocity and JavaScript
        // engines all use the same JSONPath/XPath logic and error handling. The Mustache idiom is to
        // store the extracted value under "jsonPathResult" (so it can be iterated in a following section)
        // and emit nothing inline.
        Object jsonPathResult = new RequestBodyExtractionHelper(request, mockServerLogger).jsonPath(jsonPath);
        data.put("jsonPathResult", jsonPathResult);
        out.write("");
    }

    private void evaluatedXPath(String xPath, HttpRequest request, Writer out) throws IOException {
        out.write(new RequestBodyExtractionHelper(request, mockServerLogger).xPath(xPath));
    }
}
