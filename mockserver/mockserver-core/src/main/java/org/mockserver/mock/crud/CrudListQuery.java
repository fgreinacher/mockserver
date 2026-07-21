package org.mockserver.mock.crud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.model.HttpRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses and applies the generic CRUD list query parameters — field filtering,
 * sorting, and page/size pagination — to a {@link CrudActionHandler#handleList}
 * result. All parameters are optional; when none are supplied the list is returned
 * unchanged (legacy behaviour, byte-for-byte identical response body).
 *
 * <p><b>Query parameters</b> (all optional):
 * <ul>
 *   <li>{@code filterField} + {@code filterValue} — keep only items whose
 *       {@code filterField} (a dot-separated attribute path, e.g. {@code name} or
 *       {@code address.city}) has a scalar value equal to {@code filterValue}
 *       (case-insensitive). Both must be supplied together; supplying only one is a
 *       400. Items missing the field, or whose field is a non-scalar, are excluded.</li>
 *   <li>{@code sortBy} + {@code sortOrder} — sort by a dot-separated attribute path,
 *       case-insensitive. {@code sortOrder} is {@code ascending}/{@code asc} (default)
 *       or {@code descending}/{@code desc}; anything else is a 400, as is a
 *       {@code sortOrder} without a {@code sortBy}. Items whose sort value is missing
 *       sort <em>last</em> regardless of order. The sort is stable.</li>
 *   <li>{@code page} + {@code size} — 0-based {@code page} index (default 0) and page
 *       {@code size} (default: all items). A {@code size} &le; 0 means "no limit"
 *       (all items from {@code page*size}); a non-integer {@code page}/{@code size},
 *       or a negative {@code page}, is a 400.</li>
 * </ul>
 *
 * <p>Application order is <b>filter → sort → paginate</b>, mirroring the SCIM list
 * callback. This is the generic CRUD store's own query surface and is intentionally
 * independent of the SCIM sort/filter implementation.
 */
class CrudListQuery {

    private final String filterField;
    private final String filterValue;
    private final String sortBy;
    private final boolean descending;
    private final int page;
    private final Integer size;

    private CrudListQuery(String filterField, String filterValue, String sortBy, boolean descending, int page, Integer size) {
        this.filterField = filterField;
        this.filterValue = filterValue;
        this.sortBy = sortBy;
        this.descending = descending;
        this.page = page;
        this.size = size;
    }

    /**
     * Parses the list query parameters off the request.
     *
     * @throws IllegalArgumentException if a parameter is malformed (surfaced by the
     *                                  handler as a 400)
     */
    static CrudListQuery parse(HttpRequest request) {
        String filterField = trimToNull(request.getFirstQueryStringParameter("filterField"));
        String filterValue = trimToNull(request.getFirstQueryStringParameter("filterValue"));
        if ((filterField == null) != (filterValue == null)) {
            throw new IllegalArgumentException("'filterField' and 'filterValue' must be supplied together");
        }

        String sortBy = trimToNull(request.getFirstQueryStringParameter("sortBy"));
        String sortOrder = trimToNull(request.getFirstQueryStringParameter("sortOrder"));
        boolean descending = false;
        if (sortOrder != null) {
            if (sortOrder.equalsIgnoreCase("descending") || sortOrder.equalsIgnoreCase("desc")) {
                descending = true;
            } else if (sortOrder.equalsIgnoreCase("ascending") || sortOrder.equalsIgnoreCase("asc")) {
                descending = false;
            } else {
                throw new IllegalArgumentException("'sortOrder' must be 'ascending'/'asc' or 'descending'/'desc' but was: " + sortOrder);
            }
        }
        if (sortOrder != null && sortBy == null) {
            throw new IllegalArgumentException("'sortOrder' requires 'sortBy'");
        }

        int page = parseInt(request.getFirstQueryStringParameter("page"), "page", 0);
        if (page < 0) {
            throw new IllegalArgumentException("'page' must not be negative but was: " + page);
        }
        Integer size = null;
        String sizeParam = trimToNull(request.getFirstQueryStringParameter("size"));
        if (sizeParam != null) {
            int parsed = parseInt(sizeParam, "size", 0);
            size = parsed > 0 ? parsed : null;
        }

        return new CrudListQuery(filterField, filterValue, sortBy, descending, page, size);
    }

    /**
     * Applies the filter then the sort, returning a new list (the input is not mutated).
     * The result is the full matching set <em>before</em> pagination — its size is the
     * total count reported by the handler.
     */
    List<ObjectNode> filterAndSort(List<ObjectNode> items) {
        List<ObjectNode> result = new ArrayList<>(items);
        if (filterField != null) {
            result.removeIf(item -> {
                String value = valueAt(item, filterField);
                return value == null || !value.equalsIgnoreCase(filterValue);
            });
        }
        if (sortBy != null) {
            Comparator<ObjectNode> comparator = (a, b) -> {
                String va = valueAt(a, sortBy);
                String vb = valueAt(b, sortBy);
                if (va == null && vb == null) {
                    return 0;
                }
                if (va == null) {
                    return 1; // missing values always sort last
                }
                if (vb == null) {
                    return -1;
                }
                int comparison = va.compareToIgnoreCase(vb);
                return descending ? -comparison : comparison;
            };
            result.sort(comparator);
        }
        return result;
    }

    /**
     * Returns the {@code page}-th slice of {@code size} items from the already
     * filtered+sorted list. When no {@code size} is configured the full list is
     * returned (offset by {@code page*0 == 0}, i.e. unchanged).
     */
    List<ObjectNode> paginate(List<ObjectNode> filteredAndSorted) {
        if (size == null) {
            return filteredAndSorted;
        }
        // long math throughout so a large page/size cannot overflow int and throw from subList
        long offset = (long) page * size;
        int from = (int) Math.min(offset, filteredAndSorted.size());
        int to = (int) Math.min(offset + size, filteredAndSorted.size());
        return new ArrayList<>(filteredAndSorted.subList(from, to));
    }

    /** Whether any list query parameter was supplied (so the handler can add paging headers only when relevant). */
    boolean isActive() {
        return filterField != null || sortBy != null || size != null || page > 0;
    }

    int getPage() {
        return page;
    }

    Integer getSize() {
        return size;
    }

    private static String valueAt(ObjectNode item, String path) {
        JsonNode node = item;
        for (String segment : path.split("\\.")) {
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

    private static int parseInt(String raw, String name, int defaultValue) {
        String value = trimToNull(raw);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + name + "' must be an integer but was: " + value);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
