package org.mockserver.imports;

import org.mockserver.mock.Expectation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of a migration import ({@link WireMockImporter}, {@link MountebankImporter},
 * {@link MockoonImporter}): the {@link Expectation}s that were successfully mapped plus the
 * ordered list of {@link ImportWarning}s describing every source construct that could not be
 * faithfully mapped.
 *
 * <p>Unlike {@link HarImporter} / {@link PostmanCollectionImporter} — which return a bare
 * {@code List<Expectation>} because HAR/Postman map cleanly — the foreign mock-tool formats have
 * constructs with no MockServer equivalent (TCP imposters, faults, XPath predicates, unsupported
 * response modes). Rather than dropping those silently, the importer records a warning so the
 * migrating user sees exactly what did not carry over.
 */
public class ImportResult {

    private final List<Expectation> expectations;
    private final List<ImportWarning> warnings;

    public ImportResult(List<Expectation> expectations, List<ImportWarning> warnings) {
        this.expectations = expectations != null ? expectations : new ArrayList<>();
        this.warnings = warnings != null ? warnings : new ArrayList<>();
    }

    public List<Expectation> getExpectations() {
        return expectations;
    }

    public List<ImportWarning> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }
}
