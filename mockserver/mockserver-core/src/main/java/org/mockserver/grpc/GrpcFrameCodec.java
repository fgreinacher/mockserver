package org.mockserver.grpc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;

public class GrpcFrameCodec {

    private static final int HEADER_LENGTH = 5;
    private static final byte UNCOMPRESSED = 0;
    private static final byte COMPRESSED = 1;

    /**
     * The only message encoding MockServer can decode, besides {@code identity}.
     */
    public static final String GZIP_ENCODING = "gzip";

    /**
     * Advertised to clients in {@code grpc-accept-encoding} so a client whose preferred encoding is
     * unsupported knows what to retry with.
     */
    public static final String ACCEPT_ENCODING = "identity, gzip";

    /**
     * The maximum decoded message size, resolved from configuration on each call so a test or
     * embedded user changing {@code maxGrpcMessageSize} takes effect without a restart. This is the
     * single definition of the limit -- {@link IncrementalGrpcFrameDecoder} reads it from here
     * rather than keeping its own copy, which previously let the two drift.
     */
    public static int maxMessageSize() {
        return maxMessageSize(null);
    }

    /**
     * Resolves the limit from a live {@link org.mockserver.configuration.Configuration} when one is
     * available, falling back to the static {@code ConfigurationProperties} store.
     * <p>
     * Reading only the static store meant {@code Configuration.maxGrpcMessageSize(...)} -- and
     * therefore the DTO and {@code PUT /mockserver/config} -- silently had no effect, even though
     * all four equivalent forms are documented. The sibling {@code maxRequestBodySize} is consumed
     * via the instance, so this restores the established pattern.
     */
    public static int maxMessageSize(org.mockserver.configuration.Configuration configuration) {
        int configured = configuration != null
            ? configuration.maxGrpcMessageSize()
            : org.mockserver.configuration.ConfigurationProperties.maxGrpcMessageSize();
        // Clamp HERE rather than in the property reader so every source is covered by one check --
        // the static store, a Configuration instance, the DTO and PUT /mockserver/config alike.
        return Math.min(configured, MAX_MESSAGE_SIZE_CEILING);
    }

    /**
     * Hard ceiling on the configured message size. This limit is the only bound on the gzip
     * decompression path, which accumulates into a heap {@code ByteArrayOutputStream} while
     * comparing against it -- so an unbounded value (say {@code Integer.MAX_VALUE}) would let a few
     * KB of gzip drive a multi-GB allocation. 256 MiB is far above any legitimate gRPC message and
     * still keeps the decompression bomb capped.
     */
    public static final int MAX_MESSAGE_SIZE_CEILING = 256 * 1024 * 1024;

    /**
     * Returns {@code true} if the requested {@code grpc-encoding} is one MockServer can decode.
     * {@code null}/empty means the client sent no preference, which is {@code identity}.
     */
    public static boolean isSupportedEncoding(String grpcEncoding) {
        return grpcEncoding == null
            || grpcEncoding.isEmpty()
            || "identity".equalsIgnoreCase(grpcEncoding.trim())
            || GZIP_ENCODING.equalsIgnoreCase(grpcEncoding.trim());
    }

    public static byte[] encode(byte[] message, boolean compress) {
        byte[] payload = message;
        byte flag = UNCOMPRESSED;
        if (compress) {
            payload = gzipCompress(message);
            flag = COMPRESSED;
        }
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + payload.length);
        buffer.put(flag);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    public static byte[] encode(byte[] message) {
        return encode(message, false);
    }

    public static List<byte[]> decode(byte[] data) {
        return decode(data, null);
    }

    /**
     * Decodes length-prefixed gRPC frames, honouring the request's {@code grpc-encoding}.
     * <p>
     * The compressed flag alone does not identify the algorithm -- the {@code grpc-encoding} header
     * does. Assuming gzip whenever the flag is set meant a client negotiating {@code deflate} or
     * {@code snappy} hit a gzip decode failure reported as {@code INTERNAL}, instead of the
     * specification's {@code UNIMPLEMENTED} telling it to retry with a supported encoding.
     *
     * @param grpcEncoding the request's {@code grpc-encoding} header value, may be {@code null}
     */
    public static List<byte[]> decode(byte[] data, String grpcEncoding) {
        return decode(data, grpcEncoding, null);
    }

    /**
     * As {@link #decode(byte[], String)}, enforcing the limit from a live {@link
     * org.mockserver.configuration.Configuration}.
     */
    public static List<byte[]> decode(byte[] data, String grpcEncoding, org.mockserver.configuration.Configuration configuration) {
        List<byte[]> messages = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.remaining() >= HEADER_LENGTH) {
            byte compressedFlag = buffer.get();
            if ((compressedFlag & ~1) != 0) {
                throw new GrpcException("gRPC frame has reserved flag bits set: " + compressedFlag);
            }
            int length = buffer.getInt();
            int maxMessageSize = maxMessageSize(configuration);
            if (length < 0 || length > maxMessageSize) {
                // RESOURCE_EXHAUSTED, not INTERNAL: this is the receive-message-size limit, and it
                // is what grpc-java and grpc-go report so a client can distinguish "too big" from
                // "server broke"
                throw new GrpcException(
                    "gRPC message size " + length + " exceeds maximum allowed " + maxMessageSize,
                    GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED);
            }
            if (buffer.remaining() < length) {
                break;
            }
            byte[] payload = new byte[length];
            buffer.get(payload);
            if (compressedFlag == COMPRESSED) {
                if (!isSupportedEncoding(grpcEncoding)) {
                    throw new GrpcException(
                        "grpc-encoding \"" + grpcEncoding + "\" is not supported, supported encodings are: " + ACCEPT_ENCODING,
                        GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED);
                }
                payload = gzipDecompress(payload, configuration);
            }
            messages.add(payload);
        }
        return messages;
    }

    public static byte[] decodeSingle(byte[] data) {
        return decodeSingle(data, null);
    }

    public static byte[] decodeSingle(byte[] data, String grpcEncoding) {
        List<byte[]> messages = decode(data, grpcEncoding);
        if (messages.isEmpty()) {
            return new byte[0];
        }
        return messages.get(0);
    }

    private static byte[] gzipCompress(byte[] data) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
            try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
                gos.write(data);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to gzip compress gRPC message", e);
        }
    }

    private static byte[] gzipDecompress(byte[] data, org.mockserver.configuration.Configuration configuration) {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            try (GZIPInputStream gis = new GZIPInputStream(bis)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                long total = 0;
                int maxMessageSize = maxMessageSize(configuration);
                while ((len = gis.read(buf)) != -1) {
                    total += len;
                    if (total > maxMessageSize) {
                        throw new GrpcException(
                            "decompressed gRPC message size exceeds maximum allowed " + maxMessageSize,
                            GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED);
                    }
                    bos.write(buf, 0, len);
                }
                return bos.toByteArray();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to gzip decompress gRPC message", e);
        }
    }
}
