package liquibase.ext.trino.snapshot;

import liquibase.ext.trino.TrinoTestSupport;
import liquibase.ext.trino.TrinoTestSupport.Diff;
import liquibase.structure.core.Table;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code diff} reports today, pinned as-is.
 *
 * <p>This class asserts behaviour rather than changing it. Two things it documents are worth knowing
 * before anyone builds on {@code diff}:
 * <ul>
 *   <li>the attribute {@code trino.ddl} is not compared. Core compares structural fields only, so two
 *       tables that differ solely in {@code partitioning}, {@code format}, a comment or a location
 *       come out <em>equal</em> — the diff has no way to see them;
 *   <li>{@code TrinoDdlChangeGenerator} implements {@code MissingObjectChangeGenerator} only. A table
 *       that exists on one side and not the other is emitted verbatim; a table that exists on both and
 *       differs is handed to core's structural generators, which cannot express a Trino table anyway.
 * </ul>
 * Both are in Known limitations in the README. Tests that assert them are what stops a future change
 * from shifting that line unnoticed.
 *
 * <p>Two schemas in the one catalog rather than two servers: {@code diff} takes
 * {@code referenceUrl} + {@code referenceSchemas} against the same cluster, which is enough to compare
 * two schemas and keeps the stand to a single container.
 */
@Tag("integration")
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoDiffIntegrationTest {

    /** The side that plays the part of the already-deployed database. */
    private static final String REFERENCE_SCHEMA = "diff_reference_probe";

    /** The side that plays the part of the one about to be deployed. */
    private static final String TARGET_SCHEMA = "diff_target_probe";

    private static final String SHARED_TABLE = TrinoTestSupport.DIFF_SHARED_TABLE;
    private static final String REFERENCE_ONLY = TrinoTestSupport.DIFF_REFERENCE_ONLY;
    private static final String TARGET_ONLY = TrinoTestSupport.DIFF_TARGET_ONLY;

    /** Both sides start from the same columns; the tests that need a difference change them. */
    private static final String COLUMNS = "(id integer, name varchar)";
    private static final String DIFFERENT_TABLE = "different_table";

    private static String catalog;

    @BeforeEach
    void setup() throws Exception {
        catalog = TrinoTestSupport.catalog();
        TrinoTestSupport.createDiffFixture(
                TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA), TrinoTestSupport.qualified(catalog, TARGET_SCHEMA),
                COLUMNS, COLUMNS);

        // The one table that exists on both sides but differs: same name, different columns.
        TrinoTestSupport.execute("CREATE TABLE " + TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA) + "."
                + DIFFERENT_TABLE + " " + COLUMNS);
    }

    @AfterAll
    static void dropProbeSchemas() throws Exception {
        TrinoTestSupport.dropSchemas(TrinoTestSupport.qualified(catalog, REFERENCE_SCHEMA), TrinoTestSupport.qualified(catalog, TARGET_SCHEMA));
    }

    // --- What the comparison reports ---

    /**
     * The baseline both sides agree on. Every other case in this class depends on the diff being able
     * to see a difference at all, and a fixture that had quietly stopped creating objects would make
     * every one of them pass for the wrong reason.
     */
    @Test
    void anObjectPresentOnOnlyOneSideIsReported() throws Exception {
        Diff diff = diff();

        assertTrue(TrinoTestSupport.names(diff.result().getMissingObjects(Table.class)).contains(REFERENCE_ONLY),
                "a table that exists only on the reference side must be reported missing:\n" + diff.render());
        assertTrue(TrinoTestSupport.names(diff.result().getUnexpectedObjects(Table.class)).contains(TARGET_ONLY),
                "a table that exists only on the target side must be reported unexpected:\n" + diff.render());
    }

    /** A table on both sides with the same definition is not a difference. */
    @Test
    void anIdenticalObjectIsNotReported() throws Exception {
        Diff diff = diff();

        assertFalse(TrinoTestSupport.names(diff.result().getMissingObjects(Table.class)).contains(SHARED_TABLE),
                "a table on both sides must not be reported missing:\n" + diff.render());
        assertFalse(TrinoTestSupport.names(diff.result().getUnexpectedObjects(Table.class)).contains(SHARED_TABLE),
                "a table on both sides must not be reported unexpected:\n" + diff.render());
    }

    /**
     * A column-level difference is seen, and reported against the table rather than the column: core
     * groups column differences under their table in {@code getChangedObjects}.
     */
    @Test
    void aColumnDifferenceIsReportedAgainstItsTable() throws Exception {
        // Rebuild the target side alone: the reference has to stay as the fixture left it.
        TrinoTestSupport.resetSchemas(TrinoTestSupport.qualified(catalog, TARGET_SCHEMA));
        TrinoTestSupport.execute("CREATE TABLE " + TrinoTestSupport.qualified(catalog, TARGET_SCHEMA) + "."
                + SHARED_TABLE + " (id integer, name varchar, extra boolean)");
        TrinoTestSupport.execute("CREATE TABLE " + TrinoTestSupport.qualified(catalog, TARGET_SCHEMA) + "."
                + TARGET_ONLY + " " + COLUMNS);

        Diff diff = diff();

        assertTrue(changedNames(diff).contains(SHARED_TABLE),
                "a table whose columns differ must be reported changed:\n" + diff.render());
    }

    /**
     * A difference that only exists in {@code SHOW CREATE TABLE} is detected — and that is the
     * interesting part.
     *
     * <p>{@code format} lives nowhere but the {@code trino.ddl} attribute, so a table that differs
     * only in its connector properties has no structural field to differ in. Core's
     * {@code DefaultDatabaseObjectComparator} compares the union of attribute names generically, which
     * picks {@code trino.ddl} up without knowing what it is. The difference is therefore reported as a
     * changed table.
     *
     * <p>What {@code diff} then does about it is the real limitation: {@code TrinoDdlChangeGenerator}
     * implements {@code MissingObjectChangeGenerator} only, so a changed table falls through to core,
     * which compares the structural fields it knows — and this one differs in none of them. The
     * user sees a changed table and a changelog with nothing in it to do about it. That gap is what
     * {@code TrinoDiffChangelogIntegrationTest} pins.
     */
    @Test
    void aDifferenceOnlyInTheConnectorPropertiesIsReportedAsChanged() throws Exception {
        TrinoTestSupport.execute("DROP TABLE " + TrinoTestSupport.qualified(catalog, TARGET_SCHEMA) + "." + SHARED_TABLE);
        TrinoTestSupport.execute("CREATE TABLE " + TrinoTestSupport.qualified(catalog, TARGET_SCHEMA) + "." + SHARED_TABLE
                + " (id integer, name varchar) WITH (format = 'ORC')");

        Diff diff = diff();

        assertTrue(changedNames(diff).contains(SHARED_TABLE),
                "a difference confined to trino.ddl must still be detected:\n" + diff.render());
    }

    // --- Plumbing ---

    /**
     * Names of the tables {@code diff} reported as changed.
     *
     * <p>Compared by name rather than by key lookup: {@code getChangedObjects} is keyed by an object
     * instance from one of the two sides, and which side that is not something a test should depend on.
     */
    private static Set<String> changedNames(Diff diff) {
        return TrinoTestSupport.names(diff.result().getChangedObjects(Table.class).keySet());
    }

    private static Diff diff() throws Exception {
        return TrinoTestSupport.diff(REFERENCE_SCHEMA, TARGET_SCHEMA);
    }
}