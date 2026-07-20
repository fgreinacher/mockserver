package org.mockserver.async.publish;

/**
 * A delivery failure reported asynchronously by a broker client's send callback,
 * held until the next {@link MessagePublisher#flush()} can report it to the caller.
 *
 * <p>Without this, an async send failure is only logged — and the control plane answers
 * "published" for a message the broker never accepted.
 */
final class DeliveryFailure {

    private final String channel;
    private final Exception cause;

    DeliveryFailure(String channel, Exception cause) {
        this.channel = channel;
        this.cause = cause;
    }

    String getChannel() {
        return channel;
    }

    Exception getCause() {
        return cause;
    }

    /** Build the exception reported to the caller for this failed delivery. */
    RuntimeException asRuntimeException(String description) {
        return new RuntimeException("Failed to deliver " + description + " to Kafka topic '"
            + channel + "': " + cause.getMessage(), cause);
    }
}
