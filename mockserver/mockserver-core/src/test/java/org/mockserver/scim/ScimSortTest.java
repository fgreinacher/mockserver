package org.mockserver.scim;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class ScimSortTest {

    private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.instance;

    private ObjectNode user(String userName, String familyName) {
        ObjectNode user = NODE_FACTORY.objectNode();
        user.put("userName", userName);
        if (familyName != null) {
            user.putObject("name").put("familyName", familyName);
        }
        return user;
    }

    private List<ObjectNode> users() {
        // deliberately out of order, with one entry missing a family name
        return new ArrayList<>(Arrays.asList(
            user("charlie", "Brown"),
            user("alice", "Anderson"),
            user("bob", null),
            user("dave", "adams")
        ));
    }

    @Test
    public void blankSortByReturnsNull() {
        assertNull(ScimSort.parse(null, "ascending"));
        assertNull(ScimSort.parse("   ", "descending"));
    }

    @Test
    public void ascendingIsTheDefaultOrder() {
        ScimSort sort = ScimSort.parse("userName", null);
        assertThat(sort.isDescending(), is(false));
        List<ObjectNode> sorted = sort.apply(users());
        assertThat(sorted, contains(
            user("alice", "Anderson"),
            user("bob", null),
            user("charlie", "Brown"),
            user("dave", "adams")
        ));
    }

    @Test
    public void descendingReversesOrder() {
        ScimSort sort = ScimSort.parse("userName", "DESCENDING");
        assertThat(sort.isDescending(), is(true));
        List<ObjectNode> sorted = sort.apply(users());
        assertThat(sorted, contains(
            user("dave", "adams"),
            user("charlie", "Brown"),
            user("bob", null),
            user("alice", "Anderson")
        ));
    }

    @Test
    public void comparisonIsCaseInsensitive() {
        // "adams" (lowercase) must sort next to "Anderson", not after all uppercase values
        ScimSort sort = ScimSort.parse("name.familyName", "ascending");
        List<ObjectNode> sorted = sort.apply(users());
        // present family names ordered case-insensitively: adams, Anderson, Brown; missing (bob) last
        assertThat(sorted, contains(
            user("dave", "adams"),
            user("alice", "Anderson"),
            user("charlie", "Brown"),
            user("bob", null)
        ));
    }

    @Test
    public void missingAttributeSortsLastEvenWhenDescending() {
        ScimSort sort = ScimSort.parse("name.familyName", "descending");
        List<ObjectNode> sorted = sort.apply(users());
        // descending over present values (Brown, Anderson, adams), then the missing one last
        assertThat(sorted, contains(
            user("charlie", "Brown"),
            user("alice", "Anderson"),
            user("dave", "adams"),
            user("bob", null)
        ));
    }

    @Test
    public void nestedAttributePathResolves() {
        ScimSort sort = ScimSort.parse("name.familyName", "ascending");
        assertThat(sort.getSortBy(), is("name.familyName"));
        List<ObjectNode> sorted = sort.apply(users());
        assertThat(sorted.get(0), is(user("dave", "adams")));
    }

    @Test
    public void sortIsStableForEqualKeys() {
        List<ObjectNode> equalKeys = new ArrayList<>(Arrays.asList(
            user("same", "Z"), user("same", "Y"), user("same", "X")));
        List<ObjectNode> sorted = ScimSort.parse("userName", "ascending").apply(equalKeys);
        // all userName equal -> original (insertion) order preserved
        assertThat(sorted, contains(equalKeys.get(0), equalKeys.get(1), equalKeys.get(2)));
    }

    @Test
    public void applyDoesNotMutateInput() {
        List<ObjectNode> input = users();
        List<ObjectNode> snapshot = new ArrayList<>(input);
        ScimSort.parse("userName", "descending").apply(input);
        assertThat(input, is(snapshot));
    }

    @Test
    public void invalidSortByPathThrows() {
        assertThrows(IllegalArgumentException.class, () -> ScimSort.parse("name..familyName", "ascending"));
        assertThrows(IllegalArgumentException.class, () -> ScimSort.parse("1name", "ascending"));
        assertThrows(IllegalArgumentException.class, () -> ScimSort.parse("user Name", "ascending"));
        assertThrows(IllegalArgumentException.class, () -> ScimSort.parse(".userName", "ascending"));
    }

    @Test
    public void invalidSortOrderThrows() {
        assertThrows(IllegalArgumentException.class, () -> ScimSort.parse("userName", "sideways"));
        assertThrows(IllegalArgumentException.class, () -> ScimSort.parse("userName", "asc"));
    }
}
