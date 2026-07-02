package org.mockserver.serialization.model;

import org.mockserver.model.AllOfBody;
import org.mockserver.model.Body;
import org.mockserver.model.Not;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author jamesdbloom
 */
public class AllOfBodyDTO extends BodyDTO {

    private final List<BodyDTO> bodies;

    public AllOfBodyDTO(AllOfBody allOfBody) {
        this(allOfBody, null);
    }

    public AllOfBodyDTO(AllOfBody allOfBody, Boolean not) {
        super(Body.Type.ALL_OF, not);
        this.bodies = allOfBody.getValue() != null
            ? allOfBody.getValue().stream().map(BodyDTO::createDTO).collect(Collectors.toList())
            : new ArrayList<>();
        withOptional(allOfBody.getOptional());
    }

    public AllOfBodyDTO(List<BodyDTO> bodies, Boolean not) {
        super(Body.Type.ALL_OF, not);
        this.bodies = bodies != null ? bodies : new ArrayList<>();
    }

    public List<BodyDTO> getBodies() {
        return bodies;
    }

    public AllOfBody buildObject() {
        List<Body> builtBodies = bodies.stream()
            .map(bodyDTO -> (Body) Not.not(bodyDTO.buildObject(), bodyDTO.getNot()))
            .collect(Collectors.toList());
        return (AllOfBody) new AllOfBody(builtBodies).withOptional(getOptional());
    }
}
