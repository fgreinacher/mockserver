package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.mockserver.model.Body;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.SelectionSetMatchType;

import java.util.List;

public class GraphQLBodyDTO extends BodyDTO {

    private final String query;
    private final String operationName;
    private final String variablesSchema;
    private final SelectionSetMatchType selectionSetMatchType;
    private final List<String> fields;
    private final String schema;

    public GraphQLBodyDTO(GraphQLBody graphQLBody) {
        this(graphQLBody, null);
    }

    public GraphQLBodyDTO(GraphQLBody graphQLBody, Boolean not) {
        super(Body.Type.GRAPHQL, not);
        this.query = graphQLBody.getQuery();
        this.operationName = graphQLBody.getOperationName();
        this.variablesSchema = graphQLBody.getVariablesSchema();
        this.selectionSetMatchType = graphQLBody.getSelectionSetMatchType();
        this.fields = graphQLBody.getFields();
        this.schema = graphQLBody.getSchema();
        withOptional(graphQLBody.getOptional());
    }

    /**
     * Jackson entry point for the one place a {@code GraphQLBodyDTO} is deserialised by field type
     * rather than through {@link org.mockserver.serialization.deserializers.body.BodyDTODeserializer}:
     * {@link HttpWebSocketResponseDTO#getGraphqlSubscriptionFilter()}. That field is declared as the
     * concrete subtype, so the polymorphic body deserialiser — which is registered against
     * {@code BodyDTO} and matched by exact class — never runs for it, and without a creator Jackson
     * cannot instantiate this all-final type at all.
     *
     * <p>{@code type} is accepted so the {@code "type":"GRAPHQL"} discriminator emitted by
     * {@link org.mockserver.serialization.serializers.body.GraphQLBodyDTOSerializer} round-trips, but
     * it is deliberately ignored: the body type is fixed by the class itself. Accepting it explicitly
     * keeps this working regardless of the mapper's {@code FAIL_ON_UNKNOWN_PROPERTIES} setting.
     *
     * <p><b>The {@code @JsonCreator} annotation is intent, not mechanism, and no test guards it.</b>
     * Jackson already treats a constructor whose parameters carry {@code @JsonProperty} as an implicit
     * property-based creator, so deleting the annotation alone leaves every test green — this was
     * confirmed by mutation. What is load-bearing is the CONSTRUCTOR: remove it and control-plane
     * expectations carrying a {@code graphqlSubscriptionFilter} are rejected outright. The annotation
     * is kept so the intent survives a future reader, not because anything currently fails without it.
     */
    @JsonCreator
    public GraphQLBodyDTO(
        @JsonProperty("type") Body.Type type,
        @JsonProperty("not") Boolean not,
        @JsonProperty("optional") Boolean optional,
        @JsonProperty("query") String query,
        @JsonProperty("operationName") String operationName,
        @JsonProperty("variablesSchema") String variablesSchema,
        @JsonProperty("selectionSetMatchType") SelectionSetMatchType selectionSetMatchType,
        @JsonProperty("fields") List<String> fields,
        @JsonProperty("schema") String schema
    ) {
        super(Body.Type.GRAPHQL, not);
        this.query = query;
        this.operationName = operationName;
        this.variablesSchema = variablesSchema;
        this.selectionSetMatchType = selectionSetMatchType;
        this.fields = fields;
        this.schema = schema;
        withOptional(optional);
    }

    public String getQuery() {
        return query;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getVariablesSchema() {
        return variablesSchema;
    }

    public SelectionSetMatchType getSelectionSetMatchType() {
        return selectionSetMatchType;
    }

    public List<String> getFields() {
        return fields;
    }

    public String getSchema() {
        return schema;
    }

    public GraphQLBody buildObject() {
        GraphQLBody body = new GraphQLBody(getQuery(), getOperationName(), getVariablesSchema());
        if (selectionSetMatchType != null) {
            body.withSelectionSetMatchType(selectionSetMatchType);
        }
        if (fields != null) {
            body.withFields(fields);
        }
        if (schema != null) {
            body.withSchema(schema);
        }
        body.withOptional(getOptional());
        return body;
    }
}
