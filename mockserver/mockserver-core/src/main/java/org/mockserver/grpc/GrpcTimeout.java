package org.mockserver.grpc;

import java.util.concurrent.TimeUnit;

/**
 * Parses the gRPC {@code grpc-timeout} request header, which carries the client's deadline for the
 * whole RPC.
 * <p>
 * Wire format is a positive integer of at most 8 digits followed by a one-character unit:
 * <pre>
 *   Timeout      = "grpc-timeout" TimeoutValue TimeoutUnit
 *   TimeoutValue = {1..8}Digit
 *   TimeoutUnit  = "H" / "M" / "S" / "m" / "u" / "n"
 * </pre>
 * The units are <strong>case-sensitive</strong>: {@code H}ours, {@code M}inutes, {@code S}econds,
 * {@code m}illiseconds, {@code u}microseconds, {@code n}anoseconds. {@code M} and {@code m} mean
 * very different things (minutes vs milliseconds), which is why this is parsed explicitly rather
 * than with a case-insensitive match.
 * <p>
 * A server that receives a timeout should cancel the RPC and report
 * {@code grpc-status: 4 DEADLINE_EXCEEDED} once it elapses. Before this existed MockServer passed
 * the header through as an ordinary (still matchable) request header but never honoured it, so an
 * expectation whose {@code Delay} exceeded the client's deadline left the client to time out
 * locally while MockServer went on writing to an abandoned stream.
 */
public class GrpcTimeout {

    public static final String GRPC_TIMEOUT_HEADER = "grpc-timeout";

    /**
     * Maximum digits in the timeout value, per the wire specification.
     */
    private static final int MAX_TIMEOUT_DIGITS = 8;

    /**
     * Parses a {@code grpc-timeout} value into nanoseconds.
     *
     * @param value the raw header value, may be {@code null}
     * @return the timeout in nanoseconds, or {@code null} if absent or malformed. A malformed value
     * is treated as "no deadline" rather than as an error: refusing the call would be a harsher
     * response than a real server gives, and the header remains visible for matching.
     */
    public static Long parseNanos(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 2) {
            return null;
        }
        char unit = trimmed.charAt(trimmed.length() - 1);
        String digits = trimmed.substring(0, trimmed.length() - 1);
        if (digits.isEmpty() || digits.length() > MAX_TIMEOUT_DIGITS) {
            return null;
        }
        long amount;
        try {
            amount = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        if (amount < 0) {
            return null;
        }
        switch (unit) {
            case 'H':
                return TimeUnit.HOURS.toNanos(amount);
            case 'M':
                return TimeUnit.MINUTES.toNanos(amount);
            case 'S':
                return TimeUnit.SECONDS.toNanos(amount);
            case 'm':
                return TimeUnit.MILLISECONDS.toNanos(amount);
            case 'u':
                return TimeUnit.MICROSECONDS.toNanos(amount);
            case 'n':
                return amount;
            default:
                return null;
        }
    }

    /**
     * Parses the {@code grpc-timeout} header from a request's headers into nanoseconds.
     *
     * @return the timeout in nanoseconds, or {@code null} if absent or malformed
     */
    public static Long parseNanos(org.mockserver.model.HttpRequest request) {
        if (request == null) {
            return null;
        }
        return parseNanos(request.getFirstHeader(GRPC_TIMEOUT_HEADER));
    }

    /**
     * The {@code grpc-message} MockServer reports when a deadline elapses.
     */
    public static String deadlineExceededMessage(long timeoutNanos) {
        return "deadline exceeded after " + TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + "ms";
    }
}
