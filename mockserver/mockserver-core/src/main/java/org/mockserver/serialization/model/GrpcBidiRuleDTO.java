package org.mockserver.serialization.model;

import org.mockserver.model.GrpcBidiRule;
import org.mockserver.model.NottableString;
import org.mockserver.model.ObjectWithReflectiveEqualsHashCodeToString;

import java.util.ArrayList;
import java.util.List;

public class GrpcBidiRuleDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<GrpcBidiRule> {
    // held as a NottableString, not a String: collapsing to String drops the negation flag, and
    // buildObject() cannot recover it by re-parsing a leading '!' because a matchJson value is JSON
    // and never starts with '!'. GrpcBidiRuleMatcher depends on the flag, so a negated rule
    // otherwise inverts into its opposite on any control-plane round-trip.
    private NottableString matchJson;
    private List<GrpcStreamMessageDTO> responses;

    public GrpcBidiRuleDTO(GrpcBidiRule rule) {
        if (rule != null) {
            matchJson = rule.getMatchJson();
            if (rule.getResponses() != null) {
                responses = new ArrayList<>();
                rule.getResponses().forEach(msg -> responses.add(new GrpcStreamMessageDTO(msg)));
            }
        }
    }

    public GrpcBidiRuleDTO() {
    }

    public GrpcBidiRule buildObject() {
        GrpcBidiRule rule = new GrpcBidiRule();
        if (matchJson != null) {
            rule.withMatchJson(matchJson);
        }
        if (responses != null) {
            responses.forEach(msgDTO -> rule.withResponse(msgDTO.buildObject()));
        }
        return rule;
    }

    public NottableString getMatchJson() {
        return matchJson;
    }

    public GrpcBidiRuleDTO setMatchJson(NottableString matchJson) {
        this.matchJson = matchJson;
        return this;
    }

    public List<GrpcStreamMessageDTO> getResponses() {
        return responses;
    }

    public GrpcBidiRuleDTO setResponses(List<GrpcStreamMessageDTO> responses) {
        this.responses = responses;
        return this;
    }
}
