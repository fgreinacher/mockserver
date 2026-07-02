package org.mockserver.templates.engine.velocity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;
import org.apache.velocity.tools.ToolManager;
import org.apache.velocity.tools.config.ToolConfiguration;
import org.apache.velocity.tools.config.ToolboxConfiguration;
import org.apache.velocity.tools.config.XmlFactoryConfiguration;
import org.apache.velocity.util.introspection.SecureUberspector;
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
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.mockserver.templates.engine.velocity.directives.Ifnull;
import org.slf4j.event.Level;

import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class VelocityTemplateEngine implements TemplateEngine {

    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;
    private final VelocityEngine velocityEngine;
    private final ToolManager toolManager;

    // ----- shared, request-independent function/helper bindings (built once, read by every render) -----
    // The BUILT_IN_FUNCTIONS ($uuid, $now, $rand_int, ...) and BUILT_IN_HELPERS ($faker, $strings,
    // $crypto, ...) are identical for every render: they are static final ImmutableMaps holding the SAME
    // stateless value objects for the life of the JVM, and the function/helper objects are already shared
    // and invoked concurrently today (the original path merely re-referenced them from a fresh per-render
    // context). The original path copied all ~26 entries into a fresh VelocityContext on EVERY render via
    // two forEach loops. We instead build the combined map ONCE and reference it (never copy it) per
    // render through Velocity's context chaining, so the per-render work drops to a couple of thin context
    // wrappers with no 26-entry copy.
    //
    // The Velocity TOOLS ($json, $xml, $math, ...) are NOT hoisted: $json/$xml are
    // configured request-scoped and hold per-request parse state (e.g. $json.parse($request.body)), so a
    // fresh ToolContext MUST be created per render — sharing one across render threads cross-contaminates
    // request state (guarded by VelocityTemplateEngineTest.shouldUseRequestScopeToolsInThreadSafeWay).
    //
    // Per-render context chain (outermost first): child(request/iteration/jsonPath/xPath) ->
    //   functionsLayer(own map = the shared sharedFunctionsAndHelpers, read-only here) ->
    //   perRenderToolContext(tools). Reads fall through the chain; every put during a render targets only
    //   the outermost child (AbstractContext.put writes to the outermost map only) so neither the shared
    //   function/helper map nor its layer is ever mutated. functionsLayer's own storage is the SHARED map
    //   passed by reference: nothing ever calls put on functionsLayer, so the map stays read-only and
    //   safe for concurrent reads. Output is byte-for-byte identical to copying every binding into one
    //   flat context, and the per-render-varying functions ($uuid, $now, $rand, $faker, $iteration.*)
    //   still recompute on each get() because the same supplier/helper objects are invoked either way.
    private final Map<String, Object> sharedFunctionsAndHelpers;

    // ----- parsed-template cache (parse once, render many) -----
    // Templates are mostly static for the lifetime of a mock — the same template string is rendered
    // on every matching request. The original path re-parsed the template string into a fresh AST on
    // every render, which dominated render cost. To parse once and render many we register each
    // distinct template string with the engine's own StringResourceLoader and render it through
    // Velocity's native getTemplate(...) + Template.merge(...). getTemplate(...) returns the
    // parsed-and-initialised Template cached in the engine's resource cache, so the AST is built once
    // and shared across concurrent request threads (the same concurrency contract Velocity already
    // relies on for file/classpath templates). Template.merge(...) is Velocity's own render entry
    // point, so #stop, macros, scope control and IOException wrapping behave identically to evaluate.
    //
    // Bound rationale: distinct template strings are bounded by the number of expectations/actions a
    // user configures, but a misbehaving client (e.g. unique generated templates per request) could
    // otherwise grow both caches without limit. We cap the number of cached templates at
    // PARSED_TEMPLATE_CACHE_MAX so memory stays bounded; 1000 comfortably covers realistic mock
    // configurations while remaining cheap. The bound is enforced by registeredTemplates (a
    // synchronized access-ordered LRU) which, on eviction, removes the body from the string
    // repository so the corresponding entry also ages out of the engine's resource cache (itself
    // sized to the same bound). The repository key is the template string itself, so distinct
    // templates can never collide onto the same cached AST. Falls back to the re-parsing evaluate(...)
    // path if the string loader is unavailable, keeping behaviour identical.
    static final int PARSED_TEMPLATE_CACHE_MAX = 1000;
    private static final AtomicLong ENGINE_INSTANCE_COUNTER = new AtomicLong();
    private final StringResourceRepository templateRepository;
    private final String templateRepositoryName;
    // template strings currently registered in the repository; access-ordered LRU bounded at
    // PARSED_TEMPLATE_CACHE_MAX, wrapped in Collections.synchronizedMap so concurrent renders read and
    // insert safely. The value is unused (a marker) — the key is the template string.
    private final Map<String, Boolean> registeredTemplates;

    public VelocityTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        // unique per engine instance so concurrently-constructed engines never share a repository
        this.templateRepositoryName = "mockserver-velocity-templates-" + ENGINE_INSTANCE_COUNTER.incrementAndGet();
        velocityEngine = buildVelocityEngine(configuration);
        toolManager = buildToolManager(velocityEngine);
        // Build the shared, request-independent function/helper map once. It is referenced (not copied)
        // by every render via context chaining and is never mutated after construction, so it is safe for
        // concurrent reads. unmodifiableMap makes that read-only contract explicit and fail-fast.
        Map<String, Object> functionsAndHelpers = new LinkedHashMap<>(
            TemplateFunctions.BUILT_IN_FUNCTIONS.size() + TemplateFunctions.BUILT_IN_HELPERS.size());
        functionsAndHelpers.putAll(TemplateFunctions.BUILT_IN_FUNCTIONS);
        functionsAndHelpers.putAll(TemplateFunctions.BUILT_IN_HELPERS);
        // When a non-zero templateFakerSeed is configured, replace the shared unseeded $faker with a
        // per-engine seeded faker so faker-driven templates generate reproducible fixtures. Seed 0
        // (the default) keeps the shared unseeded faker, so behaviour is unchanged.
        if (configuration.templateFakerSeed() != 0L) {
            functionsAndHelpers.put("faker", TemplateFunctions.resolveFaker(configuration.templateFakerSeed()));
        }
        this.sharedFunctionsAndHelpers = Collections.unmodifiableMap(functionsAndHelpers);
        this.templateRepository = StringResourceLoader.getRepository(templateRepositoryName);
        this.registeredTemplates = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                if (size() > PARSED_TEMPLATE_CACHE_MAX) {
                    // drop the body from the string repository so the matching parsed Template ages
                    // out of the engine's resource cache too — keeps both caches bounded together.
                    if (templateRepository != null) {
                        templateRepository.removeStringResource(eldest.getKey());
                    }
                    return true;
                }
                return false;
            }
        });
    }

    /**
     * Return Velocity's own parsed-and-initialised {@link Template} for the given template string,
     * parsing it at most once per distinct string. The first time a string is seen it is stored in the
     * engine's {@link StringResourceRepository} keyed by the template string itself, and
     * {@code getTemplate(...)} builds and caches the AST in the engine's resource cache; subsequent
     * calls return the same cached Template. The returned Template is rendered concurrently by request
     * threads via {@link Template#merge}, exactly as Velocity renders any cached file/classpath
     * template — so {@code #stop}, macros and scope control behave identically to
     * {@code velocityEngine.evaluate(...)}.
     * <p>
     * Returns null when no string repository is available, or when the template fails to parse/init,
     * in which case callers fall back to the re-parsing {@code evaluate(...)} path. Falling back on a
     * parse failure is deliberate: {@code evaluate(...)} reports parse errors using the logical name
     * {@code "VelocityResponseTemplate"} (rather than the cache key), so the user-facing error message
     * stays byte-for-byte identical to the original path. Invalid templates are rare and never cached,
     * so this fallback costs nothing on the hot path.
     */
    private Template parsedTemplate(String template) {
        if (templateRepository == null) {
            return null;
        }
        // Ensure the body is in the repository BEFORE getTemplate(...) reads it. Storing is idempotent
        // (same content) and, because modification_check_interval=0, never invalidates an already-parsed
        // AST in the resource cache — so re-storing a body re-renders from the cached AST. Storing
        // unconditionally (rather than only on first registration) avoids a race where one thread marks
        // the template registered while another stores the body, leaving getTemplate(...) momentarily
        // unable to find the resource.
        templateRepository.putStringResource(template, template, StandardCharsets.UTF_8.name());
        // touch the access-ordered LRU so this template is most-recently-used for bound accounting
        registeredTemplates.put(template, Boolean.TRUE);
        try {
            return velocityEngine.getTemplate(template, StandardCharsets.UTF_8.name());
        } catch (Exception parseOrLoadFailure) {
            // drop the bad entry so it is neither cached nor counted against the bound, then let the
            // caller reproduce the original error message via evaluate(...)
            registeredTemplates.remove(template);
            templateRepository.removeStringResource(template);
            return null;
        }
    }

    /**
     * Render the template into the writer using the parse-once cached {@link Template} when one is
     * available, falling back to the re-parsing {@code evaluate(...)} path otherwise (no string
     * repository, or a template that fails to parse). Both paths use Velocity's own render entry
     * point, so the output — and any surfaced parse error — is byte-for-byte identical to
     * {@code velocityEngine.evaluate(context, writer, "VelocityResponseTemplate", template)}.
     */
    private void renderTemplate(String template, VelocityContext context, Writer writer) throws Exception {
        Template parsed = parsedTemplate(template);
        if (parsed != null) {
            parsed.merge(context, writer);
        } else {
            velocityEngine.evaluate(context, writer, "VelocityResponseTemplate", template);
        }
    }

    private VelocityEngine buildVelocityEngine(Configuration configuration) {
        VelocityEngine velocityEngine;

        // See: https://velocity.apache.org/engine/2.0/configuration.html
        Properties velocityProperties = new Properties();
        velocityProperties.put(RuntimeConstants.RUNTIME_LOG_REFERENCE_LOG_INVALID, "true");
        velocityProperties.put(RuntimeConstants.RUNTIME_STRING_INTERNING, "true");
        velocityProperties.put(RuntimeConstants.MAX_NUMBER_LOOPS, "-1");
        velocityProperties.put(RuntimeConstants.CHECK_EMPTY_OBJECTS, "true");
        velocityProperties.put(RuntimeConstants.PARSE_DIRECTIVE_MAXDEPTH, "10");
        velocityProperties.put(RuntimeConstants.RUNTIME_REFERENCES_STRICT, "false");
        velocityProperties.put("context.scope_control.template", "false");
        velocityProperties.put("context.scope_control.evaluate", "false");
        velocityProperties.put("context.scope_control.foreach", "true");
        velocityProperties.put("context.scope_control.macro", "false");
        velocityProperties.put("context.scope_control.define", "false");
        velocityProperties.put("directive.set.null.allowed", "true");
        velocityProperties.put(RuntimeConstants.INTERPOLATE_STRINGLITERALS, "true");
        velocityProperties.put(RuntimeConstants.INPUT_ENCODING, "UTF-8");
        velocityProperties.put(RuntimeConstants.PARSER_POOL_CLASS, org.apache.velocity.runtime.ParserPoolImpl.class.getName());
        velocityProperties.put(RuntimeConstants.PARSER_POOL_SIZE, "50");
        velocityProperties.put(RuntimeConstants.SPACE_GOBBLING, "lines");
        velocityProperties.put(RuntimeConstants.PARSER_HYPHEN_ALLOWED, "true");
        velocityProperties.put(RuntimeConstants.CUSTOM_DIRECTIVES, Ifnull.class.getName());
        velocityProperties.put(RuntimeConstants.RESOURCE_MANAGER_CLASS, org.apache.velocity.runtime.resource.ResourceManagerImpl.class.getName());
        velocityProperties.put(RuntimeConstants.RESOURCE_MANAGER_CACHE_CLASS, org.apache.velocity.runtime.resource.ResourceCacheImpl.class.getName());
        // bound the engine's parsed-Template (AST) cache with a native LRU matching the cache we keep
        // in registeredTemplates, so a flood of distinct templates cannot grow memory without limit.
        velocityProperties.put(RuntimeConstants.RESOURCE_MANAGER_DEFAULTCACHE_SIZE, String.valueOf(PARSED_TEMPLATE_CACHE_MAX));
        velocityProperties.put("resource.loader.file.class", org.apache.velocity.runtime.resource.loader.FileResourceLoader.class.getName());
        // Register a string resource loader so each distinct template string is parsed once and the
        // initialised AST is cached + rendered through Velocity's own getTemplate(...) + Template.merge(...).
        // A unique, static, per-engine repository name avoids any cross-engine key collisions while still
        // letting us obtain the repository through the public StringResourceLoader.getRepository(name) API
        // (no reflection). cache=true keeps the parsed Template in the resource cache;
        // modification_check_interval=0 means a registered body is never re-checked for changes (bodies are
        // immutable once registered), so the cached AST is served directly on every subsequent render.
        velocityProperties.put(RuntimeConstants.RESOURCE_LOADERS, "string");
        velocityProperties.put("resource.loader.string.class", StringResourceLoader.class.getName());
        velocityProperties.put("resource.loader.string.repository.class", org.apache.velocity.runtime.resource.util.StringResourceRepositoryImpl.class.getName());
        velocityProperties.put("resource.loader.string.repository.static", "true");
        velocityProperties.put("resource.loader.string.repository.name", templateRepositoryName);
        velocityProperties.put("resource.loader.string.repository.encoding", "UTF-8");
        velocityProperties.put("resource.loader.string.cache", "true");
        velocityProperties.put("resource.loader.string.modification_check_interval", "0");
        if (configuration.velocityDisallowClassLoading()) {
            velocityProperties.put(RuntimeConstants.UBERSPECT_CLASSNAME, SecureUberspector.class.getName());
        } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("Velocity template class loading is enabled (mockserver.velocityDisallowClassLoading=false). Templates can instantiate arbitrary Java classes — only use Velocity templates from trusted sources, or set mockserver.velocityDisallowClassLoading=true.")
            );
        }
        velocityEngine = new VelocityEngine();
        velocityEngine.init(velocityProperties);

        return velocityEngine;
    }

    private ToolManager buildToolManager(VelocityEngine velocityEngine) {
        ToolManager manager = new ToolManager();
        // ToolboxConfiguration for "application" scope
        ToolboxConfiguration applicationToolboxConfiguration = new ToolboxConfiguration();
        applicationToolboxConfiguration.setScope("application");
        ToolConfiguration collectionTool = new ToolConfiguration();
        collectionTool.setClass(org.apache.velocity.tools.generic.CollectionTool.class.getName());
        applicationToolboxConfiguration.addTool(collectionTool);
        ToolConfiguration comparisonDateTool = new ToolConfiguration();
        comparisonDateTool.setClass(org.apache.velocity.tools.generic.ComparisonDateTool.class.getName());
        applicationToolboxConfiguration.addTool(comparisonDateTool);
        ToolConfiguration displayTool = new ToolConfiguration();
        displayTool.setClass(org.apache.velocity.tools.generic.DisplayTool.class.getName());
        applicationToolboxConfiguration.addTool(displayTool);
        ToolConfiguration escapeTool = new ToolConfiguration();
        escapeTool.setClass(org.apache.velocity.tools.generic.EscapeTool.class.getName());
        applicationToolboxConfiguration.addTool(escapeTool);
        ToolConfiguration mathTool = new ToolConfiguration();
        mathTool.setClass(org.apache.velocity.tools.generic.MathTool.class.getName());
        applicationToolboxConfiguration.addTool(mathTool);
        ToolConfiguration numberTool = new ToolConfiguration();
        numberTool.setClass(org.apache.velocity.tools.generic.NumberTool.class.getName());
        applicationToolboxConfiguration.addTool(numberTool);

        // ToolboxConfiguration for "request" scope
        ToolboxConfiguration requestToolboxConfiguration = new ToolboxConfiguration();
        requestToolboxConfiguration.setScope("request");
        ToolConfiguration jsonTool = new ToolConfiguration();
        jsonTool.setClass(org.apache.velocity.tools.generic.JsonTool.class.getName());
        requestToolboxConfiguration.addTool(jsonTool);
        ToolConfiguration xmlTool = new ToolConfiguration();
        xmlTool.setClass(org.apache.velocity.tools.generic.XmlTool.class.getName());
        requestToolboxConfiguration.addTool(xmlTool);
        // SECURITY: ImportTool ($import.read(...)) is deliberately NOT registered — it lets a template
        // fetch an arbitrary URL or read a local file, which is an SSRF / local-file-read vector. Omit it
        // so Velocity templates cannot reach the network or filesystem through $import.

        XmlFactoryConfiguration xmlFactoryConfiguration = new XmlFactoryConfiguration();
        xmlFactoryConfiguration.addToolbox(applicationToolboxConfiguration);
        xmlFactoryConfiguration.addToolbox(requestToolboxConfiguration);
        manager.configure(xmlFactoryConfiguration);
        manager.setVelocityEngine(velocityEngine);

        return manager;
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
            VelocityContext context = newRenderContext(request);
            if (iteration != null) {
                context.put("iteration", iteration);
            }
            RequestBodyExtractionHelper bodyExtractionHelper = new RequestBodyExtractionHelper(request, mockServerLogger);
            context.put("jsonPath", new RequestBodyExtractionHelper.JsonPathTool(bodyExtractionHelper));
            context.put("xPath", new RequestBodyExtractionHelper.XPathTool(bodyExtractionHelper));
            renderTemplate(template, context, writer);
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
            VelocityContext context = newRenderContext(request);
            if (response != null) {
                context.put("response", new HttpResponseTemplateObject(response));
            }
            RequestBodyExtractionHelper bodyExtractionHelper = new RequestBodyExtractionHelper(request, mockServerLogger);
            context.put("jsonPath", new RequestBodyExtractionHelper.JsonPathTool(bodyExtractionHelper));
            context.put("xPath", new RequestBodyExtractionHelper.XPathTool(bodyExtractionHelper));
            renderTemplate(template, context, writer);
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

    /**
     * Build the per-render context chain for the given request with the {@code request} binding already
     * in place. The chain is, outermost first:
     * <pre>
     *   child(request, + caller adds iteration/response/jsonPath/xPath) -&gt;
     *     functionsLayer(own map = the shared sharedFunctionsAndHelpers, read-only) -&gt;
     *       perRenderToolContext(tools)
     * </pre>
     * Only the per-render bindings live in the outermost child; the stateless function/helper map is
     * referenced (never copied) and the request-scoped tool context is created fresh per render. Reads
     * fall through the chain; every {@code put} during a render targets only the outermost child, so the
     * shared map and its layer are never mutated. Output is byte-for-byte identical to copying every
     * binding into a single flat context.
     */
    private VelocityContext newRenderContext(HttpRequest request) {
        // fresh per render: $json/$xml are request-scoped and hold per-request parse state
        VelocityContext functionsLayer = new VelocityContext(sharedFunctionsAndHelpers, toolManager.createContext());
        VelocityContext context = new VelocityContext(functionsLayer);
        context.put("request", new HttpRequestTemplateObject(request));
        return context;
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.velocityDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.velocityDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }
}
