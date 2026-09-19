package org.mockserver.llm.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RerankResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.mockserver.model.HttpResponse.response;

/**
 * Shared, deterministic rerank scoring used by the rerank codecs (Cohere,
 * Voyage). Producing one result per candidate document, each scored with a
 * reproducible relevance score in {@code (0, 1)}, sorted descending, then capped
 * to {@code topN}. The response shape is identical across both providers:
 * {@code {"results":[{"index":N,"relevance_score":F}, ...]}}.
 */
public final class RerankScoring {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RerankScoring() {
    }

    /** One scored, original-index-tagged document. */
    public static final class Scored {
        public final int index;
        public final double score;

        Scored(int index, double score) {
            this.index = index;
            this.score = score;
        }
    }

    /**
     * Score each document and return them sorted by descending relevance,
     * capped to {@code topN} when set.
     */
    public static List<Scored> score(RerankResponse rerank, List<String> documents) {
        // Mirror EmbeddingVectors: deterministic only when explicitly opted in
        // (deterministicFromInput == true), otherwise random scores.
        boolean deterministic = rerank != null && Boolean.TRUE.equals(rerank.getDeterministicFromInput());
        long seed = rerank != null && rerank.getSeed() != null ? rerank.getSeed() : 0L;

        List<Scored> scored = new ArrayList<>();
        List<String> docs = documents != null ? documents : new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            double score = deterministic
                ? deterministicScore(docs.get(i), seed)
                : ThreadLocalRandom.current().nextDouble();
            scored.add(new Scored(i, score));
        }
        // Sort by descending score; ties broken by original index for stability.
        scored.sort(Comparator.<Scored>comparingDouble(s -> s.score).reversed()
            .thenComparingInt(s -> s.index));

        Integer topN = rerank != null ? rerank.getTopN() : null;
        if (topN != null && topN >= 0 && topN < scored.size()) {
            return new ArrayList<>(scored.subList(0, topN));
        }
        return scored;
    }

    /**
     * The provider-specific envelope wrapping the per-document scores. Cohere
     * uses a top-level {@code results} array; Voyage uses an OpenAI-style
     * {@code data} list with an {@code object}/{@code usage} envelope. Both carry
     * the same {@code {"index":N,"relevance_score":F}} entries.
     */
    public enum Envelope {
        /** Cohere: {@code {"results":[{"index":N,"relevance_score":F}, ...]}}. */
        COHERE_RESULTS,
        /** Voyage: {@code {"object":"list","data":[...],"usage":{"total_tokens":N}}}. */
        VOYAGE_DATA
    }

    /**
     * Build the provider-correct rerank HTTP response from the scored documents,
     * using the provider's envelope shape.
     */
    public static HttpResponse encode(RerankResponse rerank, List<String> documents, String model, Envelope envelope) {
        List<Scored> scored = score(rerank, documents);

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        if (envelope == Envelope.VOYAGE_DATA) {
            root.put("object", "list");
            ArrayNode data = root.putArray("data");
            for (Scored s : scored) {
                ObjectNode result = data.addObject();
                result.put("index", s.index);
                result.put("relevance_score", s.score);
            }
            if (model != null) {
                root.put("model", model);
            }
            ObjectNode usage = root.putObject("usage");
            usage.put("total_tokens", 0); // mocked — no real tokenisation
        } else {
            if (model != null) {
                root.put("model", model);
            }
            ArrayNode results = root.putArray("results");
            for (Scored s : scored) {
                ObjectNode result = results.addObject();
                result.put("index", s.index);
                result.put("relevance_score", s.score);
            }
        }

        try {
            String json = OBJECT_MAPPER.writeValueAsString(root);
            return response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode rerank response", e);
        }
    }

    /** Deterministic relevance score in {@code (0, 1)} from document + seed. */
    static double deterministicScore(String document, long seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update((document != null ? document : "").getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            long bits = ByteBuffer.wrap(hash, 0, 8).getLong() & Long.MAX_VALUE;
            // Map to (0, 1); avoid exactly 0 or 1 so it always reads as a fractional score.
            double score = (bits % 1_000_000L + 1) / 1_000_001.0;
            // Round to 4 decimal places, matching real rerank API precision.
            return Math.round(score * 10_000.0) / 10_000.0;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
