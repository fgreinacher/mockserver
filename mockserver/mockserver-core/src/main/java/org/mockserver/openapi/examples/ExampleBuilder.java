/*
 *  Copyright 2017 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mockserver.openapi.examples;

import io.swagger.util.Json;
import io.swagger.v3.oas.models.media.*;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.openapi.examples.models.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * See: https://github.com/swagger-api/swagger-inflector
 */
@SuppressWarnings("rawtypes")
public class ExampleBuilder {
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(ExampleBuilder.class);

    public static final String SAMPLE_EMAIL_PROPERTY_VALUE = "some_email@mockserver.com";
    public static final String SAMPLE_UUID_PROPERTY_VALUE = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    public static final String SAMPLE_STRING_PROPERTY_VALUE = "some_string_value";
    public static final int SAMPLE_INT_PROPERTY_VALUE = 0;
    public static final int SAMPLE_LONG_PROPERTY_VALUE = 0;
    public static final int SAMPLE_BASE_INTEGER_PROPERTY_VALUE = 0;
    public static final float SAMPLE_FLOAT_PROPERTY_VALUE = 1.1f;
    public static final double SAMPLE_DOUBLE_PROPERTY_VALUE = 1.1;
    public static final boolean SAMPLE_BOOLEAN_PROPERTY_VALUE = true;
    public static final String SAMPLE_BYTE_PROPERTY_VALUE = "c29tZV9ieXRlX3ZhbHVl";
    public static final String SAMPLE_DATE_PROPERTY_VALUE = "2018-11-13";
    public static final String SAMPLE_TIME_PROPERTY_VALUE = "20:20:39+00:00";
    public static final String SAMPLE_DATETIME_PROPERTY_VALUE = SAMPLE_DATE_PROPERTY_VALUE + "T" + SAMPLE_TIME_PROPERTY_VALUE;
    public static final double SAMPLE_DECIMAL_PROPERTY_VALUE = 1.5;

    /**
     * The direction an example is generated for. Controls {@code readOnly}/{@code writeOnly} filtering:
     * {@code readOnly} properties are excluded from {@link #REQUEST} examples and {@code writeOnly}
     * properties are excluded from {@link #RESPONSE} examples, per the OpenAPI specification. When the
     * direction is {@link #UNSPECIFIED} no filtering is applied (the historic behaviour).
     */
    public enum Direction {
        REQUEST,
        RESPONSE,
        UNSPECIFIED
    }

    public static Example fromSchema(Schema<?> property, Map<String, Schema> definitions) {
        return fromSchema(property, definitions, null, Direction.UNSPECIFIED);
    }

    public static Example fromSchema(Schema<?> property, Map<String, Schema> definitions, GenerationOptions generationOptions) {
        return fromSchema(property, definitions, generationOptions, Direction.UNSPECIFIED);
    }

    /**
     * Builds an example from a schema, optionally honouring per-run {@link GenerationOptions}
     * (a caller-chosen seed for reproducible realistic values and per-field value overrides) and a
     * {@link Direction} that drives {@code readOnly}/{@code writeOnly} property filtering.
     * When {@code generationOptions} is {@code null} behaviour is identical to the historic
     * two-arg overload (fixed seed 42, no overrides); when {@code direction} is {@link Direction#UNSPECIFIED}
     * no {@code readOnly}/{@code writeOnly} filtering is applied.
     */
    public static Example fromSchema(Schema<?> property, Map<String, Schema> definitions, GenerationOptions generationOptions, Direction direction) {
        // An explicit per-run choice on the options wins; otherwise defer to the global configuration
        // default. Reading the global only as a fallback (rather than unconditionally here) lets callers
        // and tests drive realistic generation deterministically without mutating shared process state.
        boolean realisticValues = (generationOptions != null && generationOptions.getRealisticValues() != null)
            ? generationOptions.getRealisticValues()
            : ConfigurationProperties.generateRealisticExampleValues();
        SampleDataGenerator generator = null;
        if (realisticValues) {
            generator = (generationOptions != null && generationOptions.getSeed() != null)
                ? new SampleDataGenerator(generationOptions.getSeed())
                : new SampleDataGenerator();
        }
        return fromProperty(null, property, definitions, new ConcurrentHashMap<>(), new ConcurrentSkipListSet<>(), new StringBuilder(), generator, generationOptions, direction);
    }

    public static Example fromProperty(String name, Schema<?> property, Map<String, Schema> definitions, Map<String, Example> processedModels, Set<String> modelsStartedProcessing, StringBuilder location) {
        return fromProperty(name, property, definitions, processedModels, modelsStartedProcessing, location, null, null, Direction.UNSPECIFIED);
    }

    private static Example fromProperty(String name, Schema<?> property, Map<String, Schema> definitions, Map<String, Example> processedModels, Set<String> modelsStartedProcessing, StringBuilder location, SampleDataGenerator generator) {
        return fromProperty(name, property, definitions, processedModels, modelsStartedProcessing, location, generator, null, Direction.UNSPECIFIED);
    }

