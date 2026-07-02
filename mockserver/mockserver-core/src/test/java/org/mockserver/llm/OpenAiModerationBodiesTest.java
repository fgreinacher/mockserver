package org.mockserver.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.model.ModerationResponse;

import java.util.Arrays;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.core.Is.is;

public class OpenAiModerationBodiesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    public void notFlaggedWhenNoCategories() throws Exception {
        JsonNode root = parse(OpenAiModerationBodies.encode(ModerationResponse.moderationResponse()));
        JsonNode result = root.get("results").get(0);

        assertThat(result.get("flagged").asBoolean(), is(false));
        // every canonical category present and false
        JsonNode categories = result.get("categories");
        for (String category : OpenAiModerationBodies.CATEGORIES) {
            assertThat("category " + category, categories.get(category).asBoolean(), is(false));
        }
        // scores present and low
        JsonNode scores = result.get("category_scores");
        assertThat(scores.get("violence").asDouble(), closeTo(OpenAiModerationBodies.NOT_FLAGGED_SCORE, 1e-9));
        assertThat(root.get("model").asText(), is(OpenAiModerationBodies.DEFAULT_MODEL));
        assertThat(root.get("id").asText().startsWith("modr-"), is(true));
    }

    @Test
    public void flaggedCategoriesAreTrueWithHighScore() throws Exception {
        ModerationResponse moderation = ModerationResponse.moderationResponse()
            .withFlaggedCategory("hate")
            .withFlaggedCategory("violence")
            .withModel("text-moderation-007");
        JsonNode root = parse(OpenAiModerationBodies.encode(moderation));
        JsonNode result = root.get("results").get(0);

        assertThat(result.get("flagged").asBoolean(), is(true));
        assertThat(result.get("categories").get("hate").asBoolean(), is(true));
        assertThat(result.get("categories").get("violence").asBoolean(), is(true));
        // a non-flagged category stays false
        assertThat(result.get("categories").get("sexual").asBoolean(), is(false));

        assertThat(result.get("category_scores").get("hate").asDouble(), closeTo(OpenAiModerationBodies.FLAGGED_SCORE, 1e-9));
        assertThat(result.get("category_scores").get("sexual").asDouble(), closeTo(OpenAiModerationBodies.NOT_FLAGGED_SCORE, 1e-9));
        assertThat(root.get("model").asText(), is("text-moderation-007"));
    }

    @Test
    public void flaggedCategoryMatchingIsCaseInsensitive() throws Exception {
        JsonNode root = parse(OpenAiModerationBodies.encode(Arrays.asList("HATE"), null));
        assertThat(root.get("results").get(0).get("categories").get("hate").asBoolean(), is(true));
        assertThat(root.get("results").get(0).get("flagged").asBoolean(), is(true));
    }
}
