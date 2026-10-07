package liquibase.ext.trino.snapshot;

import liquibase.command.CommandResults;
import liquibase.command.CommandScope;
import liquibase.command.core.DiffCommandStep;
import liquibase.database.Database;
import liquibase.diff.DiffResult;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoDiffIntegrationTest {

    /** The side that plays the part of the already-deployed database. */
    private static final String REFERENCE_SCHEMA = "diff_reference_probe";

    /** The side that plays the part of the one about to be deployed. */
    private static final String TARGET_SCHEMA = "diff_target_probe";

    private static final String SHARED_TABLE = "shared_table";
    private static final String REFERENCE_ONLY = "reference_only_table";
    private static final String TARGET_ONLY = "target_only_table";
    private static final String DIFFERENT_TABLE = "different_table";

    private static String catalog;

    @BeforeEach
    void setup() throws Exception {
        try (Database db = TrinoTestSupport.openDatabase()) {
            catalog = db.getDefaultCatalogName();
        }
        reference(catalog + "." + REFERENCE_SCHEMA, "(id integer, name varchar)");
        target(catalog + "." + TARGET_SCHEMA, "(id integer, name varchar)");
    }

    /**
     * Reference side, which is also the side the tests extend per case.
     * <p>
     * Created from scratch on every test rather than incrementally: these tests are about what the
     * comparison says, and a leftover object from a previous case would change that answer.
     */
    private void reference(String schema, String columns) throws Exception {
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        TrinoTestSupport.execute("CREATE SCHEMA " + schema);
        TrinoTestSupport.execute("CREATE TABLE " + schema + "." + SHARED_TABLE + " " + columns);
        TrinoTestSupport.execute("CREATE TABLE " + schema + "." + REFERENCE_ONLY + " " + columns);
        TrinoTestSupport.execute("CREATE TABLE " + schema + "." + DIFFERENT_TABLE + " " + columns);
    }

    /** Target side: the reference minus one object, plus one of its own. */
    private void target(String schema, String columns) throws Exception {
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        TrinoTestSupport.execute("CREATE SCHEMA " + schema);
        TrinoTestSupport.execute("CREATE TABLE " + schema + "." + SHARED_TABLE + " " + columns);
        TrinoTestSupport.execute("CREATE TABLE " + schema + "." + TARGET_ONLY + " " + columns);
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

        assertTrue(names(diff.result().getMissingObjects(Table.class)).contains(REFERENCE_ONLY),
                "a table that exists only on the reference side must be reported missing:\n" + diff.render());
        assertTrue(names(diff.result().getUnexpectedObjects(Table.class)).contains(TARGET_ONLY),
                "a table that exists only on the target side must be reported unexpected:\n" + diff.render());
    }

    /** A table on both sides with the same definition is not a difference. */
    @Test
    void anIdenticalObjectIsNotReported() throws Exception {
        Diff diff = diff();

        assertFalse(names(diff.result().getMissingObjects(Table.class)).contains(SHARED_TABLE),
                "a table on both sides must not be reported missing:\n" + diff.render());
        assertFalse(names(diff.result().getUnexpectedObjects(Table.class)).contains(SHARED_TABLE),
                "a table on both sides must not be reported unexpected:\n" + diff.render());
    }

    /**
     * A column-level difference is seen, and reported against the table rather than the column: core
     * groups column differences under their table in {@code getChangedObjects}.
     */
    @Test
    void aColumnDifferenceIsReportedAgainstItsTable() throws Exception {
        target(catalog + "." + TARGET_SCHEMA, "(id integer, name varchar, extra boolean)");

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
        TrinoTestSupport.execute("DROP TABLE " + catalog + "." + TARGET_SCHEMA + "." + SHARED_TABLE);
        TrinoTestSupport.execute("CREATE TABLE " + catalog + "." + TARGET_SCHEMA + "." + SHARED_TABLE
                + " (id integer, name varchar) WITH (format = 'ORC')");

        Diff diff = diff();

        assertTrue(changedNames(diff).contains(SHARED_TABLE),
                "a difference confined to trino.ddl must still be detected:\n" + diff.render());
    }

    // --- The command itself ---

    /**
     * The claim the other cases rest on: an ordinary mismatch must not fail the command. A diff that
     * threw on a difference would be unusable, since a difference is the normal case.
     */
    @Test
    void aDiffWithDifferencesCompletes() throws Exception {
        Diff diff = diff();

        assertFalse(diff.result().getMissingObjects().isEmpty(),
                "the fixture must really differ, or this test proves nothing:\n" + diff.render());
    }

    // --- Plumbing ---

    /**
     * Names of the tables {@code diff} reported as changed.
     *
     * <p>Compared by name rather than by key lookup: {@code getChangedObjects} is keyed by an object
     * instance from one of the two sides, and which side that is not something a test should depend on.
     */
    private static Set<String> changedNames(Diff diff) {
        return diff.result().getChangedObjects(Table.class).keySet().stream()
                .map(DatabaseObject::getName)
                .collect(Collectors.toSet());
    }

    private static Set<String> names(Set<? extends DatabaseObject> objects) {
        return objects.stream().map(DatabaseObject::getName).collect(Collectors.toSet());
    }

    /** One diff run, keeping both the structured result and the text a user would have read. */
    private record Diff(DiffResult result, String output) {

        /** What to print when an assertion about this run fails. */
        String render() {
            return output.isEmpty()
                    ? "missing=" + names(result.getMissingObjects(Table.class))
                        + " unexpected=" + names(result.getUnexpectedObjects(Table.class))
                        + " changed=" + result.getChangedObjects(Table.class).keySet()
                    : output;
        }
    }

    private static Diff diff() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandResults results = new CommandScope(DiffCommandStep.COMMAND_NAME[0])
                .addArgumentValue("url", TrinoTestSupport.url())
                .addArgumentValue("username", TrinoTestSupport.user())
                .addArgumentValue("referenceUrl", TrinoTestSupport.url())
                .addArgumentValue("referenceUsername", TrinoTestSupport.user())
                // The schema arguments live in PreCompareCommandStep, not DiffCommandStep, and are
                // passed by name because they are hidden arguments with no public constant to reach for.
                .addArgumentValue("schemas", TARGET_SCHEMA)
                .addArgumentValue("referenceSchemas", REFERENCE_SCHEMA)
                .setOutput(out)
                .execute();
        DiffResult result = results.getResult(DiffCommandStep.DIFF_RESULT);
        return new Diff(result, out.toString(StandardCharsets.UTF_8));
    }
}