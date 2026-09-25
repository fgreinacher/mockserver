package org.mockserver.model;

import java.util.*;

import static org.mockserver.model.NottableString.*;

/**
 * @author jamesdbloom
 */
public class KeyToMultiValue extends ObjectWithJsonToString {
    private final NottableString name;
    private final List<NottableString> values;
    // Lazily cached hashCode using the same self-healing sentinel as Not/HttpRequest: 0 means "not yet
    // computed" and a genuinely-zero result is stored as 1. Because "computed?" and the value live in one
    // int field, a concurrent reader of a shared instance sees either 0 (and recomputes the same value) or
    // the finished value - never a torn pair - so no volatile is needed. Recomputed eagerly on mutation.
    private int hashCode;

    KeyToMultiValue(final String name, final String... values) {
        this(string(name), strings(values));
    }

    KeyToMultiValue(final NottableString name, final String... values) {
        this(name, strings(values));
    }

    @SuppressWarnings({"UseBulkOperation", "ManualArrayToCollectionCopy"})
    KeyToMultiValue(final NottableString name, final NottableString... values) {
        if (name == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        this.name = name;
        if (values == null || values.length == 0) {
            this.values = Collections.singletonList(string(".*"));
        } else if (values.length == 1) {
            this.values = Collections.singletonList(values[0]);
        } else {
            this.values = new LinkedList<>();
            for (NottableString value : values) {
                this.values.add(value);
            }
        }
    }

    KeyToMultiValue(final String name, final Collection<String> values) {
        this(string(name), strings(values));
    }

    KeyToMultiValue(final NottableString name, final Collection<NottableString> values) {
        this.name = name;
        if (values == null || values.isEmpty()) {
            this.values = Collections.singletonList(string(".*"));
        } else {
            this.values = new LinkedList<>(values);
        }
        recomputeHashCode();
    }

    private void recomputeHashCode() {
        int computed = Objects.hash(name, values);
        this.hashCode = computed != 0 ? computed : 1;
    }

    public NottableString getName() {
        return name;
    }

    public List<NottableString> getValues() {
        return values;
    }

    public void replaceValues(List<NottableString> values) {
        if (this.values != values) {
            this.values.clear();
            this.values.addAll(values);
            recomputeHashCode();
        }
    }

    public void addValue(final String value) {
        addValue(string(value));
    }

    private void addValue(final NottableString value) {
        if (values != null) {
            values.add(value);
        }
        recomputeHashCode();
    }

    private void addValues(final List<String> values) {
        addNottableValues(deserializeNottableStrings(values));
    }

    private void addNottableValues(final List<NottableString> values) {
        if (this.values != null) {
            this.values.addAll(values);
            recomputeHashCode();
        }
    }

    public void addValues(final String... values) {
        addValues(Arrays.asList(values));
    }

    public void addValues(final NottableString... values) {
        addNottableValues(Arrays.asList(values));
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        KeyToMultiValue that = (KeyToMultiValue) o;
        return Objects.equals(name, that.name) &&
            Objects.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            recomputeHashCode();
        }
        return hashCode;
    }
}
