package org.mockserver.xml;

import org.junit.Test;

import javax.xml.xpath.XPathConstants;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class XPathEvaluatorTest {

    @Test
    public void shouldMatchMatchingXPath() {
        String xml = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";

        evaluateXPath(xml, "/element[key = 'some_key' and value = 'some_value']", "   some_key   some_value");
        evaluateXPath(xml, "/element[key = 'some_key']", "   some_key   some_value");
        evaluateXPath(xml, "/element/key", "some_key");
        evaluateXPath(xml, "/element[key and value]", "   some_key   some_value");
    }

    private void evaluateXPath(String matched, String expression, String expected) {
        AtomicReference<Throwable> throwable = new AtomicReference<>();
        assertThat(new XPathEvaluator(expression, null).evaluateXPathExpression(matched, (xmlAsString, exception, level) -> throwable.set(exception), XPathConstants.STRING), is(expected));
        if (throwable.get() != null) {
            throw new RuntimeException(throwable.get().getMessage(), throwable.get());
        }
    }

    @Test
    public void shouldReturnTypeAppropriateSentinelOnTimeoutForBooleanReturnType() {
        // Build a synthetic large XML document and an XPath with a quadratic predicate so
        // the timeout fires reliably even on fast machines.
        long previous = org.mockserver.configuration.ConfigurationProperties.xpathMatchingTimeoutMillis();
        try {
            org.mockserver.configuration.ConfigurationProperties.xpathMatchingTimeoutMillis(10L);
            String xml = buildSlowXml(800);
            AtomicReference<Throwable> throwable = new AtomicReference<>();
            long start = System.nanoTime();
            Object result = new XPathEvaluator("count(/root/n[@id = //n/@id]) > 0", null)
                .evaluateXPathExpression(xml, (xmlAsString, exception, level) -> throwable.set(exception), XPathConstants.BOOLEAN);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            // contract: must not hang, and must return a Boolean rather than null so callers can unbox safely.
            assertThat(elapsedMs < 5_000L, is(true));
            assertThat(result instanceof Boolean, is(true));
        } finally {
            org.mockserver.configuration.ConfigurationProperties.xpathMatchingTimeoutMillis(previous);
        }
    }

    private static String buildSlowXml(int nodes) {
        StringBuilder sb = new StringBuilder("<root>");
        for (int i = 0; i < nodes; i++) {
            sb.append("<n id=\"").append(i).append("\">v").append(i).append("</n>");
        }
        sb.append("</root>");
        return sb.toString();
    }

}