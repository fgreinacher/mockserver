package org.mockserver.openapi.examples;

import io.swagger.v3.oas.models.media.EmailSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.openapi.OpenApiParameterExamples;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.StringExample;

import java.util.HashMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.openapi.examples.ExampleBuilder.SAMPLE_EMAIL_PROPERTY_VALUE;
import static org.mockserver.openapi.examples.ExampleBuilder.fromSchema;

/**
 * Proves that {@code generateRealisticExampleValues} set on a {@link Configuration} INSTANCE — as
 * {@code PUT /mockserver/configuration} does — actually reaches {@link ExampleBuilder}.
 * <p>
 * Before the {@code Configuration}-aware overload existed, {@link ExampleBuilder} read the static
 * {@link ConfigurationProperties} store unconditionally, so an instance-only value silently did
 * nothing. Every test here leaves the static store at {@code false} and asserts the generated value
 * differs from the static sample, so reverting to a static read fails the test.
 */
public class ExampleBuilderConfigurationInstanceTest {

    @Before
    public void setUp() {
        ConfigurationProperties.generateRealisticExampleValues(false);
    }

    @After
    public void tearDown() {
        ConfigurationProperties.generateRealisticExampleValues(false);
    }

    @Test
    public void shouldHonourRealisticValuesSetOnConfigurationInstanceRatherThanStaticStore() {
        Configuration configuration = configuration().generateRealisticExampleValues(true);

        Example result = fromSchema(new EmailSchema(), new HashMap<>(), null, ExampleBuilder.Direction.UNSPECIFIED, configuration);

        // the static store is still false, so a static read would have produced the fixed sample
        assertThat(ConfigurationProperties.generateRealisticExampleValues(), is(false));
        assertThat(((StringExample) result).getValue(), is(not(SAMPLE_EMAIL_PROPERTY_VALUE)));
    }

    @Test
    public void shouldFallBackToStaticStoreWhenConfigurationIsNull() {
        Example result = fromSchema(new EmailSchema(), new HashMap<>(), null, ExampleBuilder.Direction.UNSPECIFIED, null);

        assertThat(((StringExample) result).getValue(), is(SAMPLE_EMAIL_PROPERTY_VALUE));
    }

    @Test
    public void shouldFallBackToStaticStoreWhenConfigurationLeavesPropertyUnset() {
        ConfigurationProperties.generateRealisticExampleValues(true);

        // property unset on the instance — Configuration falls back to the static store
        Example result = fromSchema(new EmailSchema(), new HashMap<>(), null, ExampleBuilder.Direction.UNSPECIFIED, configuration());

        assertThat(((StringExample) result).getValue(), is(not(SAMPLE_EMAIL_PROPERTY_VALUE)));
    }

    @Test
    public void shouldLetExplicitGenerationOptionsBeatConfigurationInstance() {
        Configuration configuration = configuration().generateRealisticExampleValues(true);
        GenerationOptions generationOptions = new GenerationOptions(null, null, false);

        Example result = fromSchema(new EmailSchema(), new HashMap<>(), generationOptions, ExampleBuilder.Direction.UNSPECIFIED, configuration);

        assertThat(((StringExample) result).getValue(), is(SAMPLE_EMAIL_PROPERTY_VALUE));
    }

    @Test
    public void shouldHonourConfigurationInstanceForParameterExamples() {
        Configuration configuration = configuration().generateRealisticExampleValues(true);
        io.swagger.v3.oas.models.parameters.Parameter parameter = new io.swagger.v3.oas.models.parameters.Parameter()
            .name("contact")
            .in("query")
            .schema(new EmailSchema());

        String value = OpenApiParameterExamples.getParameterExampleValue(parameter, null, null, configuration);

        assertThat(ConfigurationProperties.generateRealisticExampleValues(), is(false));
        assertThat(value, is(not(SAMPLE_EMAIL_PROPERTY_VALUE)));
    }
}
