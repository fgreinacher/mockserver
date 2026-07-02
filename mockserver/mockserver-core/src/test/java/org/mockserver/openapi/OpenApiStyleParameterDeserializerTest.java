package org.mockserver.openapi;

import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.Parameter.StyleEnum;
import org.junit.Test;
import org.mockserver.model.HttpRequest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;

/**
 * Table-driven coverage of {@link OpenApiStyleParameterDeserializer} across the OpenAPI
 * {@code style}/{@code explode} matrix for query (form/spaceDelimited/pipeDelimited/deepObject),
 * path (simple/label/matrix) and header (simple) parameters, for both {@code array} and {@code object}
 * schemas. Asserts the decoded, type-aware JSON literal (numeric/boolean tokens emitted unquoted,
 * everything else quoted) so the downstream schema check validates the reconstructed value.
 */
@SuppressWarnings("rawtypes")
public class OpenApiStyleParameterDeserializerTest {

    private static Parameter param(String in, StyleEnum style, Boolean explode, Schema schema) {
        Parameter parameter = new Parameter().in(in).name("p").schema(schema);
        parameter.setStyle(style);
        parameter.setExplode(explode);
        return parameter;
    }

    private static Schema arrayOf(String itemType) {
        return new ArraySchema().items(new Schema<>().type(itemType));
    }

    private static Schema objectWith(String... propTypePairs) {
        ObjectSchema object = new ObjectSchema();
        for (int i = 0; i < propTypePairs.length; i += 2) {
            object.addProperty(propTypePairs[i], new Schema<>().type(propTypePairs[i + 1]));
        }
        return object;
    }

    private static OpenApiStyleParameterDeserializer.Result run(Parameter parameter, HttpRequest request, Map<String, String> pathValues) {
        String primaryType = OpenApiStyleParameterDeserializer.primaryType(parameter.getSchema());
        return OpenApiStyleParameterDeserializer.deserialize(parameter, parameter.getIn(), parameter.getName(), parameter.getSchema(), primaryType, request, pathValues);
    }

