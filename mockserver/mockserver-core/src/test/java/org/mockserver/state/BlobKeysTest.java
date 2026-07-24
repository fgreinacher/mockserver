package org.mockserver.state;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Unit coverage for the object-store key conventions shared by the S3, GCS and Azure
 * blob stores.
 * <p>
 * These are not cosmetic string rules: a key with a leading {@code /} or a doubled
 * {@code //} is rejected outright by MinIO (HTTP 400, "Object name contains unsupported
 * characters"), so a {@code blobStoreKeyPrefix} ending in a separator combined with a
 * key beginning with one made every cloud write fail.
 * <p>
 * Uses only local values -- no JVM-global singleton is touched, so this class stays in
 * the parallel Surefire phase.
 */
public class BlobKeysTest {

    // ---------------------------------------------------------------- join: prefix shapes

    @Test
    public void shouldReturnTheKeyUnchangedWhenNoPrefixIsConfigured() {
        assertThat(BlobKeys.join("", "persistedExpectations.json"), is("persistedExpectations.json"));
        assertThat(BlobKeys.join(null, "persistedExpectations.json"), is("persistedExpectations.json"));
    }

    @Test
    public void shouldInsertTheSeparatorWhenThePrefixHasNoTrailingSlash() {
        assertThat(BlobKeys.join("mockserver", "persistedExpectations.json"), is("mockserver/persistedExpectations.json"));
    }

    @Test
    public void shouldNotDoubleTheSeparatorWhenThePrefixEndsInASlash() {
        assertThat(BlobKeys.join("mockserver/", "persistedExpectations.json"), is("mockserver/persistedExpectations.json"));
    }

    @Test
    public void shouldDropALeadingSlashOnThePrefix() {
        // S3 object names never start with a separator; "/mockserver/x" and "mockserver/x"
        // are different objects on AWS and the former is rejected by MinIO
        assertThat(BlobKeys.join("/mockserver/", "persistedExpectations.json"), is("mockserver/persistedExpectations.json"));
        assertThat(BlobKeys.join("/mockserver", "persistedExpectations.json"), is("mockserver/persistedExpectations.json"));
    }

    @Test
    public void shouldCollapseRepeatedSeparatorsInsideThePrefix() {
        assertThat(BlobKeys.join("mockserver//nested///", "persistedExpectations.json"), is("mockserver/nested/persistedExpectations.json"));
    }

    @Test
    public void shouldKeepAMultiSegmentPrefixIntact() {
        assertThat(BlobKeys.join("team/mockserver/ci", "persistedExpectations.json"), is("team/mockserver/ci/persistedExpectations.json"));
    }

    // ---------------------------------------------------------------- join: key shapes

    @Test
    public void shouldDropALeadingSlashOnTheKey() {
        // THE BUG: the persistence layer used to pass an ABSOLUTE local path as the key, so
        // with any prefix the composed name gained a "//" and with no prefix it gained a
        // leading "/" -- both invalid object names
        assertThat(BlobKeys.join("mockserver/", "/var/folders/tmp/persistedExpectations.json"),
            is("mockserver/var/folders/tmp/persistedExpectations.json"));
        assertThat(BlobKeys.join("", "/var/folders/tmp/persistedExpectations.json"),
            is("var/folders/tmp/persistedExpectations.json"));
    }

    @Test
    public void shouldNeverProduceALeadingOrDoubledSeparatorForAnyPrefixShape() {
        String[] prefixes = {"", null, "mockserver", "mockserver/", "/mockserver", "/mockserver/", "//mockserver//"};
        String[] keys = {"persistedExpectations.json", "/persistedExpectations.json", "/var/tmp/persistedExpectations.json", "a//b"};
        for (String prefix : prefixes) {
            for (String key : keys) {
                String joined = BlobKeys.join(prefix, key);
                assertThat("prefix=" + prefix + " key=" + key, joined, not(startsWith("/")));
                assertThat("prefix=" + prefix + " key=" + key, joined, not(containsString("//")));
            }
        }
    }

    @Test
    public void shouldScopeAnEmptyKeyToThePrefixFolder() {
        // list("") must scope the listing to the prefix, not to every key that happens to
        // start with the same characters
        assertThat(BlobKeys.join("mockserver", ""), is("mockserver/"));
        assertThat(BlobKeys.join("mockserver/", null), is("mockserver/"));
        assertThat(BlobKeys.join("", ""), is(""));
    }

    @Test
    public void shouldPreserveATrailingSeparatorOnAListingPrefix() {
        assertThat(BlobKeys.join("mockserver", "expectations/"), is("mockserver/expectations/"));
    }

    // ---------------------------------------------------------------- stripPrefix

    @Test
    public void shouldStripThePrefixWhateverItsShape() {
        assertThat(BlobKeys.stripPrefix("mockserver/", "mockserver/persistedExpectations.json"), is("persistedExpectations.json"));
        assertThat(BlobKeys.stripPrefix("mockserver", "mockserver/persistedExpectations.json"), is("persistedExpectations.json"));
        assertThat(BlobKeys.stripPrefix("/mockserver/", "mockserver/persistedExpectations.json"), is("persistedExpectations.json"));
        assertThat(BlobKeys.stripPrefix("", "persistedExpectations.json"), is("persistedExpectations.json"));
        assertThat(BlobKeys.stripPrefix(null, "persistedExpectations.json"), is("persistedExpectations.json"));
    }

    @Test
    public void shouldRoundTripEveryJoinedKeyBackToTheOriginalKey() {
        String[] prefixes = {"", "mockserver", "mockserver/", "/mockserver/", "team/ci/"};
        for (String prefix : prefixes) {
            String joined = BlobKeys.join(prefix, "expectations/persistedExpectations.json");
            assertThat("prefix=" + prefix, BlobKeys.stripPrefix(prefix, joined), is("expectations/persistedExpectations.json"));
        }
    }

    @Test
    public void shouldLeaveAKeyThatDoesNotCarryThePrefixUnchanged() {
        assertThat(BlobKeys.stripPrefix("mockserver/", "somethingelse/persistedExpectations.json"),
            is("somethingelse/persistedExpectations.json"));
    }

    // ---------------------------------------------------------------- forPersistedFile

    @Test
    public void shouldUseTheFileNameAloneAsTheKeyForACloudStore() {
        Path absolute = Paths.get(File.separator + "var" + File.separator + "mockserver" + File.separator + "persistedExpectations.json");

        assertThat(BlobKeys.forPersistedFile(new InMemoryBlobStore(), absolute), is("persistedExpectations.json"));
    }

    @Test
    public void shouldUseTheFileNameAloneEvenForARelativePersistencePath() {
        // the DEFAULT persistedExpectationsPath is relative, so the key used to vary with
        // whichever directory MockServer happened to be started from
        Path relative = Paths.get("target", "persistedExpectations.json");

        assertThat(BlobKeys.forPersistedFile(new InMemoryBlobStore(), relative), is("persistedExpectations.json"));
    }

    @Test
    public void shouldKeepTheAbsolutePathAsTheKeyForTheFilesystemStore() {
        // the filesystem store INTERPRETS the key as a file path, so it must keep the
        // absolute path or persistence would start writing somewhere else
        Path relative = Paths.get("target", "persistedExpectations.json");

        assertThat(BlobKeys.forPersistedFile(new FilesystemBlobStore(null), relative),
            is(relative.toAbsolutePath().toString()));
    }
}
