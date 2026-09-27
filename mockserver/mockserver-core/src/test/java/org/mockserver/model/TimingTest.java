package org.mockserver.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.lang.reflect.Field;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/**
 * Guards the primitive-{@code long}+sentinel storage in {@link Timing}: the public {@code Long}
 * signatures, JSON output (unset omitted, zero preserved), and equals/hashCode must be observably
 * identical to the previous boxed-{@code Long} storage, and no epoch field may be retained as a box.
 */
public class TimingTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldReturnNullForUnsetFieldsAndOmitThemFromJson() throws Exception {
        Timing timing = Timing.timing();

        assertNull(timing.getRequestStartedMillis());
        assertNull(timing.getConnectionEstablishedMillis());
        assertNull(timing.getResponseReceivedMillis());
        assertNull(timing.getConnectionTimeInMillis());
        assertNull(timing.getTimeToFirstByteInMillis());
        assertNull(timing.getTotalTimeInMillis());
        assertNull(timing.getInjectedChaosLatencyMillis());
        assertNull(timing.getInjectedDelayMillis());
        assertNull(timing.getBreakpointHeldMillis());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(timing));
        assertThat("unset fields must be omitted from JSON", json.size(), is(0));
    }

    @Test
    public void shouldPreserveSetValuesThroughGettersAndJson() throws Exception {
        Timing timing = Timing.timing()
            .withRequestStartedMillis(1_000L)
            .withConnectionEstablishedMillis(1_010L)
            .withResponseReceivedMillis(1_050L)
            .withConnectionTimeInMillis(10L)
            .withTimeToFirstByteInMillis(30L)
            .withTotalTimeInMillis(50L)
            .withInjectedChaosLatencyMillis(5L)
            .withInjectedDelayMillis(6L)
            .withBreakpointHeldMillis(7L);

        assertEquals(Long.valueOf(1_000L), timing.getRequestStartedMillis());
        assertEquals(Long.valueOf(1_010L), timing.getConnectionEstablishedMillis());
        assertEquals(Long.valueOf(1_050L), timing.getResponseReceivedMillis());
        assertEquals(Long.valueOf(10L), timing.getConnectionTimeInMillis());
        assertEquals(Long.valueOf(30L), timing.getTimeToFirstByteInMillis());
        assertEquals(Long.valueOf(50L), timing.getTotalTimeInMillis());
        assertEquals(Long.valueOf(5L), timing.getInjectedChaosLatencyMillis());
        assertEquals(Long.valueOf(6L), timing.getInjectedDelayMillis());
        assertEquals(Long.valueOf(7L), timing.getBreakpointHeldMillis());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(timing));
        assertThat(json.size(), is(9));
        assertThat(json.get("requestStartedMillis").asLong(), is(1_000L));
        assertThat(json.get("connectionEstablishedMillis").asLong(), is(1_010L));
        assertThat(json.get("responseReceivedMillis").asLong(), is(1_050L));
        assertThat(json.get("connectionTimeInMillis").asLong(), is(10L));
        assertThat(json.get("timeToFirstByteInMillis").asLong(), is(30L));
        assertThat(json.get("totalTimeInMillis").asLong(), is(50L));
        assertThat(json.get("injectedChaosLatencyMillis").asLong(), is(5L));
        assertThat(json.get("injectedDelayMillis").asLong(), is(6L));
        assertThat(json.get("breakpointHeldMillis").asLong(), is(7L));
    }

    @Test
    public void shouldTreatZeroAsSetAndDistinctFromUnset() throws Exception {
        Timing zero = Timing.timing().withTotalTimeInMillis(0L);

        assertEquals("zero is a real value, not unset", Long.valueOf(0L), zero.getTotalTimeInMillis());

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(zero));
        assertThat("a zero field must be emitted, not omitted", json.has("totalTimeInMillis"), is(true));
        assertThat(json.get("totalTimeInMillis").asLong(), is(0L));
        assertThat("only the explicitly-set field is present", json.size(), is(1));

        assertNotEquals("zero must not equal unset", zero, Timing.timing());
    }

    @Test
    public void shouldClearFieldBackToUnsetWhenSetToNull() {
        Timing timing = Timing.timing().withTotalTimeInMillis(50L);
        assertEquals(Long.valueOf(50L), timing.getTotalTimeInMillis());

        timing.withTotalTimeInMillis(null);
        assertNull("null must reset the field to unset", timing.getTotalTimeInMillis());
        assertEquals(Timing.timing(), timing);
    }

    @Test
    public void shouldHaveEqualsAndHashCodeUnchangedByStorageChange() {
        Timing a = Timing.timing().withRequestStartedMillis(100L).withTotalTimeInMillis(50L);
        Timing b = Timing.timing().withRequestStartedMillis(100L).withTotalTimeInMillis(50L);
        Timing different = Timing.timing().withRequestStartedMillis(100L).withTotalTimeInMillis(51L);
        Timing empty = Timing.timing();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, different);
        assertNotEquals(a, empty);

        assertEquals("two unset Timing instances are equal", empty, Timing.timing());
        assertEquals(empty.hashCode(), Timing.timing().hashCode());
    }

    @Test
    public void shouldStoreEpochFieldsAsPrimitiveLongSoNoBoxIsRetained() throws Exception {
        for (String fieldName : new String[]{
            "requestStartedMillis", "connectionEstablishedMillis", "responseReceivedMillis",
            "connectionTimeInMillis", "timeToFirstByteInMillis", "totalTimeInMillis",
            "injectedChaosLatencyMillis", "injectedDelayMillis", "breakpointHeldMillis"}) {
            Field field = Timing.class.getDeclaredField(fieldName);
            assertEquals(
                "Timing." + fieldName + " must be a primitive long so it is not retained as a boxed Long",
                long.class, field.getType());
        }
    }
}
