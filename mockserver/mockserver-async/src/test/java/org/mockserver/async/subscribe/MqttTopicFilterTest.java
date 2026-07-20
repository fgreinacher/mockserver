package org.mockserver.async.subscribe;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link MqttTopicFilter} against the worked examples in MQTT 3.1.1 §4.7.
 */
public class MqttTopicFilterTest {

    // ---- exact matches ----

    @Test
    public void shouldMatchIdenticalTopic() {
        assertMatches("sport/tennis/player1", "sport/tennis/player1");
        assertDoesNotMatch("sport/tennis/player1", "sport/tennis/player2");
    }

    // ---- multi-level wildcard '#' (§4.7.1.2) ----

    @Test
    public void multiLevelWildcardShouldMatchDescendants() {
        assertMatches("sport/tennis/player1/#", "sport/tennis/player1");
        assertMatches("sport/tennis/player1/#", "sport/tennis/player1/ranking");
        assertMatches("sport/tennis/player1/#", "sport/tennis/player1/score/wimbledon");
    }

    @Test
    public void multiLevelWildcardShouldIncludeTheParentLevel() {
        // "sport/#" also matches the singular "sport", since # includes the parent level
        assertMatches("sport/#", "sport");
        assertMatches("sport/#", "sport/tennis");
    }

    @Test
    public void loneMultiLevelWildcardShouldMatchEveryTopic() {
        assertMatches("#", "sport");
        assertMatches("#", "sport/tennis/player1");
        assertMatches("#", "a");
    }

    @Test
    public void multiLevelWildcardShouldNotMatchASiblingPrefix() {
        assertDoesNotMatch("sport/tennis/#", "sport/tennisball");
        assertDoesNotMatch("sport/#", "sportsdirect");
    }

    // ---- single-level wildcard '+' (§4.7.1.3) ----

    @Test
    public void singleLevelWildcardShouldMatchExactlyOneLevel() {
        assertMatches("sport/tennis/+", "sport/tennis/player1");
        assertMatches("sport/tennis/+", "sport/tennis/player2");
        assertDoesNotMatch("sport/tennis/+", "sport/tennis/player1/ranking");
    }

    @Test
    public void singleLevelWildcardShouldNotMatchTheParentLevel() {
        // "sport/+" does not match "sport" but it does match "sport/"
        assertDoesNotMatch("sport/+", "sport");
        assertMatches("sport/+", "sport/");
    }

    @Test
    public void loneSingleLevelWildcardShouldMatchOnlyTopLevelTopics() {
        assertMatches("+", "sport");
        assertDoesNotMatch("+", "sport/tennis");
    }

    @Test
    public void singleLevelWildcardShouldMatchAtAnyPosition() {
        assertMatches("+/tennis/#", "sport/tennis/player1");
        assertMatches("sport/+/player1", "sport/tennis/player1");
    }

    // ---- reserved '$' topics (§4.7.2) ----

    @Test
    public void wildcardShouldNotMatchReservedDollarTopics() {
        assertDoesNotMatch("#", "$SYS/broker/uptime");
        assertDoesNotMatch("+/monitor/clients", "$SYS/monitor/clients");
    }

    @Test
    public void explicitDollarPrefixShouldMatchReservedTopics() {
        assertMatches("$SYS/#", "$SYS/broker/uptime");
        assertMatches("$SYS/monitor/+", "$SYS/monitor/clients");
        assertMatches("$SYS/broker/uptime", "$SYS/broker/uptime");
    }

    // ---- malformed filters ----

    @Test
    public void malformedFiltersShouldNotMatch() {
        // '#' must occupy an entire level and be last
        assertDoesNotMatch("sport/tennis#", "sport/tennis/player1");
        assertDoesNotMatch("sport/#/ranking", "sport/tennis/ranking");
        // '+' must occupy an entire level
        assertDoesNotMatch("sport+", "sport/tennis");
    }

    @Test
    public void shouldReportFilterValidity() {
        assertThat(MqttTopicFilter.isValidFilter("sport/+/player1"), is(true));
        assertThat(MqttTopicFilter.isValidFilter("sport/#"), is(true));
        assertThat(MqttTopicFilter.isValidFilter("#"), is(true));
        assertThat(MqttTopicFilter.isValidFilter("sport/tennis#"), is(false));
        assertThat(MqttTopicFilter.isValidFilter("sport/#/ranking"), is(false));
        assertThat(MqttTopicFilter.isValidFilter("sport+"), is(false));
        assertThat(MqttTopicFilter.isValidFilter(""), is(false));
        assertThat(MqttTopicFilter.isValidFilter(null), is(false));
    }

    @Test
    public void shouldDetectWildcardPresence() {
        assertThat(MqttTopicFilter.containsWildcard("sport/tennis"), is(false));
        assertThat(MqttTopicFilter.containsWildcard("sport/+"), is(true));
        assertThat(MqttTopicFilter.containsWildcard("sport/#"), is(true));
        assertThat(MqttTopicFilter.containsWildcard(null), is(false));
    }

    @Test
    public void shouldHandleNullsWithoutMatching() {
        assertThat(MqttTopicFilter.matches(null, "sport"), is(false));
        assertThat(MqttTopicFilter.matches("sport", null), is(false));
    }

    // ---- helpers ----

    private static void assertMatches(String filter, String topic) {
        assertThat("filter '" + filter + "' should match topic '" + topic + "'",
            MqttTopicFilter.matches(filter, topic), is(true));
    }

    private static void assertDoesNotMatch(String filter, String topic) {
        assertThat("filter '" + filter + "' should NOT match topic '" + topic + "'",
            MqttTopicFilter.matches(filter, topic), is(false));
    }
}
