package liquibase.ext.trino.snapshot;

import liquibase.ext.trino.TrinoTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 *       reported as changed by {@code diff} (see {@code TrinoDiffIntegrationTest}) and yields no
 *       change at all here, because no generator implements {@code ChangedObjectChangeGenerator} for
 *       it. The generated file is empty while {@code diff} insists the schemas differ.
 * </ul>
 *
 * <p>That second bullet is the notable one. It is pinned deliberately rather than fixed: closing it
 * needs a {@code ChangedObjectChangeGenerator} that emits a drop-and-recreate from the verbatim
 * statement, and that is a design decision with real consequences — it would destroy data on every
 * {@code format} tweak. It is recorded in Known limitations in the README.
 */
@Tag("integration")
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoDiffChangelogIntegrationTest {

    private static final String REFERENCE_SCHEMA = "diff_changelog_reference";
    private static final String TARGET_SCHEMA = "diff_changelog_target";
    private static final String SHARED_TABLE = TrinoTestSupport.DIFF_SHARED_TABLE;
    private static final String REFERENCE_ONLY = TrinoTestSupport.DIFF_REFERENCE_ONLY;
    private static final String TARGET_ONLY = TrinoTestSupport.DIFF_TARGET_ONLY;

    private static final String COLUMNS = "(id integer, name varchar)";

    private static String catalog;

    @AfterAll
    static void dropProbeSchemas() throws Exception {
        TrinoTestSupport.dropSchemas(TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA), TrinoTestSupport.qualified(catalog, TARGET_SCHEMA));
    }

    @BeforeEach
    void setup() throws Exception {
        catalog = TrinoTestSupport.catalog();

        // reference_only_table is partitioned here, and only here: this test compares the
        // emitted statement verbatim, so it needs a partitioning clause that could go missing.
        TrinoTestSupport.createDiffFixture(
                TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA), TrinoTestSupport.qualified(catalog, TARGET_SCHEMA),
                COLUMNS, "(id integer) WITH (format = 'PARQUET', partitioning = ARRAY['bucket(id, 4)'])");
    }

    /**
     * The case the plugin exists for: a missing table is created from Trino's own words, not from a
     * reconstruction.
     */
    @Test
    void aMissingTableIsEmittedAsVerbatimDdl() throws Exception {
        String changelog = diffChangelog();

        assertTrue(changelog.contains("CREATE TABLE " + TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA) + "." + REFERENCE_ONLY),
                "the missing table must be created in the changelog:\\n" + changelog);
        assertFalse(changelog.contains("<createTable"),
                "the structural form must not be used — it cannot express the connector properties:\\n"
                        + changelog);
    }

    /**
     * Compared character for character against a fresh {@code SHOW CREATE}, so the test breaks if the
     * generator ever starts assembling DDL itself. {@code partitioning} is the load-bearing part: a
     * reconstructed {@code CREATE TABLE} has nowhere to put it.
     */
    @Test
    void theMissingTableCarriesTheServerStatementUnchanged() throws Exception {
        String changelog = diffChangelog();
        String expected = showCreate(REFERENCE_SCHEMA, REFERENCE_ONLY);

        assertTrue(changelog.contains(expected),
                "the changelog must carry SHOW CREATE verbatim.\\n--- from SHOW CREATE ---\\n"
                        + expected + "\\n--- from changelog ---\\n" + changelog);
    }

    /**
     * A table that exists only on the target side is dropped, by core's
     * {@code UnexpectedTableChangeGenerator} — this plugin only overrides the missing case.
     * <p>
     * Worth pinning because the drop is unqualified ({@code DROP TABLE target_only_table}), where the
     * creates above it are catalog-qualified. It works only because the changelog is applied against a
     * connection whose default schema is the target one; applying the file with a different default
     * schema would drop the wrong table, or nothing. The verbatim path sidesteps the question by
     * carrying the server's own fully-qualified name.
     */
    @Test
    void anUnexpectedTableIsDropped() throws Exception {
        String changelog = diffChangelog();

        assertTrue(changelog.contains("DROP TABLE " + TARGET_ONLY),
                "a table only the target has must be dropped:\\n" + changelog);
    }

    /**
     * The gap: {@code diff} reports this table as changed, and {@code diffChangelog} produces nothing.
     *
     * <p>Not asserted as desirable — asserted as what happens. The pair of facts together is the
     * limitation: a user comparing two schemas that differ only in {@code format} is told they differ
     * and is handed an empty changelog.
     */
    @Test
    void aChangedTableWithNoGeneratorProducesNoChange() throws Exception {
        TrinoTestSupport.execute("DROP TABLE " + TrinoTestSupport.qualified(catalog, TARGET_SCHEMA) + "." + SHARED_TABLE);
        TrinoTestSupport.execute("CREATE TABLE " + TrinoTestSupport.qualified(catalog, TARGET_SCHEMA) + "." + SHARED_TABLE
                + " (id integer, name varchar) WITH (format = 'ORC')");

        String changelog = diffChangelog();

        assertFalse(changelog.contains(SHARED_TABLE),
                "with no ChangedObjectChangeGenerator for Trino tables, a changed table yields no change:\\n"
                        + changelog);
    }

    // --- Plumbing ---

    /**
     * Reads the reference table's DDL straight from the server, for the verbatim comparison.
     */
    private static String showCreate(String schema, String table) throws Exception {
        return TrinoTestSupport.showCreate(catalog, schema, table);
    }

    private static String diffChangelog() throws Exception {
        return TrinoTestSupport.diffChangelog(REFERENCE_SCHEMA, TARGET_SCHEMA);
    }
}