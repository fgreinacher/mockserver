package org.mockserver.templates.engine.velocity;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Differential/output-parity corpus for {@link VelocityTemplateEngine}, focused on the template-cache
 * keying change (A2/A3). It pins that distinct templates never render each other's content (the synthetic
 * per-template name must never collide), that a repeated render of one template is stable on the warm
 * cache, that request-scoped extraction still resolves, and that a parse failure is followed by a clean
 * render of a valid template (the fallback path must not corrupt the cache).
 */
public class VelocityTemplateEngineOutputParityTest {

    private static final Configuration configuration = configuration();

    @Mock
    private MockServerLogger mockServerLogger;

    private VelocityTemplateEngine engine;

    @Before
    public void setUp() {
        openMocks(this);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        engine = new VelocityTemplateEngine(mockServerLogger, configuration);
    }

    @Test
    public void resolvedAndUndefinedReferences() {
        HttpRequest request = request().withMethod("GET").withPath("/api/orders");
        assertThat(engine.renderTemplate("method=$request.method path=$request.path", request),
            is("method=GET path=/api/orders"));
        assertThat(engine.renderTemplate("[$does_not_exist]", request), is("[$does_not_exist]"));
    }

    @Test
    public void distinctTemplatesNeverCollide() {
        HttpRequest request = request().withMethod("GET").withPath("/p");
        for (int i = 0; i < 25; i++) {
            assertThat(engine.renderTemplate("tmpl-" + i + "=$request.path", request), is("tmpl-" + i + "=/p"));
        }
        // re-render the first few after later ones registered: each must still be its own content
        assertThat(engine.renderTemplate("tmpl-0=$request.path", request), is("tmpl-0=/p"));
        assertThat(engine.renderTemplate("tmpl-3=$request.path", request), is("tmpl-3=/p"));
    }

    @Test
    public void warmCacheRepeatedRenderIsStable() {
        HttpRequest request = request().withMethod("POST").withPath("/x");
        String template = "m=$request.method,p=$request.path";
        assertThat(engine.renderTemplate(template, request), is("m=POST,p=/x"));
        assertThat(engine.renderTemplate(template, request), is("m=POST,p=/x"));
        assertThat(engine.renderTemplate(template, request), is("m=POST,p=/x"));
    }

    @Test
    public void jsonPathForeachLoop() {
        HttpRequest request = request().withMethod("POST").withPath("/x").withBody("{\"items\":[\"a\",\"b\",\"c\"]}");
        assertThat(engine.renderTemplate("#foreach($i in $jsonPath.find('$.items'))$i,#end", request),
            is("a,b,c,"));
    }

    @Test
    public void builtInFunctionRemainsBound() {
        HttpRequest request = request().withMethod("GET").withPath("/x");
        assertThat(engine.renderTemplate("$uuid", request),
            matchesPattern("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
    }

    @Test
    public void validRenderRecoversAfterParseFailure() {
        HttpRequest request = request().withMethod("GET").withPath("/p");
        assertThrows(RuntimeException.class, () -> engine.renderTemplate("#if {", request));
        assertThat(engine.renderTemplate("ok=$request.path", request), is("ok=/p"));
    }
}
