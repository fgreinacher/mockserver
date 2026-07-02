package org.mockserver.llm.codec;

import org.mockserver.model.EmbeddingResponse;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Shared, deterministic embedding-vector generation used by every provider
 * embedding codec (OpenAI, Gemini, Ollama, Bedrock Titan/Cohere).
 * <p>
 * The vector is either reproducibly derived from the input text + seed +
 * dimensions (when {@link EmbeddingResponse#getDeterministicFromInput()} is
 * true and an input is present) or random, then L2-normalised so it behaves
 * like a real embedding (unit length, cosine-comparable). Provider codecs only
 * differ in the JSON envelope they wrap this vector in.
 * <p>
 * The deterministic vector is produced by <b>n-gram feature hashing</b>
 * (a.k.a. the "hashing trick"): the input is tokenised into word unigrams,
 * word bigrams, and character n-grams; each feature is hashed into one of
 * {@code dimensions} buckets with a signed contribution (to reduce collision
 * bias) weighted by a sublinear term frequency; the accumulated vector is then
 * L2-normalised. Two texts that share vocabulary / n-grams land in overlapping
 * buckets and therefore have a higher cosine similarity, while unrelated texts
 * are near-orthogonal — so the vector is <b>both</b> deterministic (same
 * input+seed+dimensions ⇒ same vector) <b>and</b> semantically plausible,
 * which lets offline RAG / vector-search code rank related documents above
 * unrelated ones without calling a real embedding model.
 */
public final class EmbeddingVectors {

    /** Character n-gram size (captures shared sub-words, e.g. "cat"/"cats"). */
    private static final int CHAR_NGRAM_SIZE = 3;

    /**
     * Weight multiplier for character n-gram features. Word-level features
     * carry the primary meaning, so character n-grams (which are far more
     * numerous) are down-weighted to keep whole-word overlap dominant.
     */
    private static final double CHAR_NGRAM_WEIGHT = 0.5;

    /** FNV-1a 64-bit offset basis / prime — a stable, JVM-independent hash. */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private EmbeddingVectors() {
    }

    /**
     * Build the embedding vector for an {@link EmbeddingResponse} and input,
     * applying the determinism flag, seed, and default dimensions, then
     * L2-normalising the result.
     *
     * @param embedding         the configured embedding response
     * @param input             the request input text (may be null)
     * @param defaultDimensions provider-specific default when no dimensions are configured
     * @return an L2-normalised vector
     */
    public static double[] build(EmbeddingResponse embedding, String input, int defaultDimensions) {
        int dimensions = embedding.getDimensions() != null ? embedding.getDimensions() : defaultDimensions;
        long seed = embedding.getSeed() != null ? embedding.getSeed() : 0L;
        boolean deterministic = Boolean.TRUE.equals(embedding.getDeterministicFromInput());

        double[] vector;
        if (deterministic && input != null) {
            vector = generateDeterministicVector(input, dimensions, seed);
        } else {
            vector = generateRandomVector(dimensions);
        }
        normalizeL2(vector);
        return vector;
    }

    /**
     * Produce a deterministic, semantically-plausible (un-normalised) vector
     * from the input text via n-gram feature hashing. The same
     * {@code input}, {@code dimensions}, and {@code seed} always yield the same
     * vector; texts sharing vocabulary / n-grams accumulate into overlapping
     * buckets and so have a higher cosine similarity.
     */
    public static double[] generateDeterministicVector(String input, int dimensions, long seed) {
        if (dimensions <= 0) {
            // No buckets to hash into; return an empty vector rather than
            // dividing by zero (floorMod by zero), matching the historical
            // graceful behaviour for a zero-dimension request.
            return new double[0];
        }
        double[] vector = new double[dimensions];

        // Count features first so a sublinear term-frequency weight can be applied.
        Map<String, Integer> wordFeatures = new HashMap<>();
        Map<String, Integer> charFeatures = new HashMap<>();
        extractFeatures(input, wordFeatures, charFeatures);

        if (wordFeatures.isEmpty() && charFeatures.isEmpty()) {
            // Empty / punctuation-only input has no features; fall back to a
            // seeded pseudo-random unit-ish vector so the result is still a
            // deterministic, non-degenerate (non-zero) embedding.
            return seededFallbackVector(input, dimensions, seed);
        }

        accumulate(vector, wordFeatures, dimensions, seed, 1.0);
        accumulate(vector, charFeatures, dimensions, seed, CHAR_NGRAM_WEIGHT);
        return vector;
    }

    private static void accumulate(double[] vector, Map<String, Integer> features, int dimensions, long seed, double weightMultiplier) {
        for (Map.Entry<String, Integer> entry : features.entrySet()) {
            String feature = entry.getKey();
            int count = entry.getValue();
            // Sublinear term frequency: 1 + ln(count) dampens frequent features.
            double weight = weightMultiplier * (1.0 + Math.log(count));
            long indexHash = fnv1a(feature, seed, 0);
            long signHash = fnv1a(feature, seed, 1);
            int bucket = (int) Math.floorMod(indexHash, (long) dimensions);
            double sign = (signHash & 1L) == 0L ? 1.0 : -1.0;
            vector[bucket] += sign * weight;
        }
    }

    /**
     * Tokenise (Unicode-aware, lowercased) into word unigrams, word bigrams,
     * and character n-grams. Word/bigram features go into {@code wordFeatures};
     * character n-grams into {@code charFeatures}. Feature strings are
     * namespaced ("w:" / "b:" / "c:") so the three kinds never collide.
     */
    private static void extractFeatures(String input, Map<String, Integer> wordFeatures, Map<String, Integer> charFeatures) {
        String[] tokens = tokenize(input);
        String previous = null;
        for (String token : tokens) {
            increment(wordFeatures, "w:" + token);
            if (previous != null) {
                increment(wordFeatures, "b:" + previous + " " + token);
            }
            previous = token;

            // Character n-grams over a boundary-padded token, so word edges
            // ("#ca", "at#") are distinguished from interior sequences.
            String padded = "#" + token + "#";
            if (padded.length() <= CHAR_NGRAM_SIZE) {
                increment(charFeatures, "c:" + padded);
            } else {
                for (int i = 0; i + CHAR_NGRAM_SIZE <= padded.length(); i++) {
                    increment(charFeatures, "c:" + padded.substring(i, i + CHAR_NGRAM_SIZE));
                }
            }
        }
    }

    /**
     * Split into lowercase word tokens on any non-letter/digit boundary.
     * Uses {@link Character#isLetterOrDigit(int)} over code points so non-ASCII
     * text (accented Latin, CJK, Cyrillic, …) tokenises correctly.
     */
    private static String[] tokenize(String input) {
        String lower = input.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> tokens = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int length = lower.length();
        for (int i = 0; i < length; ) {
            int codePoint = lower.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(codePoint);
            } else if (current.length() > 0) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            i += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }

    private static void increment(Map<String, Integer> features, String feature) {
        features.merge(feature, 1, Integer::sum);
    }

    /**
     * FNV-1a 64-bit hash over the seed, a salt (to derive independent hashes
     * for the bucket index and the sign), and the feature's UTF-8 bytes.
     * FNV-1a is fully specified so the per-feature hash is stable across JVMs
     * and platforms, which keeps the vector deterministic for the same input,
     * seed, and dimensions. (The floating-point vector is not promised to be
     * bit-exact across platforms — feature summation order and {@link Math#log}
     * ULP differences can perturb the low bits — but the cosine ordering the
     * feature is designed around is preserved.)
     */
    private static long fnv1a(String feature, long seed, int salt) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < Long.BYTES; i++) {
            hash ^= (seed >>> (i * 8)) & 0xffL;
            hash *= FNV_PRIME;
        }
        hash ^= salt & 0xffL;
        hash *= FNV_PRIME;
        byte[] bytes = feature.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= (b & 0xffL);
            hash *= FNV_PRIME;
        }
        return hash;
    }

    /**
     * Deterministic non-zero fallback for feature-less input (empty or
     * punctuation-only), seeded from the input+dimensions+seed so it is still
     * reproducible. Mirrors the historical hash-seeded {@link Random} vector.
     */
    private static double[] seededFallbackVector(String input, int dimensions, long seed) {
        long hash = fnv1a("fallback:" + dimensions + ":" + input, seed, 2);
        Random random = new Random(hash);
        double[] vector = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = random.nextDouble() * 2 - 1;
        }
        return vector;
    }

    public static double[] generateRandomVector(int dimensions) {
        Random random = new Random();
        double[] vector = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = random.nextDouble() * 2 - 1;
        }
        return vector;
    }

    public static void normalizeL2(double[] vector) {
        double sumOfSquares = 0;
        for (double v : vector) {
            sumOfSquares += v * v;
        }
        double norm = Math.sqrt(sumOfSquares);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }

    /**
     * Approximate the prompt token count from the input length, matching the
     * convention used by the chat codecs (~4 chars per token).
     */
    public static int approximateTokens(String input) {
        return input != null ? Math.max(1, input.length() / 4) : 0;
    }
}