    private static Map<String, String> path(String value) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("p", value);
        return values;
    }

    private static void assertJson(Parameter parameter, HttpRequest request, Map<String, String> pathValues, String expectedJson) {
        OpenApiStyleParameterDeserializer.Result result = run(parameter, request, pathValues);
        assertThat("present", result.present, is(true));
        assertThat(result.json, is(expectedJson));
    }

    // ---- query arrays ----

    @Test
    public void queryFormNonExplodedIntegerArray() {
        assertJson(param("query", StyleEnum.FORM, false, arrayOf("integer")),
            request().withQueryStringParameter("p", "1,2,3"), null, "[1,2,3]");
    }

    @Test
    public void queryFormExplodedStringArrayRepeated() {
        assertJson(param("query", StyleEnum.FORM, true, arrayOf("string")),
            request().withQueryStringParameter("p", "a", "b"), null, "[\"a\",\"b\"]");
    }

    @Test
    public void queryFormExplodedSingleValueIsOneElement() {
        // an exploded form array repeats the parameter; a single comma value is therefore ONE element
        assertJson(param("query", StyleEnum.FORM, true, arrayOf("string")),
            request().withQueryStringParameter("p", "a,b"), null, "[\"a,b\"]");
    }

    @Test
    public void queryDefaultStyleAndExplodeIsFormExplodeTrue() {
        // no style/explode on the parameter -> OpenAPI default form + explode:true for query
        assertJson(param("query", null, null, arrayOf("string")),
            request().withQueryStringParameter("p", "a,b"), null, "[\"a,b\"]");
    }

    @Test
    public void querySpaceDelimitedIntegerArray() {
        assertJson(param("query", StyleEnum.SPACEDELIMITED, false, arrayOf("integer")),
            request().withQueryStringParameter("p", "1 2 3"), null, "[1,2,3]");
    }

    @Test
    public void queryPipeDelimitedIntegerArray() {
        assertJson(param("query", StyleEnum.PIPEDELIMITED, false, arrayOf("integer")),
            request().withQueryStringParameter("p", "1|2|3"), null, "[1,2,3]");
    }

    @Test
    public void queryArrayWithNonNumericElementEmitsQuotedToken() {
        // a mismatched element is emitted as a JSON string so the schema check correctly fails items:integer
        assertJson(param("query", StyleEnum.FORM, false, arrayOf("integer")),
            request().withQueryStringParameter("p", "1,x,3"), null, "[1,\"x\",3]");
    }

    // ---- query objects ----

    @Test
    public void queryFormNonExplodedObjectAlternating() {
        assertJson(param("query", StyleEnum.FORM, false, objectWith("lat", "number", "lng", "number")),
            request().withQueryStringParameter("p", "lat,1.5,lng,2.5"), null, "{\"lat\":1.5,\"lng\":2.5}");
    }

    @Test
    public void queryDeepObject() {
        assertJson(param("query", StyleEnum.DEEPOBJECT, true, objectWith("a", "integer", "b", "integer")),
            request().withQueryStringParameter("p[a]", "1").withQueryStringParameter("p[b]", "2"), null, "{\"a\":1,\"b\":2}");
    }

    @Test
    public void queryFormExplodedObjectByDeclaredProperties() {
        assertJson(param("query", StyleEnum.FORM, true, objectWith("R", "integer", "G", "integer", "B", "integer")),
            request().withQueryStringParameter("R", "100").withQueryStringParameter("G", "200").withQueryStringParameter("B", "150"),
            null, "{\"R\":100,\"G\":200,\"B\":150}");
    }

    // ---- path arrays ----

    @Test
    public void pathSimpleIntegerArray() {
        assertJson(param("path", StyleEnum.SIMPLE, false, arrayOf("integer")),
            request(), path("1,2,3"), "[1,2,3]");
    }

    @Test
    public void pathLabelNonExplodedArray() {
        assertJson(param("path", StyleEnum.LABEL, false, arrayOf("integer")),
            request(), path(".1,2,3"), "[1,2,3]");
    }

    @Test
    public void pathLabelExplodedArray() {
        assertJson(param("path", StyleEnum.LABEL, true, arrayOf("string")),
            request(), path(".a.b.c"), "[\"a\",\"b\",\"c\"]");
    }

    @Test
    public void pathMatrixNonExplodedArray() {
        assertJson(param("path", StyleEnum.MATRIX, false, arrayOf("integer")),
            request(), path(";p=1,2,3"), "[1,2,3]");
    }

    @Test
    public void pathMatrixExplodedArray() {
        assertJson(param("path", StyleEnum.MATRIX, true, arrayOf("integer")),
            request(), path(";p=1;p=2;p=3"), "[1,2,3]");
    }

    // ---- path objects ----

    @Test
    public void pathSimpleNonExplodedObject() {
        assertJson(param("path", StyleEnum.SIMPLE, false, objectWith("R", "integer", "G", "integer")),
            request(), path("R,100,G,200"), "{\"R\":100,\"G\":200}");
    }

    @Test
    public void pathSimpleExplodedObject() {
        assertJson(param("path", StyleEnum.SIMPLE, true, objectWith("R", "integer", "G", "integer")),
            request(), path("R=100,G=200"), "{\"R\":100,\"G\":200}");
    }

    // ---- header ----

    @Test
    public void headerSimpleStringArray() {
        assertJson(param("header", StyleEnum.SIMPLE, false, arrayOf("string")),
            request().withHeader("p", "a,b,c"), null, "[\"a\",\"b\",\"c\"]");
    }

    @Test
    public void headerSimpleNonExplodedObject() {
        assertJson(param("header", StyleEnum.SIMPLE, false, objectWith("R", "integer", "G", "integer")),
            request().withHeader("p", "R,100,G,200"), null, "{\"R\":100,\"G\":200}");
    }

    // ---- cookie ----

    @Test
    public void cookieFormStringArray() {
        assertJson(param("cookie", StyleEnum.FORM, false, arrayOf("string")),
            request().withCookie("p", "a,b"), null, "[\"a\",\"b\"]");
    }

    // ---- presence and fail-open ----

    @Test
    public void missingRequiredArrayReportsAbsent() {
        OpenApiStyleParameterDeserializer.Result result = run(param("query", StyleEnum.FORM, true, arrayOf("string")),
            request(), null);
        assertThat(result.present, is(false));
        assertThat(result.json, is(nullValue()));
    }

    @Test
    public void nonPrimitiveArrayItemsIsPresentButSkipped() {
        OpenApiStyleParameterDeserializer.Result result = run(
            param("query", StyleEnum.FORM, false, new ArraySchema().items(new ObjectSchema())),
            request().withQueryStringParameter("p", "a,b"), null);
        assertThat(result.present, is(true));
        assertThat("non-primitive items cannot be soundly coerced -> skip schema check", result.json, is(nullValue()));
    }

    @Test
    public void nonPrimitiveObjectPropertyIsPresentButSkipped() {
        assertThat(run(
            param("query", StyleEnum.FORM, false, objectWith("nested", "object")),
            request().withQueryStringParameter("p", "nested,x"), null).json, is(nullValue()));
    }

    @Test
    public void booleanArrayTokensEmittedUnquoted() {
        assertJson(param("query", StyleEnum.FORM, false, arrayOf("boolean")),
            request().withQueryStringParameter("p", "true,false,maybe"), null, "[true,false,\"maybe\"]");
    }
}
