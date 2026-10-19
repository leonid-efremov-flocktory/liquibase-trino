package liquibase.ext.trino.snapshot

import liquibase.ext.trino.TrinoIntegrationSpec
import liquibase.ext.trino.TrinoTestSupport
import spock.lang.Shared

/**
 * What {@code diffChangelog} puts in the file, which is not the same question as what {@code diff}
 * reports.
 *
 * <p>The split matters because the two commands answer different questions. {@code diff} says
 * <em>whether</em> the schemas differ; {@code diffChangelog} says <em>what to do</em> about it, and
 * only an object that exists on one side and not the other can be answered. {@code TrinoDdlChangeGenerator}
 * implements {@code MissingObjectChangeGenerator} and nothing else, so:
 * <ul>
 *   <li>a table present only on the reference side is emitted as its verbatim {@code CREATE TABLE},
 *       which is the whole point of the plugin — verified here against {@code SHOW CREATE} itself;
 *   <li>a table present on <em>both</em> sides and differing only in its connector properties is
 *       reported as changed by {@code diff} (see {@code TrinoDiffSpec}) and yields no change at all
 *       here, because no generator implements {@code ChangedObjectChangeGenerator} for it. The
 *       generated file is empty while {@code diff} insists the schemas differ.
 * </ul>
 *
 * <p>That second bullet is the notable one. It is pinned deliberately rather than fixed: closing it
 * needs a {@code ChangedObjectChangeGenerator} that emits a drop-and-recreate from the verbatim
 * statement, and that is a design decision with real consequences — it would destroy data on every
 * {@code format} tweak. It is recorded in Known limitations in the README.
 */
class TrinoDiffChangelogSpec extends TrinoIntegrationSpec {

    private static final String REFERENCE_SCHEMA = "diff_changelog_reference"
    private static final String TARGET_SCHEMA = "diff_changelog_target"
    private static final String SHARED_TABLE = TrinoTestSupport.DIFF_SHARED_TABLE
    private static final String REFERENCE_ONLY = TrinoTestSupport.DIFF_REFERENCE_ONLY
    private static final String TARGET_ONLY = TrinoTestSupport.DIFF_TARGET_ONLY

    private static final String COLUMNS = "(id integer, name varchar)"

    @Shared
    String catalog

    def setup() {
        catalog = TrinoTestSupport.catalog()

        // reference_only_table is partitioned here, and only here: this test compares the
        // emitted statement verbatim, so it needs a partitioning clause that could go missing.
        TrinoTestSupport.createDiffFixture(
                TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA),
                TrinoTestSupport.qualified(catalog, TARGET_SCHEMA),
                COLUMNS,
                "(id integer) WITH (format = 'PARQUET', partitioning = ARRAY['bucket(id, 4)'])")
    }

    def cleanupSpec() {
        // setup() assigns catalog before any fixture exists; if that first step fails,
        // catalog is still null and a teardown built from it would mask the real error.
        if (catalog == null) {
            return
        }
        TrinoTestSupport.dropSchemas(TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA),
                TrinoTestSupport.qualified(catalog, TARGET_SCHEMA))
    }

    /**
     * The case the plugin exists for: a missing table is created from Trino's own words, not from a
     * reconstruction.
     */
    def "a missing table is emitted as verbatim DDL"() {
        when:
        def changelog = diffChangelog()

        then:
        changelog.contains("CREATE TABLE " + TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA)
                + "." + REFERENCE_ONLY)
        !changelog.contains("<createTable")
    }

    /**
     * Compared character for character against a fresh {@code SHOW CREATE}, so the test breaks if the
     * generator ever starts assembling DDL itself. {@code partitioning} is the load-bearing part: a
     * reconstructed {@code CREATE TABLE} has nowhere to put it.
     */
    def "the missing table carries the server statement unchanged"() {
        when:
        def changelog = diffChangelog()
        def expected = showCreate(REFERENCE_SCHEMA, REFERENCE_ONLY)

        then:
        changelog.contains(expected)
    }

    /**
     * A table that exists only on the target side is dropped, by core's
     * {@code UnexpectedTableChangeGenerator} — this plugin only overrides the missing case.
     *
     * <p>Worth pinning because the drop is unqualified ({@code DROP TABLE target_only_table}), where the
     * creates above it are catalog-qualified. It works only because the changelog is applied against a
     * connection whose default schema is the target one; applying the file with a different default
     * schema would drop the wrong table, or nothing. The verbatim path sidesteps the question by
     * carrying the server's own fully-qualified name.
     */
    def "an unexpected table is dropped"() {
        when:
        def changelog = diffChangelog()

        then:
        changelog.contains("DROP TABLE " + TARGET_ONLY)
    }

    /**
     * The gap: {@code diff} reports this table as changed, and {@code diffChangelog} produces nothing.
     *
     * <p>Not asserted as desirable — asserted as what happens. The pair of facts together is the
     * limitation: a user comparing two schemas that differ only in {@code format} is told they differ
     * and is handed an empty changelog.
     */
    def "a changed table with no generator produces no change"() {
        given:
        TrinoTestSupport.execute("DROP TABLE " + TrinoTestSupport.qualified(catalog, TARGET_SCHEMA)
                + "." + SHARED_TABLE)
        TrinoTestSupport.execute("CREATE TABLE " + TrinoTestSupport.qualified(catalog, TARGET_SCHEMA)
                + "." + SHARED_TABLE + " (id integer, name varchar) WITH (format = 'ORC')")

        when:
        def changelog = diffChangelog()

        then:
        !changelog.contains(SHARED_TABLE)
    }

    // --- Plumbing ---

    /**
     * Reads the reference table's DDL straight from the server, for the verbatim comparison.
     */
    private String showCreate(String schema, String table) {
        return TrinoTestSupport.showCreate(catalog, schema, table)
    }

    private String diffChangelog() {
        return TrinoTestSupport.diffChangelog(REFERENCE_SCHEMA, TARGET_SCHEMA)
    }
}