    private static Example fromProperty(String name, Schema<?> property, Map<String, Schema> definitions, Map<String, Example> processedModels, Set<String> modelsStartedProcessing, StringBuilder location, SampleDataGenerator generator, GenerationOptions generationOptions, Direction direction) {
        location = new StringBuilder(location);
        if (isNotBlank(name)) {
            location.append(name).append(".");
        }

        if (property == null) {
            return null;
        }

        // Per-field override by leaf property name — pins a fixed value for every property with this
        // name, at any nesting depth. An author-declared schema example still wins (handled below).
        if (generationOptions != null && property.getExample() == null && generationOptions.hasOverrideFor(name)) {
            Example overrideExample = overrideToExample(generationOptions.getOverride(name));
            if (overrideExample != null) {
                if (name != null) {
                    overrideExample.setName(name);
                }
                return overrideExample;
            }
        }

        // name = null;
        String namespace = null;
        String prefix = null;
        Boolean attribute = false;
        boolean wrapped = false;

        if (property.getXml() != null) {
            XML xml = property.getXml();
            name = xml.getName();
            namespace = xml.getNamespace();
            prefix = xml.getPrefix();
            attribute = xml.getAttribute();
            wrapped = xml.getWrapped() != null ? xml.getWrapped() : false;
        }

        Example output = null;

        Object example = normalizeFlattenedExample(property.getExample(), property);

        if (property.get$ref() != null) {
            String ref = property.get$ref();
            ref = ref.substring(ref.lastIndexOf("/") + 1);
            if (modelsStartedProcessing.contains(ref) && !processedModels.containsKey(ref)) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setMessageFormat("unable to create example for{}due to forward reference or circular reference")
                        .setArguments(StringUtils.substringBeforeLast(location.toString(), "."))
                );
                return null;
            }
            modelsStartedProcessing.add(ref);
            if (processedModels.containsKey(ref)) {
                // return some sort of example
                output = alreadyProcessedRefExample(ref, definitions, processedModels);
            } else if (definitions != null) {
                Schema<?> model = definitions.get(ref);
                if (model != null) {
                    // resolve the referenced model under the CURRENT property name (the property key /
                    // array context / xml.name held in `name`), NOT the bare schema ref name. A schema
                    // component name (e.g. "Node") is not an XML element name; using it caused a recursive
                    // $ref that the parser could not inline (e.g. Tree{children:[$ref Tree]}) to render
                    // <Tree>/<Node> elements instead of the property name <children>/<next>. The $ref cache
                    // is keyed by the ref STRING below, so this does not affect cycle detection.
                    output = fromProperty(name, model, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                    processedModels.put(ref, output);
                }
            }
        } else if (property instanceof EmailSchema emailSchema) {
            if (example != null) {
                output = new StringExample(example.toString());
            } else {
                String defaultValue = emailSchema.getDefault();

                if (defaultValue == null) {
                    List<String> enums = emailSchema.getEnum();
                    if (enums != null && !enums.isEmpty()) {
                        defaultValue = enums.get(0);
                    }
                }

                output = new StringExample(defaultValue == null ? (generator != null ? generator.email() : SAMPLE_EMAIL_PROPERTY_VALUE) : defaultValue);
            }
        } else if (property instanceof UUIDSchema uuidSchema) {
            if (example != null) {
                output = new StringExample(example.toString());
            } else {
                UUID defaultValue = uuidSchema.getDefault();

                if (defaultValue == null) {
                    List<UUID> enums = uuidSchema.getEnum();
                    if (enums != null && !enums.isEmpty()) {
                        defaultValue = enums.get(0);
                    }
                }

                output = new StringExample(defaultValue == null ? (generator != null ? generator.uuid() : SAMPLE_UUID_PROPERTY_VALUE) : defaultValue.toString());
            }
        } else if (property instanceof ByteArraySchema) {
            if (example != null) {
                output = new StringExample(example.toString());
            } else {
                output = new StringExample(generator != null ? generator.byteString() : SAMPLE_BYTE_PROPERTY_VALUE);
            }
        } else if (property instanceof StringSchema stringSchema) {
            if (example != null) {
                output = new StringExample(example.toString());
            } else {
                String defaultValue = stringSchema.getDefault();

                if (defaultValue == null) {
                    List<String> enums = stringSchema.getEnum();
                    if (enums != null && !enums.isEmpty()) {
                        defaultValue = enums.get(0);
                    }
                }

                if (defaultValue == null) {
                    // honour a pattern (regex) or time format before any generic generation, even when
                    // the realistic-values flag is off, so constrained fields produce valid example data
                    output = constrainedStringExample(stringSchema, generator);
                    if (output == null) {
                        if (generator != null) {
                            String format = stringSchema.getFormat();
                            if ("uri".equals(format) || "url".equals(format)) {
                                output = new StringExample(generator.uri());
                            } else if ("hostname".equals(format)) {
                                output = new StringExample(generator.hostname());
                            } else if ("ipv4".equals(format)) {
                                output = new StringExample(generator.ipv4());
                            } else if ("ipv6".equals(format)) {
                                output = new StringExample(generator.ipv6());
                            } else {
                                output = new StringExample(generator.stringWithConstraints(stringSchema.getMinLength(), stringSchema.getMaxLength()));
                            }
                        } else {
                            output = new StringExample(SAMPLE_STRING_PROPERTY_VALUE);
                        }
                    }
                } else {
                    output = new StringExample(defaultValue);
                }
            }
        } else if (property instanceof PasswordSchema passwordSchema) {
            if (example != null) {
                output = new StringExample(example.toString());
            } else {
                String defaultValue = passwordSchema.getDefault();

                if (defaultValue == null) {
                    List<String> enums = passwordSchema.getEnum();
                    if (enums != null && !enums.isEmpty()) {
                        defaultValue = enums.get(0);
                    }
                }

                output = new StringExample(defaultValue == null ? (generator != null ? generator.password() : SAMPLE_STRING_PROPERTY_VALUE) : defaultValue);
            }
        } else if (property instanceof IntegerSchema integerSchema) {
            if (example != null) {
                try {
                    if (property.getFormat() != null) {
                        if (property.getFormat().equals("int32")) {
                            output = new IntegerExample(Integer.parseInt(example.toString()));
                        } else if (property.getFormat().equals("int64")) {
                            output = new LongExample(Long.parseLong(example.toString()));
                        }
                    } else {
                        // no format: an author example may exceed the int range (e.g. 3000000000) — fall
                        // back to Long, then BigInteger, so a valid author example is preserved rather
                        // than silently swallowed and replaced by a generated/sample value
                        try {
                            output = new IntegerExample(Integer.parseInt(example.toString()));
                        } catch (NumberFormatException tooLargeForInt) {
                            try {
                                output = new LongExample(Long.parseLong(example.toString()));
                            } catch (NumberFormatException tooLargeForLong) {
                                output = new BigIntegerExample(new BigInteger(example.toString()));
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            if (output == null) {
                Number defaultValue = integerSchema.getDefault();

                if (defaultValue == null) {
                    List<Number> enums = integerSchema.getEnum();
                    if (enums != null && !enums.isEmpty()) {
                        defaultValue = enums.get(0);
                    }
                }
                BigDecimal intMin = effectiveMinimum(property, INTEGER_STEP);
                BigDecimal intMax = effectiveMaximum(property, INTEGER_STEP);
                if (property.getFormat() != null) {
                    if (property.getFormat().equals("int32")) {
                        output = new IntegerExample(defaultValue == null ? (generator != null ? generator.integer(intMin, intMax) : intSampleWithin(intMin, intMax, SAMPLE_INT_PROPERTY_VALUE)) : defaultValue.intValue());
                    } else if (property.getFormat().equals("int64")) {
                        output = new LongExample(defaultValue == null ? (generator != null ? generator.longValue(intMin, intMax) : longSampleWithin(intMin, intMax, SAMPLE_LONG_PROPERTY_VALUE)) : defaultValue.longValue());
                    }
                } else {
                    // no format: honour an explicit default/enum value before falling back to the
                    // generator/sample (mirrors the int32/int64 branches and generateExampleForType)
                    output = new IntegerExample(defaultValue == null ? (generator != null ? generator.integer(intMin, intMax) : intSampleWithin(intMin, intMax, SAMPLE_BASE_INTEGER_PROPERTY_VALUE)) : defaultValue.intValue());
                }
            }
        } else if (property instanceof NumberSchema numberSchema) {

            if (example != null) {
                try {
                    if (property.getFormat() != null) {
                        if (property.getFormat().equals("double")) {
                            output = new DoubleExample(Double.parseDouble(example.toString()));
                        } else if (property.getFormat().equals("float")) {
                            output = new FloatExample(Float.parseFloat(example.toString()));
                        }
                    } else {
                        output = new DecimalExample(new BigDecimal(example.toString()));
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            if (output == null) {
                BigDecimal defaultValue = numberSchema.getDefault();

                if (defaultValue == null) {
                    List<BigDecimal> enums = numberSchema.getEnum();
                    if (enums != null && !enums.isEmpty()) {
                        defaultValue = enums.get(0);
                    }
                }
                BigDecimal numMin = effectiveMinimum(property, DECIMAL_STEP);
                BigDecimal numMax = effectiveMaximum(property, DECIMAL_STEP);
                if (property.getFormat() != null) {
                    if (property.getFormat().equals("double")) {
                        output = new DoubleExample(defaultValue == null ? (generator != null ? generator.doubleValue(numMin, numMax) : decimalSampleWithin(numMin, numMax, BigDecimal.valueOf(SAMPLE_DOUBLE_PROPERTY_VALUE)).doubleValue()) : defaultValue.doubleValue());
                    }
                    if (property.getFormat().equals("float")) {
                        output = new FloatExample(defaultValue == null ? (generator != null ? generator.floatValue(numMin, numMax) : decimalSampleWithin(numMin, numMax, BigDecimal.valueOf(SAMPLE_FLOAT_PROPERTY_VALUE)).floatValue()) : defaultValue.floatValue());
                    }
                } else {
                    // no format: honour an explicit default/enum value before falling back to the
                    // generator/sample (mirrors the float/double branches and generateExampleForType)
                    output = new DecimalExample(defaultValue == null ? (generator != null ? generator.decimal(numMin, numMax) : decimalSampleWithin(numMin, numMax, new BigDecimal(SAMPLE_DECIMAL_PROPERTY_VALUE))) : defaultValue);
                }
            }

        } else if (property instanceof BooleanSchema) {
            if (example != null) {
                output = new BooleanExample(Boolean.parseBoolean(example.toString()));
            } else {
                Boolean defaultValue = (Boolean) property.getDefault();
                output = new BooleanExample(defaultValue == null ? (generator != null ? generator.booleanValue() : SAMPLE_BOOLEAN_PROPERTY_VALUE) : defaultValue);
            }
        } else if (property instanceof DateSchema dateSchema) {
            DateFormat format = new SimpleDateFormat("yyyy-MM-dd");
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            if (example != null) {
                String exampleAsString = format.format(example);
                output = new StringExample(exampleAsString);
            } else {

                List<Date> enums = dateSchema.getEnum();
                if (enums != null && !enums.isEmpty()) {
                    output = new StringExample(format.format(enums.get(0)));
                } else {
                    output = new StringExample(generator != null ? generator.dateString() : SAMPLE_DATE_PROPERTY_VALUE);
                }
            }
        } else if (property instanceof DateTimeSchema dateTimeSchema) {
            if (example != null) {
                String exampleAsString = example.toString();
                output = new StringExample(exampleAsString);
            } else {
                List<OffsetDateTime> enums = dateTimeSchema.getEnum();
                if (enums != null && !enums.isEmpty()) {
                    output = new StringExample(enums.get(0).toString());
                } else {
                    output = new StringExample(generator != null ? generator.dateTimeString() : SAMPLE_DATETIME_PROPERTY_VALUE);
                }
            }
        } else if (property instanceof ObjectSchema objectSchema) {
            if (example != null) {
                try {
                    output = Json.mapper().readValue(example.toString(), ObjectExample.class);
                } catch (IOException e) {
                    MOCK_SERVER_LOGGER.logEvent(
                        new LogEntry()
                            .setMessageFormat("unable to convert{}to JsonNode")
                            .setArguments(example)
                            .setArguments(example)
                    );
                    output = new ObjectExample();
                }
            } else {
                ObjectExample outputExample = new ObjectExample();
                outputExample.setName(property.getName());
                if (objectSchema.getProperties() != null) {
                    for (String propertyname : objectSchema.getProperties().keySet()) {
                        Schema<?> inner = objectSchema.getProperties().get(propertyname);
                        if (excludedByDirection(inner, direction)) {
                            continue;
                        }
                        Example innerExample = fromProperty(propertyname, inner, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                        outputExample.put(propertyname, innerExample);
                    }
                    output = outputExample;
                }

            }
        } else if (property instanceof ArraySchema arraySchema) {
            if (example != null) {
                try {
                    output = Json.mapper().readValue(example.toString(), ArrayExample.class);
                } catch (IOException e) {
                    MOCK_SERVER_LOGGER.logEvent(
                        new LogEntry()
                            .setMessageFormat("unable to create example for{}because unable to convert{}to JsonNode")
                            .setArguments(StringUtils.substringBeforeLast(location.toString(), "."))
                            .setArguments(example)
                    );
                    output = new ArrayExample();
                }
            } else {
                Schema<?> inner = arraySchema.getItems();
                if (inner != null) {
                    // emit minItems items (clamped, default 1 when unset) so the example satisfies an
                    // array length constraint instead of always producing a single element.
                    int itemCount = arrayItemCount(arraySchema);
                    ArrayExample an = new ArrayExample();
                    boolean built = itemCount == 0;
                    for (int i = 0; i < itemCount; i++) {
                        // pass a null item name (NOT the array's type, "array"): an array item's XML element name
                        // is its own xml.name when set, otherwise the array property's name, which the XML
                        // serializer applies as a fallback only when the item name is null. Baking "array" in here
                        // produced <array> item elements instead of e.g. <photoUrls>.
                        Example innerExample = fromProperty(null, inner, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                        if (innerExample != null) {
                            an.add(innerExample);
                            built = true;
                        }
                    }
                    if (built) {
                        an.setName(property.getName());
                        output = an;
                    }
                }
            }
        } else if (property instanceof ComposedSchema composedSchema) {
            if (composedSchema.getAllOf() != null) {
                List<Schema> models = composedSchema.getAllOf();
                List<Example> innerExamples = new ArrayList<>();
                if (models != null) {
                    for (Schema im : models) {
                        Example innerExample = fromProperty(im.getType(), im, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                        if (innerExample != null) {
                            innerExamples.add(innerExample);
                        }
                    }
                }
                boolean hasOwnProperties = composedSchema.getProperties() != null && !composedSchema.getProperties().isEmpty();
                boolean anyObjectMember = innerExamples.stream().anyMatch(innerExample -> innerExample instanceof ObjectExample);
                if (!innerExamples.isEmpty() && !hasOwnProperties && !anyObjectMember) {
                    // allOf composed entirely of scalar (non-object) subschemas — e.g. allOf: [ $ref to a string ].
                    // Per JSON-Schema allOf semantics the resolved type is that scalar, so emit the scalar value
                    // itself rather than wrapping it in an (empty) object or array. See #2357.
                    output = innerExamples.get(0);
                } else {
                    ObjectExample ex = new ObjectExample();
                    if (composedSchema.getProperties() != null) {
                        Map<String, Schema> ownProperties = composedSchema.getProperties();
                        for (Map.Entry<String, Schema> entry : ownProperties.entrySet()) {
                            if (excludedByDirection(entry.getValue(), direction)) {
                                continue;
                            }
                            Example propExample = fromProperty(entry.getKey(), entry.getValue(), definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                            if (propExample != null) {
                                ex.put(entry.getKey(), propExample);
                            }
                        }
                    }
                    mergeTo(ex, innerExamples);
                    output = ex;
                }
            }
            if (composedSchema.getAnyOf() != null) {
                List<Schema> models = composedSchema.getAnyOf();
                if (models != null) {
                    Example discriminated = discriminatedExample(composedSchema, models, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                    if (discriminated != null) {
                        output = discriminated;
                    } else {
                        for (Schema im : models) {
                            Example innerExample = fromProperty(property.getType(), im, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                            if (innerExample != null) {
                                output = innerExample;
                                break;
                            }
                        }
                    }
                }
            }
            if (composedSchema.getOneOf() != null) {
                List<Schema> models = composedSchema.getOneOf();
                if (models != null) {
                    Example discriminated = discriminatedExample(composedSchema, models, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                    if (discriminated != null) {
                        output = discriminated;
                    } else {
                        for (Schema im : models) {
                            Example innerExample = fromProperty(property.getType(), im, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                            if (innerExample != null) {
                                output = innerExample;
                                break;
                            }
                        }
                    }
                }
            }
        } else if (output == null && property.getTypes() != null && !property.getTypes().isEmpty()) {
            // OpenAPI 3.1 type arrays (e.g. type: [string, "null"]) — pick the first non-null type
            String primaryType = resolveOas31PrimaryType(property.getTypes());
            if (primaryType != null) {
                output = generateExampleForType(primaryType, property, example, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
            }
        } else if (property.getProperties() != null) {
            if (example != null) {
                try {
                    output = Json.mapper().readValue(example.toString(), ObjectExample.class);
                } catch (IOException e) {
                    MOCK_SERVER_LOGGER.logEvent(
                        new LogEntry()
                            .setMessageFormat("unable to convert{}to JsonNode")
                            .setArguments(example)
                    );
                    output = new ObjectExample();
                }
            } else {
                ObjectExample ex = new ObjectExample();

                if (property.getProperties() != null) {
                    Map<String, Schema> properties = property.getProperties();
                    for (String propertyKey : properties.keySet()) {
                        Schema inner = properties.get(propertyKey);
                        if (excludedByDirection(inner, direction)) {
                            continue;
                        }
                        Example propExample = fromProperty(propertyKey, inner, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                        ex.put(propertyKey, propExample);
                    }
                }

                output = ex;
            }

        }
        if (property.getAdditionalProperties() instanceof Schema<?> inner) {
            if (inner != null) {
                // emit at least minProperties entries (default 3) so a minProperties constraint on a
                // free-form / additionalProperties object is satisfied; clamp to a small cap.
                int additionalCount = 3;
                Integer minProperties = property.getMinProperties();
                if (minProperties != null) {
                    int alreadyPresent = output instanceof ObjectExample existing ? existing.keySet().size() : 0;
                    int needed = minProperties - alreadyPresent;
                    if (needed > additionalCount) {
                        additionalCount = Math.min(needed, PROPERTY_CAP);
                    }
                }
                for (int i = 1; i <= additionalCount; i++) {
                    Example innerExample = fromProperty(inner.getType(), inner, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                    if (innerExample != null) {
                        if (output == null) {
                            output = new ObjectExample();
                        }
                        ObjectExample on = null;
                        if (output instanceof ObjectExample objectExample) {
                            on = objectExample;
                        }
                        String key = "additionalProp" + i;
                        if (innerExample.getName() == null) {
                            innerExample.setName(key);
                        }

                        if (on != null && !on.keySet().contains(key)) {
                            on.put(key, innerExample);
                        }
                    }
                }
            }
        } else if (property.getAdditionalProperties() instanceof Boolean && output == null) {
            output = new ObjectExample();
        }
        if (output != null) {
            if (attribute != null) {
                output.setAttribute(attribute);
            }
            if (wrapped) {
                if (name != null) {
                    output.setWrappedName(name);
                }
            } else if (name != null) {
                output.setName(name);
            }
            output.setNamespace(namespace);
            output.setPrefix(prefix);
            output.setWrapped(wrapped);
        }
        return output;
    }

    public static Example alreadyProcessedRefExample(String name, Map<String, Schema> definitions, Map<String, Example> processedModels) {
        if (processedModels.get(name) != null) {
            return processedModels.get(name);
        }
        Schema<?> model = definitions.get(name);
        if (model == null) {
            return null;
        }
        Example output = null;

        // look at type — check both getType() (OAS 3.0) and getTypes() (OAS 3.1)
        String resolvedType = model.getType();
        if (resolvedType == null) {
            resolvedType = resolveOas31PrimaryType(model.getTypes());
        }
        if (resolvedType != null) {
            if ("object".equals(resolvedType)) {
                return new ObjectExample();
            } else if ("string".equals(resolvedType)) {
                return new StringExample("");
            } else if ("integer".equals(resolvedType)) {
                return new IntegerExample(0);
            } else if ("long".equals(resolvedType)) {
                return new LongExample(0);
            } else if ("float".equals(resolvedType)) {
                return new FloatExample(0);
            } else if ("double".equals(resolvedType)) {
                return new DoubleExample(0);
            }
        }

        return output;
    }


    public static void mergeTo(ObjectExample output, List<Example> examples) {
        for (Example ex : examples) {
            if (ex instanceof ObjectExample objectExample) {
                Map<String, Example> values = objectExample.getValues();
                if (values != null) {
                    output.putAll(values);
                }
            }
        }
    }

    /**
     * Undoes the swagger-parser {@code resolveFully}/{@code resolveCombinators} artefact whereby a
     * scalar schema flattened from {@code allOf: [ $ref to a scalar ]} ends up with its {@code example}
     * wrapped in a single-element {@link Collection} (a {@code Set} or {@code List}). For a scalar-typed
     * schema such a collection is never a valid example, so it is unwrapped to the contained value.
     * Non-scalar schemas (objects, arrays) and multi-element collections are returned unchanged. See #2357.
     */
    public static Object normalizeFlattenedExample(Object example, Schema<?> schema) {
        if (example instanceof Collection<?> collection && collection.size() == 1 && isScalarType(schema)) {
            return collection.iterator().next();
        }
        return example;
    }

    private static boolean isScalarType(Schema<?> schema) {
        if (schema == null) {
            return false;
        }
        String type = schema.getType();
        if (type == null) {
            type = resolveOas31PrimaryType(schema.getTypes());
        }
        return "string".equals(type) || "integer".equals(type) || "number".equals(type) || "boolean".equals(type);
    }

    /**
     * Whether a property must be omitted from the example given the generation {@link Direction}:
     * {@code readOnly} properties are excluded from {@link Direction#REQUEST} examples and
     * {@code writeOnly} properties from {@link Direction#RESPONSE} examples (per the OpenAPI spec).
     * {@link Direction#UNSPECIFIED} (and a {@code null} direction) never excludes, preserving the
     * historic behaviour where direction is unknown.
     */
    static boolean excludedByDirection(Schema<?> propertySchema, Direction direction) {
        if (propertySchema == null || direction == null || direction == Direction.UNSPECIFIED) {
            return false;
        }
        if (direction == Direction.REQUEST) {
            return Boolean.TRUE.equals(propertySchema.getReadOnly());
        }
        return Boolean.TRUE.equals(propertySchema.getWriteOnly());
    }

    /**
     * Builds an example for a {@code oneOf}/{@code anyOf} schema that declares a {@code discriminator}:
     * a concrete subschema is selected and the discriminator property is set to the matching value.
     * When an explicit {@code mapping} is present its first entry is used ({@code value -> schema ref});
     * otherwise the first subschema is used and, if it is a {@code $ref}, the referenced schema's short
     * name is the discriminator value. Returns {@code null} when the schema declares no usable
     * discriminator, so the caller falls back to its default first-non-null selection.
     */
    @SuppressWarnings("unchecked")
    private static Example discriminatedExample(ComposedSchema composedSchema, List<Schema> members, Map<String, Schema> definitions, Map<String, Example> processedModels, Set<String> modelsStartedProcessing, StringBuilder location, SampleDataGenerator generator, GenerationOptions generationOptions, Direction direction) {
        Discriminator discriminator = composedSchema.getDiscriminator();
        if (discriminator == null || discriminator.getPropertyName() == null || members == null || members.isEmpty()) {
            return null;
        }
        String propertyName = discriminator.getPropertyName();
        Map<String, String> mapping = discriminator.getMapping();
        Schema<?> chosenSchema;
        String discriminatorValue;
        if (mapping != null && !mapping.isEmpty()) {
            Map.Entry<String, String> first = mapping.entrySet().iterator().next();
            discriminatorValue = first.getKey();
            // the mapping value may be a bare schema name or a full $ref; fromProperty resolves both by
            // taking the segment after the last '/', so a bare name looks up directly in definitions
            Schema<Object> ref = new Schema<>();
            ref.set$ref(first.getValue());
            chosenSchema = ref;
        } else {
            chosenSchema = members.get(0);
            discriminatorValue = refShortName(chosenSchema.get$ref());
        }
        Example inner = fromProperty(null, chosenSchema, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
        if (inner instanceof ObjectExample objectExample && discriminatorValue != null) {
            objectExample.put(propertyName, new StringExample(discriminatorValue));
        }
        return inner;
    }

    private static String refShortName(String ref) {
        if (ref == null) {
            return null;
        }
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    /**
     * Effective inclusive minimum for numeric generation, folding in {@code exclusiveMinimum}.
     * Supports both OpenAPI 3.0 ({@code exclusiveMinimum} boolean flag paired with {@code minimum})
     * and OpenAPI 3.1 ({@code exclusiveMinimumValue} numeric). Returns a value strictly greater than
     * the exclusive bound by {@code step}; {@code null} when no minimum constraint applies.
     */
    static BigDecimal effectiveMinimum(Schema<?> property, BigDecimal step) {
        BigDecimal exclusiveValue = property.getExclusiveMinimumValue();
        if (exclusiveValue != null) {
            return exclusiveValue.add(step);
        }
        BigDecimal minimum = property.getMinimum();
        if (minimum != null && Boolean.TRUE.equals(property.getExclusiveMinimum())) {
            return minimum.add(step);
        }
        return minimum;
    }

    /**
     * Effective inclusive maximum for numeric generation, folding in {@code exclusiveMaximum}
     * (both the OpenAPI 3.0 boolean-flag and 3.1 numeric forms). Returns a value strictly less than
     * the exclusive bound by {@code step}; {@code null} when no maximum constraint applies.
     */
    static BigDecimal effectiveMaximum(Schema<?> property, BigDecimal step) {
        BigDecimal exclusiveValue = property.getExclusiveMaximumValue();
        if (exclusiveValue != null) {
            return exclusiveValue.subtract(step);
        }
        BigDecimal maximum = property.getMaximum();
        if (maximum != null && Boolean.TRUE.equals(property.getExclusiveMaximum())) {
            return maximum.subtract(step);
        }
        return maximum;
    }

    private static final BigDecimal INTEGER_STEP = BigDecimal.ONE;
    private static final BigDecimal DECIMAL_STEP = new BigDecimal("0.01");

    /**
     * Number of array items to emit for the given items schema, honouring {@code minItems} /
     * {@code maxItems}. Defaults to 1 when unconstrained (historic behaviour); clamps {@code minItems}
     * to a small cap so a large {@code minItems} does not produce an unwieldy example, and never
     * emits more than {@code maxItems} (treating {@code maxItems < 1} as 0).
     */
    static int arrayItemCount(Schema<?> arraySchema) {
        int count = 1;
        Integer minItems = arraySchema.getMinItems();
        if (minItems != null && minItems > 1) {
            count = Math.min(minItems, ARRAY_ITEM_CAP);
        }
        Integer maxItems = arraySchema.getMaxItems();
        if (maxItems != null && maxItems < count) {
            count = Math.max(maxItems, 0);
        }
        return count;
    }

    private static final int ARRAY_ITEM_CAP = 5;

    // free-form minProperties cap — larger than the array cap because object property counts
    // requested via minProperties tend to be modest but more than a handful.
    private static final int PROPERTY_CAP = 10;

    // Reusable deterministic generator (fixed seed) for honouring pattern/time constraints when the
    // realistic-values flag is off, so a Faker instance is not allocated per field.
    private static final SampleDataGenerator STATIC_GENERATOR = new SampleDataGenerator();

    /**
     * Static-mode integer sample that respects effective (exclusive-folded) bounds: returns the
     * {@code fallback} sample when it lies within the bounds, otherwise the nearest in-bounds value.
     * Keeps the historic sample (0) for unconstrained schemas while ensuring a constrained value
     * (e.g. {@code exclusiveMinimum: 0}) is not violated even when realistic generation is off.
     */
    private static int intSampleWithin(BigDecimal minimum, BigDecimal maximum, int fallback) {
        // clamp the BigDecimal bounds to the int range first so a bound beyond Integer.MAX/MIN_VALUE
        // (e.g. a malformed int32 schema with exclusiveMinimum: 3000000000) is not silently truncated
        // by BigDecimal.intValue()'s modular conversion.
        int value = fallback;
        if (minimum != null) {
            int min = clampToInt(minimum);
            if (value < min) {
                value = min;
            }
        }
        if (maximum != null) {
            int max = clampToInt(maximum);
            if (value > max) {
                value = max;
            }
        }
        return value;
    }

    private static int clampToInt(BigDecimal value) {
        if (value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return Integer.MAX_VALUE;
        }
        if (value.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0) {
            return Integer.MIN_VALUE;
        }
        return value.intValue();
    }

    /**
     * Static-mode long sample that respects effective (exclusive-folded) bounds — like
     * {@link #intSampleWithin(BigDecimal, BigDecimal, int)} but using {@code long} arithmetic so int64
     * bounds beyond the int range (e.g. {@code exclusiveMinimum: 3000000000}) are not truncated.
     */
    private static long longSampleWithin(BigDecimal minimum, BigDecimal maximum, long fallback) {
        // clamp to the long range first (mirrors intSampleWithin/clampToInt) so a bound beyond
        // Long.MAX/MIN_VALUE is not silently truncated by BigDecimal.longValue()'s modular conversion.
        long value = fallback;
        if (minimum != null) {
            long min = clampToLong(minimum);
            if (value < min) {
                value = min;
            }
        }
        if (maximum != null) {
            long max = clampToLong(maximum);
            if (value > max) {
                value = max;
            }
        }
        return value;
    }

    private static long clampToLong(BigDecimal value) {
        if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return Long.MAX_VALUE;
        }
        if (value.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0) {
            return Long.MIN_VALUE;
        }
        return value.longValue();
    }

    /**
     * Static-mode decimal sample that respects effective (exclusive-folded) bounds — analogous to
     * {@link #intSampleWithin(BigDecimal, BigDecimal, int)} for number schemas.
     */
    private static BigDecimal decimalSampleWithin(BigDecimal minimum, BigDecimal maximum, BigDecimal fallback) {
        BigDecimal value = fallback;
        if (minimum != null && value.compareTo(minimum) < 0) {
            value = minimum;
        }
        if (maximum != null && value.compareTo(maximum) > 0) {
            value = maximum;
        }
        return value;
    }

    /**
     * Generates a {@link StringExample} that satisfies a {@code pattern} (regex) or {@code time}
     * format constraint, or {@code null} when neither applies or the constraint cannot be honoured
     * (the caller then keeps its existing default behaviour). A deterministic generator is used even
     * when the realistic-values flag is off so pattern/time fields still produce valid example data.
     * <p>
     * A {@code pattern} takes priority over a recognised string {@code format} (uri/hostname/ipv4/ipv6):
     * a pattern is the more specific constraint and a format-shaped value may not satisfy it.
     */
    private static StringExample constrainedStringExample(Schema<?> property, SampleDataGenerator generator) {
        // reuse the shared deterministic generator when the realistic-values flag is off, rather than
        // allocating a Faker per field
        SampleDataGenerator effectiveGenerator = generator != null ? generator : STATIC_GENERATOR;
        String pattern = property.getPattern();
        if (isNotBlank(pattern)) {
            String generated = effectiveGenerator.regexify(pattern);
            if (generated != null) {
                return new StringExample(generated);
            }
        }
        if ("time".equals(property.getFormat())) {
            return new StringExample(effectiveGenerator.timeString());
        }
        return null;
    }

    /**
     * Resolves the primary (non-null) type from an OpenAPI 3.1 type array.
     * For example, {@code type: [string, "null"]} returns {@code "string"}.
     * If all types are "null", returns null.
     */
    static String resolveOas31PrimaryType(Set<String> types) {
        if (types == null || types.isEmpty()) {
            return null;
        }
        for (String type : types) {
            if (!"null".equals(type)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Converts a per-field override value (from {@link GenerationOptions}) into the matching
     * {@link Example} type so it serialises with the right JSON shape (string vs number vs boolean).
     * Unknown/complex types fall back to their {@code toString()} as a string example.
     */
    private static Example overrideToExample(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean booleanValue) {
            return new BooleanExample(booleanValue);
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return new IntegerExample(((Number) value).intValue());
        }
        if (value instanceof Long longValue) {
            return new LongExample(longValue);
        }
        if (value instanceof BigDecimal bigDecimal) {
            return new DecimalExample(bigDecimal);
        }
        if (value instanceof Number number) {
            return new DoubleExample(number.doubleValue());
        }
        return new StringExample(value.toString());
    }

    /**
     * Generates an example value for a schema based on its type string.
     * Used when the schema does not match any typed subclass (e.g. OpenAPI 3.1
     * schemas with {@code type} as an array).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Example generateExampleForType(
        String type,
        Schema<?> property,
        Object example,
        Map<String, Schema> definitions,
        Map<String, Example> processedModels,
        Set<String> modelsStartedProcessing,
        StringBuilder location,
        SampleDataGenerator generator,
        GenerationOptions generationOptions,
        Direction direction
    ) {
        return switch (type) {
            case "string" -> {
                if (example != null) {
                    yield new StringExample(example.toString());
                }
                Object stringDefault = property.getDefault();
                if (stringDefault != null) {
                    yield new StringExample(stringDefault.toString());
                }
                List<?> stringEnums = property.getEnum();
                if (stringEnums != null && !stringEnums.isEmpty()) {
                    yield new StringExample(stringEnums.get(0).toString());
                }
                // pattern (and the time format) take priority over a recognised format — but after an
                // explicit default/enum — matching the typed StringSchema branch's priority order
                StringExample patternOrTime = constrainedStringExample(property, generator);
                if (patternOrTime != null) {
                    yield patternOrTime;
                }
                String format = property.getFormat();
                if ("date".equals(format)) {
                    yield new StringExample(generator != null ? generator.dateString() : SAMPLE_DATE_PROPERTY_VALUE);
                } else if ("date-time".equals(format)) {
                    yield new StringExample(generator != null ? generator.dateTimeString() : SAMPLE_DATETIME_PROPERTY_VALUE);
                } else if ("email".equals(format)) {
                    yield new StringExample(generator != null ? generator.email() : SAMPLE_EMAIL_PROPERTY_VALUE);
                } else if ("uuid".equals(format)) {
                    yield new StringExample(generator != null ? generator.uuid() : SAMPLE_UUID_PROPERTY_VALUE);
                } else if ("byte".equals(format)) {
                    yield new StringExample(generator != null ? generator.byteString() : SAMPLE_BYTE_PROPERTY_VALUE);
                } else if ("uri".equals(format) || "url".equals(format)) {
                    yield new StringExample(generator != null ? generator.uri() : SAMPLE_STRING_PROPERTY_VALUE);
                } else if ("password".equals(format)) {
                    yield new StringExample(generator != null ? generator.password() : SAMPLE_STRING_PROPERTY_VALUE);
                } else {
                    // default/enum/pattern/time already handled above
                    yield new StringExample(generator != null ? generator.stringWithConstraints(property.getMinLength(), property.getMaxLength()) : SAMPLE_STRING_PROPERTY_VALUE);
                }
            }
            case "integer" -> {
                if (example != null) {
                    try {
                        if ("int64".equals(property.getFormat())) {
                            yield new LongExample(Long.parseLong(example.toString()));
                        }
                        // no/int32 format: an author example may exceed the int range (e.g. 3000000000) —
                        // fall back to Long, then BigInteger, so a valid author example is preserved rather
                        // than silently swallowed and replaced by a generated/sample value (mirrors the
                        // typed IntegerSchema branch)
                        try {
                            yield new IntegerExample(Integer.parseInt(example.toString()));
                        } catch (NumberFormatException tooLargeForInt) {
                            try {
                                yield new LongExample(Long.parseLong(example.toString()));
                            } catch (NumberFormatException tooLargeForLong) {
                                yield new BigIntegerExample(new BigInteger(example.toString()));
                            }
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                BigDecimal intMin = effectiveMinimum(property, INTEGER_STEP);
                BigDecimal intMax = effectiveMaximum(property, INTEGER_STEP);
                if ("int64".equals(property.getFormat())) {
                    yield new LongExample(generator != null ? generator.longValue(intMin, intMax) : longSampleWithin(intMin, intMax, SAMPLE_LONG_PROPERTY_VALUE));
                }
                yield new IntegerExample(generator != null ? generator.integer(intMin, intMax) : intSampleWithin(intMin, intMax, SAMPLE_INT_PROPERTY_VALUE));
            }
            case "number" -> {
                if (example != null) {
                    try {
                        if ("float".equals(property.getFormat())) {
                            yield new FloatExample(Float.parseFloat(example.toString()));
                        } else if ("double".equals(property.getFormat())) {
                            yield new DoubleExample(Double.parseDouble(example.toString()));
                        }
                        yield new DecimalExample(new BigDecimal(example.toString()));
                    } catch (NumberFormatException ignored) {
                    }
                }
                BigDecimal numMin = effectiveMinimum(property, DECIMAL_STEP);
                BigDecimal numMax = effectiveMaximum(property, DECIMAL_STEP);
                if ("float".equals(property.getFormat())) {
                    yield new FloatExample(generator != null ? generator.floatValue(numMin, numMax) : decimalSampleWithin(numMin, numMax, BigDecimal.valueOf(SAMPLE_FLOAT_PROPERTY_VALUE)).floatValue());
                } else if ("double".equals(property.getFormat())) {
                    yield new DoubleExample(generator != null ? generator.doubleValue(numMin, numMax) : decimalSampleWithin(numMin, numMax, BigDecimal.valueOf(SAMPLE_DOUBLE_PROPERTY_VALUE)).doubleValue());
                }
                yield new DecimalExample(generator != null ? generator.decimal(numMin, numMax) : decimalSampleWithin(numMin, numMax, new BigDecimal(SAMPLE_DECIMAL_PROPERTY_VALUE)));
            }
            case "boolean" -> {
                if (example != null) {
                    yield new BooleanExample(Boolean.parseBoolean(example.toString()));
                }
                Object defaultValue = property.getDefault();
                yield new BooleanExample(defaultValue instanceof Boolean b ? b : (generator != null ? generator.booleanValue() : SAMPLE_BOOLEAN_PROPERTY_VALUE));
            }
            case "object" -> {
                if (example != null) {
                    try {
                        yield Json.mapper().readValue(example.toString(), ObjectExample.class);
                    } catch (IOException e) {
                        MOCK_SERVER_LOGGER.logEvent(
                            new LogEntry()
                                .setMessageFormat("unable to convert{}to JsonNode")
                                .setArguments(example)
                        );
                        yield new ObjectExample();
                    }
                }
                ObjectExample objectEx = new ObjectExample();
                objectEx.setName(property.getName());
                if (property.getProperties() != null) {
                    for (Map.Entry<String, Schema> entry : ((Map<String, Schema>) property.getProperties()).entrySet()) {
                        if (excludedByDirection(entry.getValue(), direction)) {
                            continue;
                        }
                        Example innerExample = fromProperty(entry.getKey(), entry.getValue(), definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                        objectEx.put(entry.getKey(), innerExample);
                    }
                }
                yield objectEx;
            }
            case "array" -> {
                if (example != null) {
                    try {
                        yield Json.mapper().readValue(example.toString(), ArrayExample.class);
                    } catch (IOException e) {
                        MOCK_SERVER_LOGGER.logEvent(
                            new LogEntry()
                                .setMessageFormat("unable to create example for{}because unable to convert{}to JsonNode")
                                .setArguments(StringUtils.substringBeforeLast(location.toString(), "."))
                                .setArguments(example)
                        );
                        yield new ArrayExample();
                    }
                }
                Schema<?> items = property.getItems();
                if (items != null) {
                    // emit minItems items (clamped, default 1 when unset); see the ArraySchema branch above.
                    int itemCount = arrayItemCount(property);
                    ArrayExample arrayEx = new ArrayExample();
                    boolean built = itemCount == 0;
                    for (int i = 0; i < itemCount; i++) {
                        // null item name (not the array "type"): the array property name is the item element-name
                        // fallback in the XML serializer; see the ArraySchema branch above.
                        Example innerExample = fromProperty(null, items, definitions, processedModels, modelsStartedProcessing, location, generator, generationOptions, direction);
                        if (innerExample != null) {
                            arrayEx.add(innerExample);
                            built = true;
                        }
                    }
                    if (built) {
                        arrayEx.setName(property.getName());
                        yield arrayEx;
                    }
                }
                yield new ArrayExample();
            }
            default -> null;
        };
    }
}