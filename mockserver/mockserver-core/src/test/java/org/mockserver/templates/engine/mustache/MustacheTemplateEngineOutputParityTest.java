package org.mockserver.templates.engine.mustache;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.load.IterationContext;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Differential/output-parity corpus for {@link MustacheTemplateEngine}. It pins the rendered output of
 * a corpus that spans the dimensions the per-render binding change touches — resolved and missing
 * variables, nested request fields, HTML escaping, section loops driven by the render-time
 * {@code jsonPath} mutation, and the built-in function bindings — so any drift in what a template
 * renders is caught. The cross-render isolation cases prove the render-time {@code jsonPathResult}
 * mutation stays confined to a single render and never leaks into the next one on the same engine.
 */
public class MustacheTemplateEngineOutputParityTest {

    private static final Configuration configuration = configuration();

    @Mock
    private MockServerLogger mockServerLogger;

    private MustacheTemplateEngine engine;

    @Before
    public void setUp() {
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        engine = new MustacheTemplateEngine(mockServerLogger, configuration);
    }

    @Test
    public void resolvedVariables() {
        HttpRequest request = request().withMethod("GET").withPath("/api/orders");
        assertThat(engine.renderTemplate("method={{request.method}} path={{request.path}}", request),
            is("method=GET path=/api/orders"));
    }

    @Test
    public void missingVariableRendersEmpty() {
        HttpRequest request = request().withMethod("GET").withPath("/api/orders");
        assertThat(engine.renderTemplate("[{{does_not_exist}}]", request), is("[]"));
    }

    @Test
    public void iterationContextVariable() {
        HttpRequest request = request().withPath("/item");
        IterationContext iteration = new IterationContext(7, 2, 3, 1234, 42);
        assertThat(engine.renderTemplate("{{iteration.index}}/{{iteration.vuId}}/{{iteration.count}}", request, iteration),
            is("7/2/42"));
    }

    @Test
    public void htmlEscapingIsDefaultAndTripleMustacheIsRaw() {
        HttpRequest request = request().withMethod("GET").withPath("/a<b>&\"c");
        String escaped = engine.renderTemplate("{{request.path}}", request);
        assertThat(escaped, containsString("&lt;b&gt;"));
        assertThat(escaped, not(containsString("<b>")));
        String raw = engine.renderTemplate("{{{request.path}}}", request);
        assertThat(raw, is("/a<b>&\"c"));
    }

    @Test
    public void jsonPathSectionLoop() {
        HttpRequest request = request().withMethod("POST").withPath("/x").withBody("{\"items\":[\"a\",\"b\",\"c\"]}");
        String rendered = engine.renderTemplate(
            "{{#jsonPath}}$.items{{/jsonPath}}[{{#jsonPathResult}}{{.}},{{/jsonPathResult}}]", request);
        assertThat(rendered, is("[a,b,c,]"));
    }

    @Test
    public void builtInFunctionsRemainBound() {
        HttpRequest request = request().withMethod("GET").withPath("/x");
        assertThat(engine.renderTemplate("{{uuid}}", request),
            matchesPattern("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        assertThat(engine.renderTemplate("{{rand_int_100}}", request), matchesPattern("\\d{1,2}"));
    }

    @Test
    public void renderTimeMutationDoesNotLeakIntoNextRender() {
        // first render sets jsonPathResult via the jsonPath lambda
        HttpRequest withItems = request().withMethod("POST").withPath("/x").withBody("{\"items\":[\"a\",\"b\",\"c\"]}");
        assertThat(engine.renderTemplate(
                "{{#jsonPath}}$.items{{/jsonPath}}[{{#jsonPathResult}}{{.}},{{/jsonPathResult}}]", withItems),
            is("[a,b,c,]"));
        // a subsequent render on the SAME engine that never calls jsonPath must see no jsonPathResult
        HttpRequest plain = request().withMethod("GET").withPath("/y");
        assertThat(engine.renderTemplate("[{{#jsonPathResult}}{{.}},{{/jsonPathResult}}]", plain), is("[]"));
        assertThat(engine.renderTemplate("[{{jsonPathResult}}]", plain), is("[]"));
    }

    @Test
    public void distinctTemplatesRenderIndependentlyOnOneEngine() {
        HttpRequest request = request().withMethod("PUT").withPath("/z");
        assertThat(engine.renderTemplate("a={{request.method}}", request), is("a=PUT"));
        assertThat(engine.renderTemplate("b={{request.path}}", request), is("b=/z"));
        assertThat(engine.renderTemplate("a={{request.method}}", request), is("a=PUT"));
    }
}
