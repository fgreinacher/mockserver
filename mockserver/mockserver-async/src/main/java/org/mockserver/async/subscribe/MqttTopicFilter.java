package org.mockserver.async.subscribe;

/**
 * Matching of concrete MQTT topic names against MQTT topic <i>filters</i>, per
 * MQTT 3.1.1 §4.7 (the same wildcard semantics apply to MQTT 5).
 *
 * <p>A subscription may use wildcards, but a message always arrives on a concrete
 * topic. Recorded messages are therefore stored under the concrete topic, while
 * verification asks for the filter the user subscribed with — so the two must be
 * reconciled by filter matching rather than by string equality.
 *
 * <h2>Wildcards</h2>
 * <ul>
 *   <li><b>{@code +}</b> — single-level wildcard; matches exactly one topic level
 *       and must occupy an entire level ({@code sport/+/score}).</li>
 *   <li><b>{@code #}</b> — multi-level wildcard; must be the last level of the
 *       filter and matches that level and all levels beneath it. Because {@code #}
 *       includes the parent level, {@code sport/#} matches {@code sport} as well as
 *       {@code sport/tennis/player1}.</li>
 * </ul>
 *
 * <h2>Reserved topics</h2>
 * Per §4.7.2 a leading wildcard does not match a topic beginning with {@code $}
 * (e.g. {@code $SYS/...}), so {@code #} does not match {@code $SYS/broker/uptime}
 * while {@code $SYS/#} does.
 */
public final class MqttTopicFilter {

    private static final String LEVEL_SEPARATOR = "/";
    private static final String SINGLE_LEVEL_WILDCARD = "+";
    private static final String MULTI_LEVEL_WILDCARD = "#";

    private MqttTopicFilter() {
        // utility class
    }

    /**
     * @return true if the given topic filter contains an MQTT wildcard character,
     *         and so cannot be compared to a concrete topic by equality alone.
     */
    public static boolean containsWildcard(String filter) {
        return filter != null
            && (filter.indexOf('+') >= 0 || filter.indexOf('#') >= 0);
    }

    /**
     * @return true if the filter is well formed: {@code +} occupies a whole level and
     *         {@code #} occupies a whole level and appears only as the final level.
     */
    public static boolean isValidFilter(String filter) {
        if (filter == null || filter.isEmpty()) {
            return false;
        }
        String[] levels = filter.split(LEVEL_SEPARATOR, -1);
        for (int i = 0; i < levels.length; i++) {
            String level = levels[i];
            if (level.indexOf('#') >= 0
                && (!level.equals(MULTI_LEVEL_WILDCARD) || i != levels.length - 1)) {
                // '#' must be its own level and the last one
                return false;
            }
            if (level.indexOf('+') >= 0 && !level.equals(SINGLE_LEVEL_WILDCARD)) {
                // '+' must occupy an entire level
                return false;
            }
        }
        return true;
    }

    /**
     * Match a concrete topic name against a topic filter.
     *
     * @param filter the (possibly wildcarded) topic filter subscribed with
     * @param topic  the concrete topic a message arrived on
     * @return true if the filter selects the topic
     */
    public static boolean matches(String filter, String topic) {
        if (filter == null || topic == null) {
            return false;
        }
        if (filter.equals(topic)) {
            // exact match — also the only way to match a reserved '$' topic without wildcards
            return true;
        }
        if (!containsWildcard(filter) || !isValidFilter(filter)) {
            return false;
        }

        String[] filterLevels = filter.split(LEVEL_SEPARATOR, -1);
        String[] topicLevels = topic.split(LEVEL_SEPARATOR, -1);

        // §4.7.2 — a wildcard at the first level does not match a reserved '$' topic
        if (topicLevels.length > 0 && topicLevels[0].startsWith("$")
            && (filterLevels[0].equals(SINGLE_LEVEL_WILDCARD)
                || filterLevels[0].equals(MULTI_LEVEL_WILDCARD))) {
            return false;
        }

        int i = 0;
        for (; i < filterLevels.length; i++) {
            String filterLevel = filterLevels[i];
            if (filterLevel.equals(MULTI_LEVEL_WILDCARD)) {
                // '#' is always the last level and matches this level plus any beneath it,
                // including zero levels ("sport/#" matches "sport")
                return true;
            }
            if (i >= topicLevels.length) {
                // filter is longer than the topic ("sport/+" does not match "sport")
                return false;
            }
            if (filterLevel.equals(SINGLE_LEVEL_WILDCARD)) {
                continue;
            }
            if (!filterLevel.equals(topicLevels[i])) {
                return false;
            }
        }
        return i == topicLevels.length;
    }
}
