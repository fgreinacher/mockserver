package org.mockserver.grpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class GrpcStatusMapper {

    public static final String GRPC_STATUS_HEADER = "grpc-status";
    public static final String GRPC_MESSAGE_HEADER = "grpc-message";
    public static final String GRPC_STATUS_NAME_HEADER = "grpc-status-name";
    public static final String GRPC_CONTENT_TYPE = "application/grpc";
    /**
     * Request header naming the message encoding the client used, e.g. {@code gzip}. The frame's
     * compressed flag says only THAT a message is compressed; this says how.
     */
    public static final String GRPC_ENCODING_HEADER = "grpc-encoding";
    /**
     * Response header advertising the encodings the server can decode, so a client whose preferred
     * encoding is unsupported knows what to retry with.
     */
    public static final String GRPC_ACCEPT_ENCODING_HEADER = "grpc-accept-encoding";

    public enum GrpcStatusCode {
        OK(0, 200),
        CANCELLED(1, 499),
        UNKNOWN(2, 500),
        INVALID_ARGUMENT(3, 400),
        DEADLINE_EXCEEDED(4, 504),
        NOT_FOUND(5, 404),
        ALREADY_EXISTS(6, 409),
        PERMISSION_DENIED(7, 403),
        RESOURCE_EXHAUSTED(8, 429),
        FAILED_PRECONDITION(9, 400),
        ABORTED(10, 409),
        OUT_OF_RANGE(11, 400),
        UNIMPLEMENTED(12, 501),
        INTERNAL(13, 500),
        UNAVAILABLE(14, 503),
        DATA_LOSS(15, 500),
        UNAUTHENTICATED(16, 401);

        private final int code;
        private final int httpStatus;

        GrpcStatusCode(int code, int httpStatus) {
            this.code = code;
            this.httpStatus = httpStatus;
        }

        public int getCode() {
            return code;
        }

        public int getHttpStatus() {
            return httpStatus;
        }
    }

    private static final Map<Integer, GrpcStatusCode> BY_CODE;
    private static final Map<String, GrpcStatusCode> BY_NAME;
    private static final Map<Integer, GrpcStatusCode> HTTP_TO_GRPC;

    static {
        Map<Integer, GrpcStatusCode> byCode = new LinkedHashMap<>();
        Map<String, GrpcStatusCode> byName = new LinkedHashMap<>();
        Map<Integer, GrpcStatusCode> httpToGrpc = new LinkedHashMap<>();
        for (GrpcStatusCode status : GrpcStatusCode.values()) {
            byCode.put(status.code, status);
            byName.put(status.name(), status);
            httpToGrpc.putIfAbsent(status.httpStatus, status);
        }
        BY_CODE = Collections.unmodifiableMap(byCode);
        BY_NAME = Collections.unmodifiableMap(byName);
        HTTP_TO_GRPC = Collections.unmodifiableMap(httpToGrpc);
    }

    public static GrpcStatusCode fromCode(int code) {
        return BY_CODE.getOrDefault(code, GrpcStatusCode.UNKNOWN);
    }

    public static GrpcStatusCode fromName(String name) {
        if (name == null) {
            return GrpcStatusCode.UNKNOWN;
        }
        return BY_NAME.getOrDefault(name.toUpperCase(), GrpcStatusCode.UNKNOWN);
    }

    public static GrpcStatusCode fromHttpStatus(int httpStatus) {
        return HTTP_TO_GRPC.getOrDefault(httpStatus, GrpcStatusCode.UNKNOWN);
    }

    /**
     * Maps an HTTP response status onto the gRPC status a client should observe, per the
     * "HTTP-Status → Status code" table in the gRPC-over-HTTP/2 protocol specification.
     * <p>
     * This is deliberately <strong>not</strong> {@link #fromHttpStatus(int)}. That method inverts
     * the gRPC → HTTP mapping carried on {@link GrpcStatusCode} (so 404 → {@code NOT_FOUND},
     * the status whose canonical HTTP rendering is 404). This method implements the separate,
     * spec-defined mapping used when a gRPC call fails at the HTTP transport level and no
     * {@code grpc-status} is available — where 404 means "the server does not implement this
     * method" and therefore maps to {@code UNIMPLEMENTED}.
     */
    public static GrpcStatusCode fromHttpTransportStatus(int httpStatus) {
        switch (httpStatus) {
            case 400:
                return GrpcStatusCode.INTERNAL;
            case 401:
                return GrpcStatusCode.UNAUTHENTICATED;
            case 403:
                return GrpcStatusCode.PERMISSION_DENIED;
            case 404:
                return GrpcStatusCode.UNIMPLEMENTED;
            case 429:
            case 502:
            case 503:
            case 504:
                return GrpcStatusCode.UNAVAILABLE;
            default:
                return GrpcStatusCode.UNKNOWN;
        }
    }

    /**
     * Percent-encodes a {@code grpc-message} value for transmission, per the gRPC wire
     * specification's {@code Percent-Encoded} production.
     * <p>
     * The spec defines the value as ASCII only:
     * <pre>
     *   Percent-Encoded       = 1*(Percent-Byte-Unescaped / Percent-Byte-Escaped)
     *   Percent-Byte-Unescaped = %x20-24 / %x26-7E        ; space..'$' and '&amp;'..'~'
     *   Percent-Byte-Escaped   = "%" 2HEXDIG
     * </pre>
     * so every byte outside {@code 0x20-0x7E}, plus {@code %} ({@code 0x25}) itself, is escaped as
     * {@code %XX} over the value's <strong>UTF-8</strong> bytes. Clients percent-decode on receipt.
     * <p>
     * This must be applied at every emission site. Writing the raw string is wrong even for
     * ordinary input:
     * <ul>
     *   <li>{@code "quota 50% exceeded"} — the client decodes {@code %20} (from "50% e") into a
     *       space, silently corrupting a plain-ASCII message. No exotic characters required.</li>
     *   <li>{@code "paiement refusé"} — on HTTP/1.1 and HTTP/2 Netty's {@code AsciiString}
     *       conversion byte-casts {@code char & 0xFF}, so the client receives ISO-8859-1 bytes and
     *       grpc-java's UTF-8 decode produces mojibake. In the gRPC-Web trailer frame, which is
     *       written as {@code US_ASCII}, every non-ASCII character becomes a literal {@code ?} —
     *       silent data loss.</li>
     *   <li>{@code "denied\r\ngrpc-status: 0"} — the gRPC-Web trailer frame is a CRLF-delimited
     *       block, so an unescaped CRLF injects a second {@code grpc-status} line and can turn an
     *       error into a success. Encoding CR and LF as {@code %0D}/{@code %0A} closes this.</li>
     * </ul>
     *
     * @param message the raw message, may be {@code null}
     * @return the percent-encoded message, or {@code null} if {@code message} was {@code null}
     */
    public static String percentEncodeMessage(String message) {
        if (message == null) {
            return null;
        }
        byte[] bytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder encoded = null;
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (b >= 0x20 && b <= 0x7E && b != '%') {
                if (encoded != null) {
                    encoded.append((char) b);
                }
            } else {
                if (encoded == null) {
                    // nothing needed escaping until now -- copy the clean prefix
                    encoded = new StringBuilder(bytes.length + 8);
                    for (int j = 0; j < i; j++) {
                        encoded.append((char) (bytes[j] & 0xFF));
                    }
                }
                encoded.append('%');
                encoded.append(HEX_DIGITS[(b >> 4) & 0xF]);
                encoded.append(HEX_DIGITS[b & 0xF]);
            }
        }
        return encoded == null ? message : encoded.toString();
    }

    /**
     * Percent-decodes a {@code grpc-message} received from an upstream gRPC server, reversing
     * {@link #percentEncodeMessage(String)}.
     * <p>
     * Lenient, matching grpc-java: a {@code %} that is not followed by two hex digits is passed
     * through literally rather than treated as an error, so a server that (like MockServer before
     * this was implemented) emits an unencoded message is not made worse.
     *
     * @param message the percent-encoded message, may be {@code null}
     * @return the decoded message, or {@code null} if {@code message} was {@code null}
     */
    public static String percentDecodeMessage(String message) {
        if (message == null || message.indexOf('%') < 0) {
            return message;
        }
        java.io.ByteArrayOutputStream decoded = new java.io.ByteArrayOutputStream(message.length());
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '%' && i + 2 < message.length()) {
                // ASCII hex only. Character.digit(c, 16) also accepts non-ASCII digits, so "%٤١"
                // (Arabic-Indic) decoded to "A" -- a decoder more permissive than the encoder's
                // inverse, which is the shape that later becomes a parser-differential bypass.
                int high = asciiHexDigit(message.charAt(i + 1));
                int low = asciiHexDigit(message.charAt(i + 2));
                if (high >= 0 && low >= 0) {
                    decoded.write((high << 4) + low);
                    i += 2;
                    continue;
                }
            }
            // not a valid escape -- emit the character's UTF-8 bytes verbatim
            for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                decoded.write(b);
            }
        }
        return new String(decoded.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    /**
     * Returns the value of an ASCII hex digit, or -1. Deliberately narrower than
     * {@link Character#digit(char, int)}, which accepts digits from any Unicode script.
     */
    private static int asciiHexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /**
     * Removes C0 control characters (and DEL) from a value decoded from an untrusted source.
     * <p>
     * Percent-decoding an upstream {@code grpc-message} can produce real NUL or CRLF bytes from
     * {@code %00} / {@code %0D%0A}. That value flows into {@code LogEntry}, the persisted log,
     * verifications and the dashboard, so a hostile upstream -- exactly the party the proxy use
     * case treats as untrusted -- could forge log lines. The printable text is preserved.
     */
    public static String stripControlCharacters(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder stripped = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                if (stripped == null) {
                    stripped = new StringBuilder(value.length()).append(value, 0, i);
                }
            } else if (stripped != null) {
                stripped.append(c);
            }
        }
        return stripped == null ? value : stripped.toString();
    }

    public static boolean isGrpcContentType(String contentType) {
        return contentType != null
            && contentType.startsWith(GRPC_CONTENT_TYPE)
            && !contentType.startsWith(GRPC_CONTENT_TYPE + "-");
    }
}
