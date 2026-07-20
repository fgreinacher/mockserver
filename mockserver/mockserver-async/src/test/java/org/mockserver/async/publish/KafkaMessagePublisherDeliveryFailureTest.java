package org.mockserver.async.publish;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * A Kafka send is asynchronous, so a caller that reports "published" without flushing is
 * reporting success before the broker has accepted — or rejected — the message. Delivery
 * failures must reach the caller through {@link MessagePublisher#flush()}, not be left in a
 * log line behind an already-sent success response.
 */
public class KafkaMessagePublisherDeliveryFailureTest {

    @Mock
    private KafkaProducer<String, String> mockProducer;

    private KafkaMessagePublisher publisher;

    @Before
    public void setUp() {
        openMocks(this);
        publisher = new KafkaMessagePublisher(mockProducer);
    }

    @Test
    public void flushShouldDrainTheProducer() {
        publisher.publish("my-topic", "{\"a\":1}");

        publisher.flush();

        verify(mockProducer).flush();
    }

    @Test
    public void flushShouldReportADeliveryFailure() {
        failNextSendWith(new org.apache.kafka.common.errors.TimeoutException("broker unreachable"));

        publisher.publish("my-topic", "{\"a\":1}");

        RuntimeException failure = null;
        try {
            publisher.flush();
        } catch (RuntimeException e) {
            failure = e;
        }

        assertThat("a failed delivery must be reported to the caller, not only logged",
            failure, is(notNullValue()));
        assertThat(failure.getMessage(), containsString("my-topic"));
        assertThat(failure.getMessage(), containsString("broker unreachable"));
    }

    @Test
    public void flushShouldNotThrowWhenEveryDeliverySucceeded() {
        publisher.publish("my-topic", "{\"a\":1}");

        publisher.flush();
    }

    @Test
    public void flushShouldNotRepeatAPreviouslyReportedFailure() {
        failNextSendWith(new org.apache.kafka.common.errors.TimeoutException("broker unreachable"));
        publisher.publish("my-topic", "{\"a\":1}");
        try {
            publisher.flush();
        } catch (RuntimeException expected) {
            // reported once
        }

        // a later, successful flush must not resurface the old failure
        publisher.flush();
    }

    private void failNextSendWith(Exception cause) {
        doAnswer(invocation -> {
            Callback callback = invocation.getArgument(1);
            callback.onCompletion(null, cause);
            return null;
        }).when(mockProducer).send(any(ProducerRecord.class), any(Callback.class));
    }
}
