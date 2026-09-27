package org.mockserver.templates.engine.velocity;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertThrows;
import static org.mockserver.model.HttpRequest.request;

/**
 * Hardens the template-cache keying change (A2/A3): distinct templates are registered under short
 * synthetic names, the {@code templateNames} LRU and the string repository are bounded, and correctness
 * holds across eviction, under concurrency, across an engine rebuild, and after a parse failure. Bounds
 * are read reflectively so a wrong-key eviction (which leaks the repository while output stays correct)
 * is still caught.
 */
public class VelocityTemplateCacheHardeningTest {

    private static final int CAP = VelocityTemplateEngine.PARSED_TEMPLATE_CACHE_MAX;
    private final MockServerLogger mockServerLogger = new MockServerLogger();

    private static Object engineHolder(VelocityTemplateEngine engine) throws Exception {
        Field field = VelocityTemplateEngine.class.getDeclaredField("engineHolder");
        field.setAccessible(true);
        return field.get(engine);
    }

    private static Map<?, ?> templateNames(VelocityTemplateEngine engine) throws Exception {
        Object holder = engineHolder(engine);
        Field field = holder.getClass().getDeclaredField("templateNames");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(holder);
    }

    private static int repositorySize(VelocityTemplateEngine engine) throws Exception {
        Object holder = engineHolder(engine);
        Field repositoryField = holder.getClass().getDeclaredField("templateRepository");
        repositoryField.setAccessible(true);
        Object repository = repositoryField.get(holder);
        Field mapField;
        try {
            mapField = repository.getClass().getDeclaredField("resources");
        } catch (NoSuchFieldException e) {
            mapField = null;
            for (Field candidate : repository.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(candidate.getType())) {
                    mapField = candidate;
                    break;
                }
            }
            if (mapField == null) {
                throw new AssertionError("could not locate the string repository's backing map");
            }
        }
        mapField.setAccessible(true);
        return ((Map<?, ?>) mapField.get(repository)).size();
    }

    @Test
    public void evictionAcrossCapKeepsEveryTemplateCorrectAndBounded() throws Exception {
        VelocityTemplateEngine engine = new VelocityTemplateEngine(mockServerLogger, Configuration.configuration());
        HttpRequest request = request().withPath("/p");
        int total = CAP + 200;
        for (int i = 0; i < total; i++) {
            assertThat(engine.renderTemplate("tmpl-" + i + "=$request.path", request), is("tmpl-" + i + "=/p"));
        }
        // re-render early (long-evicted) and recent templates: each must still render its own output
        for (int i : new int[]{0, 1, 5, 50, 500, total - 2, total - 1}) {
            assertThat(engine.renderTemplate("tmpl-" + i + "=$request.path", request), is("tmpl-" + i + "=/p"));
        }
        // both the name map and the string repository must stay bounded, not grow with the templates seen
        assertThat(templateNames(engine).size(), lessThanOrEqualTo(CAP));
        assertThat(repositorySize(engine), lessThanOrEqualTo(CAP + 5));
    }

    @Test
    public void concurrentDistinctTemplatesAcrossEvictionStayCorrect() throws Exception {
        VelocityTemplateEngine engine = new VelocityTemplateEngine(mockServerLogger, Configuration.configuration());
        int threads = 50;
        int perThread = 30;
        int rounds = 5;
        int distinct = threads * perThread; // 1500 > CAP, so eviction churns while all threads render
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int base = t * perThread;
                tasks.add(() -> {
                    HttpRequest request = request().withPath("/p");
                    for (int round = 0; round < rounds; round++) {
                        for (int i = base; i < base + perThread; i++) {
                            String rendered = engine.renderTemplate("c-" + i + "=$request.path", request);
                            if (!("c-" + i + "=/p").equals(rendered)) {
                                return "WRONG at " + i + " -> " + rendered;
                            }
                        }
                    }
                    return "OK";
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            List<String> failures = new ArrayList<>();
            for (Future<String> future : futures) {
                String result = future.get();
                if (!"OK".equals(result)) {
                    failures.add(result);
                }
            }
            assertThat(failures, is(empty()));
        } finally {
            pool.shutdownNow();
        }
        assertThat(templateNames(engine).size(), lessThanOrEqualTo(CAP));
        assertThat(distinct > CAP, is(true));
    }

    @Test
    public void sandboxToggleRebuildKeepsOutputsCorrect() {
        Configuration configuration = Configuration.configuration();
        Boolean original = configuration.velocityDisallowClassLoading();
        configuration.velocityDisallowClassLoading(false);
        try {
            VelocityTemplateEngine engine = new VelocityTemplateEngine(mockServerLogger, configuration);
            HttpRequest request = request().withPath("/p");
            for (int i = 0; i < 10; i++) {
                assertThat(engine.renderTemplate("r-" + i + "=$request.path", request), is("r-" + i + "=/p"));
            }
            // flip the sandbox flag on the SAME engine, forcing a rebuild (fresh holder/repository/counter)
            configuration.velocityDisallowClassLoading(true);
            for (int i = 10; i < 20; i++) {
                assertThat(engine.renderTemplate("r-" + i + "=$request.path", request), is("r-" + i + "=/p"));
            }
            for (int i = 0; i < 5; i++) {
                assertThat(engine.renderTemplate("r-" + i + "=$request.path", request), is("r-" + i + "=/p"));
            }
            // flip back and render more distinct templates
            configuration.velocityDisallowClassLoading(false);
            for (int i = 20; i < 30; i++) {
                assertThat(engine.renderTemplate("r-" + i + "=$request.path", request), is("r-" + i + "=/p"));
            }
            for (int i = 10; i < 15; i++) {
                assertThat(engine.renderTemplate("r-" + i + "=$request.path", request), is("r-" + i + "=/p"));
            }
        } finally {
            configuration.velocityDisallowClassLoading(original);
        }
    }

    @Test
    public void parseFailureRecoversForSameAndDifferentTemplates() {
        VelocityTemplateEngine engine = new VelocityTemplateEngine(mockServerLogger, Configuration.configuration());
        HttpRequest request = request().withPath("/p");
        assertThrows(RuntimeException.class, () -> engine.renderTemplate("#if {", request));
        // the same invalid body is still invalid (must throw, not render a stale/other template)
        assertThrows(RuntimeException.class, () -> engine.renderTemplate("#if {", request));
        // valid templates before and after the failure render their own output; enough distinct ones that
        // a name-reuse bug would collide
        assertThat(engine.renderTemplate("ok-A=$request.path", request), is("ok-A=/p"));
        assertThat(engine.renderTemplate("ok-B=$request.path", request), is("ok-B=/p"));
        assertThat(engine.renderTemplate("ok-A=$request.path", request), is("ok-A=/p"));
        for (int i = 0; i < 20; i++) {
            assertThat(engine.renderTemplate("post-" + i + "=$request.path", request), is("post-" + i + "=/p"));
        }
    }
}
