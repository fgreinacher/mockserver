package org.mockserver.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.NottableString.not;
import static org.mockserver.model.NottableString.string;

/**
 * Characterises JSON round-tripping of {@link Headers} and {@link Parameters} through the registered
 * Jackson serializers/deserializers: the deserialised collection is {@code equals} to the original and
 * preserves per-key value order. Cross-key ORDER is not preserved (the serializer sorts keys descending),
 * which is pinned here as current reality.
 *
 * @author jamesdbloom
 */
public class KeysToMultiValuesSerializationRoundTripTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    private static List<String> keySetValues(KeysToMultiValues<?, ?> collection) {
        List<String> keys = new ArrayList<>();
        for (NottableString key : collection.keySet()) {
            keys.add(key.getValue());
        }
        return keys;
    }

    @Test
    public void headersRoundTripPreservesEqualityAndPerKeyValueOrder() throws Exception {
        Headers original = new Headers();
        original.withEntry("Host", "h");
        original.withEntry("Set-Cookie", "c1");
        original.withEntry("Accept", "a");
        original.withEntry("Set-Cookie", "c2");

        String json = objectMapper.writeValueAsString(original);
        Headers restored = objectMapper.readValue(json, Headers.class);

        assertThat(restored, is(original));
        assertThat(restored.getValues("Set-Cookie"), is(Arrays.asList("c1", "c2")));
    }

    @Test
    public void headersRoundTripReordersKeysToSortedDescending() throws Exception {
        Headers original = new Headers();
        original.withEntry("Host", "h");
        original.withEntry("Set-Cookie", "c1");
        original.withEntry("Accept", "a");

        Headers restored = objectMapper.readValue(objectMapper.writeValueAsString(original), Headers.class);
        assertThat(keySetValues(restored), is(Arrays.asList("Set-Cookie", "Host", "Accept")));
    }

    @Test
    public void parametersRoundTripPreservesEqualityAndValueOrder() throws Exception {
        Parameters original = new Parameters();
        original.withEntry("one", "1a", "1b");
        original.withEntry("two", "2a");

        Parameters restored = objectMapper.readValue(objectMapper.writeValueAsString(original), Parameters.class);
        assertThat(restored, is(original));
        assertThat(restored.getValues("one"), is(Arrays.asList("1a", "1b")));
    }

    @Test
    public void headersWithNotKeyRoundTripPreservesEquality() throws Exception {
        Headers original = new Headers();
        original.withEntry(not("secret"), string("value"));

        Headers restored = objectMapper.readValue(objectMapper.writeValueAsString(original), Headers.class);
        assertThat(restored, is(original));
        List<NottableString> keys = new ArrayList<>(restored.keySet());
        assertThat(keys.get(0).getValue(), is("secret"));
        assertThat(keys.get(0).isNot(), is(true));
    }

    @Test
    public void headersWithNotValueRoundTripPreservesEquality() throws Exception {
        Headers original = new Headers();
        original.withEntry(string("name"), not("value"));

        Headers restored = objectMapper.readValue(objectMapper.writeValueAsString(original), Headers.class);
        assertThat(restored, is(original));
        List<NottableString> values = new ArrayList<>(restored.getValues(string("name")));
        assertThat(values.get(0).isNot(), is(true));
        assertThat(values.get(0).getValue(), is("value"));
    }

    @Test
    public void headersWithNameThatBeginsWithNotMarkerRoundTripsViaArrayForm() throws Exception {
        // A literal header name beginning with '!' must survive round-tripping without inverting the
        // match; the serializer falls back to the [{name,values}] array form for such names.
        Headers original = new Headers();
        original.withEntry(string("!literal", false), string("v", false));

        String json = objectMapper.writeValueAsString(original);
        assertThat(json.trim().charAt(0), is('['));
        Headers restored = objectMapper.readValue(json, Headers.class);
        assertThat(restored, is(original));
    }

    @Test
    public void emptyHeadersRoundTripToEmpty() throws Exception {
        Headers original = new Headers();
        Headers restored = objectMapper.readValue(objectMapper.writeValueAsString(original), Headers.class);
        assertThat(restored.isEmpty(), is(true));
        assertThat(restored, is(original));
    }
}
