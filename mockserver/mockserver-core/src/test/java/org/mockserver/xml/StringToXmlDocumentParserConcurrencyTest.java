package org.mockserver.xml;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The XML parser shares one hardened factory per namespace mode across threads; every parse must
 * still produce its own document, keep the hardening, and keep the namespace mode it asked for. The
 * shared compiled XPath expression must likewise give each concurrent caller the answer for its own
 * document.
 */
public class StringToXmlDocumentParserConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ITERATIONS = 200;

    private static String document(int thread, int iteration) {
        return "<order thread=\"" + thread + "\"><id>" + iteration + "</id><item sku=\"S-" + thread + "-" + iteration + "\"/></order>";
    }

    private static <T> List<T> runConcurrently(ThreadTask<T> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                int threadNumber = thread;
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.run(threadNumber);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    interface ThreadTask<T> {
        T run(int thread) throws Exception;
    }

    @Test
    public void concurrentParsesEachProduceTheirOwnDocument() throws Exception {
        List<String> failures = runConcurrently(thread -> {
            StringToXmlDocumentParser parser = new StringToXmlDocumentParser();
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                boolean namespaceAware = iteration % 2 == 0;
                Document document = parser.buildDocument(document(thread, iteration), (xml, exception, level) -> {
                    throw new AssertionError(exception);
                }, namespaceAware);
                String sku = document.getDocumentElement().getElementsByTagName("item").item(0).getAttributes().getNamedItem("sku").getNodeValue();
                if (!sku.equals("S-" + thread + "-" + iteration)) {
                    return "thread " + thread + " iteration " + iteration + " read " + sku;
                }
            }
            return null;
        });
        for (String failure : failures) {
            assertThat(failure, nullValue());
        }
    }

    @Test
    public void sharedFactoriesKeepTheirNamespaceMode() throws Exception {
        String xml = "<ns:order xmlns:ns=\"urn:test\"><ns:id>1</ns:id></ns:order>";
        StringToXmlDocumentParser parser = new StringToXmlDocumentParser();

        Document aware = parser.buildDocument(xml, (x, e, l) -> {
        }, true);
        Document unaware = parser.buildDocument(xml, (x, e, l) -> {
        }, false);

        assertThat(aware.getDocumentElement().getNamespaceURI(), is("urn:test"));
        assertThat(aware.getDocumentElement().getLocalName(), is("order"));
        assertThat(unaware.getDocumentElement().getNamespaceURI(), nullValue());
        assertThat(unaware.getDocumentElement().getTagName(), is("ns:order"));
    }

    @Test
    public void sharedFactoriesStillRejectADoctype() {
        AtomicReference<Exception> fatal = new AtomicReference<>();
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE order [<!ENTITY x \"y\">]><order>&x;</order>";
        for (boolean namespaceAware : new boolean[]{false, true}) {
            fatal.set(null);
            try {
                new StringToXmlDocumentParser().buildDocument(xml, (x, e, level) -> {
                    if (level == StringToXmlDocumentParser.ErrorLevel.FATAL_ERROR) {
                        fatal.set(e);
                    }
                }, namespaceAware);
            } catch (Exception ignore) {
                // the parse is expected to fail; the fatal error is what is asserted
            }
            assertThat(fatal.get(), notNullValue());
            assertThat(fatal.get().getMessage(), containsString("DOCTYPE is disallowed"));
        }
    }

    @Test
    public void sharedXPathExpressionAnswersEachConcurrentCallerForItsOwnDocument() throws Exception {
        XPathEvaluator plain = new XPathEvaluator("string(/order/item/@sku)", null, configuration());
        XPathEvaluator namespaced = new XPathEvaluator("string(/n:order/n:item/@sku)", ImmutableMap.of("n", "urn:test"), configuration());
        List<String> failures = runConcurrently(thread -> {
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                String expected = "S-" + thread + "-" + iteration;
                Object plainResult = plain.evaluateXPathExpression(document(thread, iteration), (x, e, l) -> {
                    throw new AssertionError(e);
                }, XPathConstants.STRING);
                String namespacedXml = "<n:order xmlns:n=\"urn:test\"><n:item sku=\"" + expected + "\"/></n:order>";
                Object namespacedResult = namespaced.evaluateXPathExpression(namespacedXml, (x, e, l) -> {
                    throw new AssertionError(e);
                }, XPathConstants.STRING);
                if (!expected.equals(plainResult) || !expected.equals(namespacedResult)) {
                    return "thread " + thread + " iteration " + iteration + " read " + plainResult + " / " + namespacedResult;
                }
            }
            return null;
        });
        for (String failure : failures) {
            assertThat(failure, nullValue());
        }
    }
}
