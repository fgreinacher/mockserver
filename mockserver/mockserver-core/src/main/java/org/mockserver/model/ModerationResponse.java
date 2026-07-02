package org.mockserver.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for a mocked OpenAI Moderations endpoint response
 * ({@code POST /v1/moderations}). Production LLM agents call the moderations
 * endpoint to decide whether user or model content should be blocked; mocking it
 * lets those agents be tested deterministically against both flagged and
 * not-flagged verdicts.
 * <p>
 * The wire shape is OpenAI's:
 * {@code {"id":..,"model":..,"results":[{"flagged":bool,"categories":{..},"category_scores":{..}}]}}.
 * Every category named in {@link #getFlaggedCategories()} is marked
 * {@code true} (with a high score) and the top-level {@code flagged} is {@code true}
 * when the list is non-empty; all other categories are {@code false} (with a low
 * score). An empty/absent list yields a fully not-flagged verdict — the safe default.
 * <p>
 * Follows the model field/{@code withX}/getter convention so it round-trips through
 * the schema-validated expectation JSON without a bespoke (de)serializer.
 */
public class ModerationResponse extends ObjectWithJsonToString {

    private int hashCode;
    private List<String> flaggedCategories;
    private String model;

    public static ModerationResponse moderationResponse() {
        return new ModerationResponse();
    }

    /**
     * The moderation categories to mark {@code flagged=true} (e.g. {@code "hate"},
     * {@code "violence"}, {@code "self-harm"}). Unknown/unrecognised category names
     * are ignored by the encoder. When null or empty the verdict is not-flagged.
     */
    public ModerationResponse withFlaggedCategories(List<String> flaggedCategories) {
        this.flaggedCategories = flaggedCategories;
        this.hashCode = 0;
        return this;
    }

    /** Add a single category to the flagged set. */
    public ModerationResponse withFlaggedCategory(String category) {
        if (this.flaggedCategories == null) {
            this.flaggedCategories = new ArrayList<>();
        }
        this.flaggedCategories.add(category);
        this.hashCode = 0;
        return this;
    }

    public List<String> getFlaggedCategories() {
        return flaggedCategories;
    }

    /** Optional model identifier echoed in the response; defaults to {@code omni-moderation-latest}. */
    public ModerationResponse withModel(String model) {
        this.model = model;
        this.hashCode = 0;
        return this;
    }

    public String getModel() {
        return model;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        ModerationResponse that = (ModerationResponse) o;
        return Objects.equals(flaggedCategories, that.flaggedCategories) &&
            Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(flaggedCategories, model);
        }
        return hashCode;
    }
}
