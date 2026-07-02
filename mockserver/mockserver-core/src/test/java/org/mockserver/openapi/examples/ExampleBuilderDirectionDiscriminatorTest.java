package org.mockserver.openapi.examples;

import io.swagger.v3.oas.models.media.*;
import org.junit.Test;
import org.mockserver.openapi.examples.ExampleBuilder.Direction;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.ObjectExample;
import org.mockserver.openapi.examples.models.StringExample;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Covers {@link ExampleBuilder} honouring {@code readOnly}/{@code writeOnly} (via {@link Direction})
 * and {@code discriminator} on {@code oneOf}/{@code anyOf} schemas.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ExampleBuilderDirectionDiscriminatorTest {

    private static Schema readOnly(Schema schema) {
        schema.setReadOnly(true);
        return schema;
    }

    private static Schema writeOnly(Schema schema) {
        schema.setWriteOnly(true);
        return schema;
    }

    private static ObjectSchema accountSchema() {
        // id: readOnly, name: plain, password: writeOnly
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("id", readOnly(new IntegerSchema()));
        schema.addProperty("name", new StringSchema());
        schema.addProperty("password", writeOnly(new StringSchema()));
        return schema;
    }

    @Test
    public void requestExampleExcludesReadOnlyKeepsWriteOnly() {
        Example example = ExampleBuilder.fromSchema(accountSchema(), new HashMap<>(), null, Direction.REQUEST);
        assertThat(example, is(instanceOf(ObjectExample.class)));
        ObjectExample object = (ObjectExample) example;
        assertThat(object.keySet(), not(hasItem("id")));
        assertThat(object.keySet(), hasItems("name", "password"));
    }

    @Test
    public void responseExampleExcludesWriteOnlyKeepsReadOnly() {
        Example example = ExampleBuilder.fromSchema(accountSchema(), new HashMap<>(), null, Direction.RESPONSE);
        ObjectExample object = (ObjectExample) example;
        assertThat(object.keySet(), not(hasItem("password")));
        assertThat(object.keySet(), hasItems("id", "name"));
    }

    @Test
    public void unspecifiedDirectionIncludesEverything() {
        ObjectExample object = (ObjectExample) ExampleBuilder.fromSchema(accountSchema(), new HashMap<>(), null, Direction.UNSPECIFIED);
        assertThat(object.keySet(), hasItems("id", "name", "password"));
    }

    @Test
    public void legacyTwoArgOverloadIncludesReadOnlyAndWriteOnly() {
        // the historic two-arg entry point must be unchanged (direction unknown -> no filtering)
        ObjectExample object = (ObjectExample) ExampleBuilder.fromSchema(accountSchema(), new HashMap<>());
        assertThat(object.keySet(), hasItems("id", "name", "password"));
    }

    @Test
    public void nestedReadOnlyPropertyExcludedFromRequest() {
        ObjectSchema inner = new ObjectSchema();
        inner.addProperty("audit", readOnly(new StringSchema()));
        inner.addProperty("label", new StringSchema());
        ObjectSchema outer = new ObjectSchema();
        outer.addProperty("child", inner);

        ObjectExample object = (ObjectExample) ExampleBuilder.fromSchema(outer, new HashMap<>(), null, Direction.REQUEST);
        ObjectExample child = (ObjectExample) object.get("child");
        assertThat(child.keySet(), not(hasItem("audit")));
        assertThat(child.keySet(), hasItem("label"));
    }

    // ---- discriminator ----

    private static Map<String, Schema> petDefinitions() {
        ObjectSchema cat = new ObjectSchema();
        cat.addProperty("meow", new StringSchema());
        ObjectSchema dog = new ObjectSchema();
        dog.addProperty("bark", new StringSchema());
        Map<String, Schema> definitions = new HashMap<>();
        definitions.put("Cat", cat);
        definitions.put("Dog", dog);
        return definitions;
    }

    private static Schema refTo(String name) {
        return new Schema<>().$ref("#/components/schemas/" + name);
    }

    @Test
    public void oneOfWithDiscriminatorMappingSetsMappingKey() {
        ComposedSchema pet = new ComposedSchema();
        pet.addOneOfItem(refTo("Cat"));
        pet.addOneOfItem(refTo("Dog"));
        Discriminator discriminator = new Discriminator().propertyName("petType");
        discriminator.mapping("cat", "#/components/schemas/Cat");
        discriminator.mapping("dog", "#/components/schemas/Dog");
        pet.setDiscriminator(discriminator);

        ObjectExample object = (ObjectExample) ExampleBuilder.fromSchema(pet, petDefinitions());
        assertThat(object.get("petType"), is(instanceOf(StringExample.class)));
        assertThat(((StringExample) object.get("petType")).getValue(), is("cat"));
        // the chosen subschema's own property is present
        assertThat(object.keySet(), hasItem("meow"));
    }

    @Test
    public void anyOfWithDiscriminatorNoMappingUsesRefShortName() {
        ComposedSchema pet = new ComposedSchema();
        pet.addAnyOfItem(refTo("Dog"));
        pet.addAnyOfItem(refTo("Cat"));
        pet.setDiscriminator(new Discriminator().propertyName("petType"));

        ObjectExample object = (ObjectExample) ExampleBuilder.fromSchema(pet, petDefinitions());
        assertThat(((StringExample) object.get("petType")).getValue(), is("Dog"));
        assertThat(object.keySet(), hasItem("bark"));
    }

    @Test
    public void oneOfWithoutDiscriminatorKeepsFirstNonNullSelection() {
        // regression: no discriminator -> unchanged behaviour (first member example, no petType added)
        ComposedSchema pet = new ComposedSchema();
        pet.addOneOfItem(refTo("Cat"));
        pet.addOneOfItem(refTo("Dog"));

        ObjectExample object = (ObjectExample) ExampleBuilder.fromSchema(pet, petDefinitions());
        assertThat(object.keySet(), hasItem("meow"));
        assertThat(object.keySet(), not(hasItem("petType")));
    }
}
