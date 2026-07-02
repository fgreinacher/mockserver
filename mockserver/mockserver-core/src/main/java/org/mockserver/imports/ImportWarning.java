package org.mockserver.imports;

/**
 * A structured, machine-readable warning emitted by a migration importer
 * ({@link WireMockImporter}, {@link MountebankImporter}, {@link MockoonImporter}) when
 * a source construct cannot be faithfully mapped onto a MockServer expectation.
 *
 * <p>Every unmappable construct produces one of these rather than being silently
 * dropped, so a one-shot migration surfaces exactly what did not carry over. The
 * warnings are serialized into the import endpoint's response body alongside the
 * expectations that <em>were</em> imported.
 *
 * <p>Fields:
 * <ul>
 *     <li>{@code item} — a stable locator for the source element (e.g. {@code "stub[2]"},
 *         {@code "imposter tcp:4545"}, {@code "route GET /users response[1]"}).</li>
 *     <li>{@code construct} — the name of the foreign construct that was not (fully) mapped
 *         (e.g. {@code "predicate matchesXPath"}, {@code "fault CONNECTION_RESET_BY_PEER"}).</li>
 *     <li>{@code detail} — a human-readable explanation of what happened (dropped, approximated,
 *         or the whole item skipped).</li>
 * </ul>
 */
public class ImportWarning {

    private final String item;
    private final String construct;
    private final String detail;

    public ImportWarning(String item, String construct, String detail) {
        this.item = item;
        this.construct = construct;
        this.detail = detail;
    }

    public String getItem() {
        return item;
    }

    public String getConstruct() {
        return construct;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "[" + item + "] " + construct + ": " + detail;
    }
}
