package org.mockserver.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal SCIM 2.0 sort evaluator implementing the {@code sortBy} + {@code sortOrder}
 * query parameters (RFC 7644 §3.4.2.3).
 *
 * <ul>
 *   <li>{@code sortBy} is a (possibly nested, dot-separated) attribute path, e.g.
 *       {@code userName} or {@code name.familyName}. Comparison is case-insensitive
 *       (SCIM string attributes default to {@code caseExact:false}).</li>
 *   <li>{@code sortOrder} is {@code ascending} (default) or {@code descending}.</li>
 *   <li>Resources whose sort attribute has no value are always ordered <strong>last</strong>,
 *       regardless of {@code sortOrder}.</li>
 *   <li>The sort is stable, so resources comparing equal keep their prior (filtered,
 *       insertion) order.</li>
 * </ul>
 *
 * <p>A syntactically invalid {@code sortBy} path, or a {@code sortOrder} other than
 * {@code ascending}/{@code descending}, is a loud {@link IllegalArgumentException}
 * (surfaced by the callback as a {@code 400} {@code invalidValue} SCIM error) — mirroring
 * how {@link ScimFilter} rejects a malformed filter.
 */
public class ScimSort {

    private static final Pattern ATTRIBUTE_PATH =
        Pattern.compile("^[A-Za-z][\\w$-]*(?:\\.[A-Za-z][\\w$-]*)*$");

    private final String sortBy;
    private final boolean descending;

    private ScimSort(String sortBy, boolean descending) {
        this.sortBy = sortBy;
        this.descending = descending;
    }

    /**
     * Parses the {@code sortBy}/{@code sortOrder} query parameters.
     *
     * @return the parsed sort, or {@code null} if {@code sortBy} is blank (no sorting requested)
     * @throws IllegalArgumentException if {@code sortBy} is a malformed attribute path, or
     *                                  {@code sortOrder} is present but not ascending/descending
     */
    public static ScimSort parse(String sortBy, String sortOrder) {
        if (sortBy == null || sortBy.trim().isEmpty()) {
            return null;
        }
        String attribute = sortBy.trim();
        if (!ATTRIBUTE_PATH.matcher(attribute).matches()) {
            throw new IllegalArgumentException("invalid sortBy attribute path: " + sortBy);
        }
        boolean descending = false;
        if (sortOrder != null && !sortOrder.trim().isEmpty()) {
            String order = sortOrder.trim();
            if (order.equalsIgnoreCase("descending")) {
                descending = true;
            } else if (order.equalsIgnoreCase("ascending")) {
                descending = false;
            } else {
                throw new IllegalArgumentException(
                    "sortOrder must be 'ascending' or 'descending' but was: " + sortOrder);
            }
        }
        return new ScimSort(attribute, descending);
    }

    /**
     * Returns a new list of the supplied resources ordered by this sort. The input list is
     * not mutated.
     */
    public List<ObjectNode> apply(List<ObjectNode> resources) {
        List<ObjectNode> sorted = new ArrayList<>(resources);
        String[] path = sortBy.split("\\.");
        Comparator<ObjectNode> comparator = (a, b) -> {
            String va = valueAt(a, path);
            String vb = valueAt(b, path);
            boolean missingA = va == null;
            boolean missingB = vb == null;
            if (missingA && missingB) {
                return 0;
            }
            if (missingA) {
                return 1; // missing values always sort last
            }
            if (missingB) {
                return -1;
            }
            int comparison = va.compareToIgnoreCase(vb);
            return descending ? -comparison : comparison;
        };
        sorted.sort(comparator);
        return sorted;
    }

    private static String valueAt(ObjectNode resource, String[] path) {
        JsonNode node = resource;
        for (String segment : path) {
            if (node == null || !node.isObject()) {
                return null;
            }
            node = node.get(segment);
        }
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        return node.asText();
    }

    public String getSortBy() {
        return sortBy;
    }

    public boolean isDescending() {
        return descending;
    }
}
