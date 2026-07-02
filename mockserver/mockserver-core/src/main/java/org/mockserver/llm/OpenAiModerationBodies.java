package org.mockserver.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.ModerationResponse;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, deterministic encoder for the OpenAI Moderations endpoint
 * ({@code POST /v1/moderations}) response body. Given the set of categories to
 * flag, produces OpenAI's exact wire shape:
 * {@code {"id":..,"model":..,"results":[{"flagged":bool,"categories":{..},"category_scores":{..}}]}}.
 *
 * <p>Every canonical moderation category is emitted (so client SDKs that read a
 * fixed field set never see a missing key); the categories named in the
 * {@link ModerationResponse} are set {@code true} with a high score, the rest
 * {@code false} with a low score. The top-level {@code flagged} is {@code true}
 * when any category is flagged. Scores are fixed constants ({@value #FLAGGED_SCORE}
 * / {@value #NOT_FLAGGED_SCORE}) so the body is deterministic.
 *
 * <p>All methods are static and pure (no clocks, no shared state); the only
 * non-determinism is the random {@code id}, which real OpenAI responses also carry.
 */
public final class OpenAiModerationBodies {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static final double FLAGGED_SCORE = 0.95;
    static final double NOT_FLAGGED_SCORE = 0.0001;

    static final String DEFAULT_MODEL = "omni-moderation-latest";

    /**
     * The canonical OpenAI moderation categories, in OpenAI's documented order.
     * Emitted in full on every response so a fixed-field client never sees a gap.
     */
    static final String[] CATEGORIES = {
        "sexual",
        "hate",
        "harassment",
        "self-harm",
        "sexual/minors",
        "hate/threatening",
        "violence/graphic",
        "self-harm/intent",
        "self-harm/instructions",
        "harassment/threatening",
        "violence"
    };

    private OpenAiModerationBodies() {
    }

    /** Encode the moderations JSON body for the given {@link ModerationResponse}. */
    public static String encode(ModerationResponse moderation) {
        List<String> flagged = moderation == null ? null : moderation.getFlaggedCategories();
        String model = moderation == null ? null : moderation.getModel();
        return encode(flagged, model);
    }

    /**
     * Encode the moderations JSON body flagging the given categories. A null/empty
     * {@code flaggedCategories} yields a fully not-flagged verdict.
     */
    public static String encode(List<String> flaggedCategories, String model) {
        Set<String> flagged = new HashSet<>();
        if (flaggedCategories != null) {
            for (String c : flaggedCategories) {
                if (c != null) {
                    flagged.add(c.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("id", "modr-" + randomId(24));
        root.put("model", model != null && !model.isEmpty() ? model : DEFAULT_MODEL);

        ArrayNode results = root.putArray("results");
        ObjectNode result = results.addObject();

        ObjectNode categories = OBJECT_MAPPER.createObjectNode();
        ObjectNode categoryScores = OBJECT_MAPPER.createObjectNode();
        boolean anyFlagged = false;
        for (String category : CATEGORIES) {
            boolean isFlagged = flagged.contains(category);
            anyFlagged = anyFlagged || isFlagged;
            categories.put(category, isFlagged);
            categoryScores.put(category, isFlagged ? FLAGGED_SCORE : NOT_FLAGGED_SCORE);
        }

        result.put("flagged", anyFlagged);
        result.set("categories", categories);
        result.set("category_scores", categoryScores);

        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode OpenAI moderation response", e);
        }
    }

    private static String randomId(int length) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        while (uuid.length() < length) {
            uuid = uuid + UUID.randomUUID().toString().replace("-", "");
        }
        return uuid.substring(0, length);
    }
}
