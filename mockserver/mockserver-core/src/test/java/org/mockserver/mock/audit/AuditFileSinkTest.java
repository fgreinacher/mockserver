package org.mockserver.mock.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

public class AuditFileSinkTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static AuditEntry entry(long epoch, String operation, String outcome) {
        return new AuditEntry(epoch, "PUT", "/mockserver/" + operation, operation, "127.0.0.1:1234", "anonymous", "none", outcome, null);
    }

    @Test
    public void appendsOneValidNdjsonLinePerEntry() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "audit.ndjson");
        AuditFileSink sink = new AuditFileSink();

        sink.write(entry(1, "expectation", "AUTHORIZED"), file.getAbsolutePath());
        sink.write(entry(2, "reset", "AUTHORIZED"), file.getAbsolutePath());
        sink.write(entry(3, "clear", "FORBIDDEN"), file.getAbsolutePath());
        sink.closeForTest();

        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertThat(lines, hasSize(3));
        // each line is a standalone, well-formed JSON object with the expected fields
        JsonNode first = OBJECT_MAPPER.readTree(lines.get(0));
        assertThat(first.get("operation").asText(), is("expectation"));
        assertThat(first.get("outcome").asText(), is("AUTHORIZED"));
        assertThat(first.get("method").asText(), is("PUT"));
        assertThat(OBJECT_MAPPER.readTree(lines.get(1)).get("operation").asText(), is("reset"));
        assertThat(OBJECT_MAPPER.readTree(lines.get(2)).get("outcome").asText(), is("FORBIDDEN"));
    }

    @Test
    public void appendsToExistingFileRatherThanTruncating() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "append.ndjson");

        AuditFileSink first = new AuditFileSink();
        first.write(entry(1, "expectation", "AUTHORIZED"), file.getAbsolutePath());
        first.closeForTest();

        // a fresh sink pointed at the same file must append, not overwrite
        AuditFileSink second = new AuditFileSink();
        second.write(entry(2, "reset", "AUTHORIZED"), file.getAbsolutePath());
        second.closeForTest();

        assertThat(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8), hasSize(2));
    }

    @Test
    public void createsMissingParentDirectories() throws Exception {
        File file = new File(temporaryFolder.getRoot(), "nested/dir/audit.ndjson");
        AuditFileSink sink = new AuditFileSink();

        sink.write(entry(1, "expectation", "AUTHORIZED"), file.getAbsolutePath());
        sink.closeForTest();

        assertThat(file.exists(), is(true));
        assertThat(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8), hasSize(1));
    }

    @Test
    public void disabledByDefaultWhenPathBlank() {
        File file = new File(temporaryFolder.getRoot(), "should-not-exist.ndjson");
        AuditFileSink sink = new AuditFileSink();

        sink.write(entry(1, "expectation", "AUTHORIZED"), "");
        sink.write(entry(2, "reset", "AUTHORIZED"), null);
        sink.write(entry(3, "clear", "AUTHORIZED"), "   ");

        assertThat(file.exists(), is(false));
    }

    @Test
    public void pathFixedAtFirstWrite() throws Exception {
        File firstFile = new File(temporaryFolder.getRoot(), "first.ndjson");
        File secondFile = new File(temporaryFolder.getRoot(), "second.ndjson");
        AuditFileSink sink = new AuditFileSink();

        sink.write(entry(1, "expectation", "AUTHORIZED"), firstFile.getAbsolutePath());
        // a later path change is ignored — the sink resolves its target once
        sink.write(entry(2, "reset", "AUTHORIZED"), secondFile.getAbsolutePath());
        sink.closeForTest();

        assertThat(Files.readAllLines(firstFile.toPath(), StandardCharsets.UTF_8), hasSize(2));
        assertThat(secondFile.exists(), is(false));
    }

    @Test
    public void writeNeverThrowsOnUnopenablePath() throws Exception {
        // point the sink at a path whose parent is an existing regular file, so the
        // directory cannot be created and the open fails — the sink must self-disable
        // silently rather than propagate the IO error into request handling.
        File regularFile = temporaryFolder.newFile("a-file");
        String impossiblePath = new File(regularFile, "child/audit.ndjson").getAbsolutePath();
        AuditFileSink sink = new AuditFileSink();

        sink.write(entry(1, "expectation", "AUTHORIZED"), impossiblePath);
        // subsequent writes remain no-ops and never throw
        sink.write(entry(2, "reset", "AUTHORIZED"), impossiblePath);
    }
}
