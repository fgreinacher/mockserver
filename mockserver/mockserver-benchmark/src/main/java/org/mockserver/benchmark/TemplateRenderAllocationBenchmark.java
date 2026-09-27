package org.mockserver.benchmark;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.javascript.JavaScriptTemplateEngine;
import org.mockserver.templates.engine.mustache.MustacheTemplateEngine;
import org.mockserver.templates.engine.velocity.VelocityTemplateEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

import static org.mockserver.model.HttpRequest.request;

/**
 * Allocation micro-benchmark for the response-templating render path — the per-render work each
 * {@link TemplateEngine} performs when a matched template is rendered against a request. It exists so
 * an allocation figure can be attributed to the render path of a given engine; the whole-programme
 * gate covered matching, inbound decode and response write, but no render arm.
 *
 * <p>The engine instance is built once and warmed in {@code setup} (the parsed/compiled template is
 * cached after the first render, mirroring steady-state production), so each measured op is one render
 * of an already-warm template — the state that dominates a running mock. The {@code gc.alloc.rate.norm}
 * (bytes/op) column is the headline number:
 *
 * <pre>./run.sh -prof gc TemplateRenderAllocationBenchmark</pre>
 *
 * <p>All three engines are exercised through {@link TemplateEngine#renderTemplateText} so the same
 * call drives the text-render path on Velocity and Mustache and the {@code handle(request)} path on
 * JavaScript. The JavaScript arm needs GraalJS on the classpath (declared by this module); with it
 * absent the engine throws by design and that arm cannot be measured. BASELINE ONLY — no production
 * code is changed by this benchmark.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TemplateRenderAllocationBenchmark {

    @Param({"MUSTACHE", "VELOCITY", "JAVASCRIPT"})
    private String engine;

    private static final String MUSTACHE_TEMPLATE =
        "{\"path\":\"{{request.path}}\",\"method\":\"{{request.method}}\",\"id\":\"{{uuid}}\",\"n\":\"{{rand_int_100}}\"}";

    private static final String VELOCITY_TEMPLATE =
        "{\"path\":\"$request.path\",\"method\":\"$request.method\",\"id\":\"$uuid\",\"n\":\"$rand_int_100\"}";

    private static final String JAVASCRIPT_TEMPLATE =
        "return JSON.stringify({path: request.path, method: request.method});";

    private TemplateEngine templateEngine;
    private String template;
    private HttpRequest renderContext;

    @Setup
    public void setup() {
        Configuration configuration = Configuration.configuration();
        MockServerLogger logger = new MockServerLogger();
        renderContext = request()
            .withMethod("POST")
            .withPath("/api/orders")
            .withHeader("Host", "upstream.local:1090")
            .withHeader("Content-Type", "application/json")
            .withBody("{\"sku\":\"ABC-123\",\"qty\":2}");
        switch (engine) {
            case "MUSTACHE":
                templateEngine = new MustacheTemplateEngine(logger, configuration);
                template = MUSTACHE_TEMPLATE;
                break;
            case "VELOCITY":
                templateEngine = new VelocityTemplateEngine(logger, configuration);
                template = VELOCITY_TEMPLATE;
                break;
            case "JAVASCRIPT":
                templateEngine = new JavaScriptTemplateEngine(logger, configuration);
                template = JAVASCRIPT_TEMPLATE;
                break;
            default:
                throw new IllegalArgumentException("unknown engine: " + engine);
        }
        // warm the parse/compile cache so a measured op is a steady-state render, not a first parse
        templateEngine.renderTemplateText(template, renderContext);
    }

    @Benchmark
    public void render(Blackhole bh) {
        bh.consume(templateEngine.renderTemplateText(template, renderContext));
    }
}
