package liquibase.ext.trino.snapshot;

import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.snapshot.CachedRow;
import liquibase.snapshot.JdbcDatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.structure.core.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard in {@link TrinoTableSnapshotGenerator} that keeps one unreadable table from taking the
 * command down.
 *
 * <p>It is unit-tested rather than integration-tested for a practical reason, not because the failure
 * is unreachable here. Core's per-object read does reach the driver: {@code TableSnapshotGenerator}
 * calls {@code getMetaDataFromCache().getTables(catalog, schema, name)}, and the
 * {@code SingleResultSetExtractor} behind it only falls back to a bulk read once a cache key has been
 * queried on its own three times or more ({@code shouldBulkSelect} compares
 * {@code getTimesSingleQueried(key) >= 3}). The first tables of a schema are therefore fetched one at
 * a time — but which three are an implementation detail that a test cannot pin, so there is no
 * reliable way to make a live stand fail one particular table's read. Overriding {@code readTable}
 * puts the failure exactly where a real one lands: inside {@code snapshotObject}, per object, after
 * the row has been found.
 *
 * <p>The DDL-read side of the same guard needs no stand at all and lives in
 * {@code TrinoDdlFetcherUnitTest}; the view guard is covered end to end in
 * {@code TrinoSnapshotFailureIntegrationTest}, where a view's body really does come from an overridable
 * dialect method.
 */
@Tag("integration")
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoTableSnapshotGeneratorUnitTest {

    private static final String SCHEMA = "snapshot_generator_rt";

    private static final String TABLE = "good_table";

    @BeforeAll
    static void createFixture() throws Exception {
        String qualified = TrinoTestSupport.catalog() + "." + SCHEMA;
        TrinoTestSupport.resetSchemas(qualified);
        TrinoTestSupport.execute("CREATE TABLE " + qualified + "." + TABLE + " (id integer)");
    }

    /**
     * The schema is this class's own, which it used to borrow.
     * <p>
     * It pointed at {@link TrinoSnapshotFailureIntegrationTest}'s probe schema and relied on that
     * class having left it behind. Adding teardown there turned that dependency into a test-order
     * failure: the tests still passed together, but only because of where surefire happened to put
     * the classes, and {@code aReadableTableIsStillSnapshotted} — the control for every other case
     * here — came back null. A fixture has to belong to the class that reads it.
     */
    @AfterAll
    static void dropFixture() throws Exception {
        TrinoTestSupport.dropSchemas(TrinoTestSupport.catalog() + "." + SCHEMA);
    }

    /**
     * The control. Without it, every test below would pass on a generator that returns null for
     * everything — which is indistinguishable from correctly skipping one bad table.
     */
    @Test
    void aReadableTableIsStillSnapshotted() throws Exception {
        assertEquals(TABLE, snapshotOf(new TrinoTableSnapshotGenerator(), TABLE).getName(),
                "a table that can be read must be snapshotted");
    }

    /**
     * The case the guard exists for: {@code readTable} fails on this table and nowhere else.
     */
    @Test
    void aTableThatCannotBeReadIsSkippedRatherThanFatal() throws Exception {
        assertNull(snapshotOf(new FailingTableSnapshotGenerator(new DatabaseException("injected")), TABLE),
                "a table whose metadata cannot be read must be left out of the snapshot");
    }

    /**
     * A checked exception is not the only thing that escapes. Type conversion inside the JDBC layer
     * throws bare {@code RuntimeException}s on a type it cannot map, and those reach the generator
     * too.
     */
    @Test
    void anUncheckedFailureIsSkippedRatherThanFatal() throws Exception {
        assertNull(snapshotOf(new FailingTableSnapshotGenerator(
                        new UnexpectedLiquibaseException("injected unchecked failure")), TABLE),
                "an unchecked failure must be handled like a checked one");
    }

    /** And the failure has to be reported, or the changelog is silently short. */
    @Test
    void aSkippedTableIsNamedInAWarning() throws Exception {
        WarningRecorder.Result<Object> recorded = WarningRecorder.record(
                () -> snapshotOf(new FailingTableSnapshotGenerator(new DatabaseException("injected")), TABLE));

        assertNull(recorded.value(), "the table must be left out of the snapshot");
        assertTrue(recorded.mentions(TABLE),
                "the warning must name the skipped table, got:\n"
                        + String.join("\n", recorded.warnings()));
    }

    /**
     * The other way out, and the one this class is really about.
     *
     * <p>The metadata read succeeds, so the table is already in hand — but its statement cannot be
     * read. Keeping it would leave core to reconstruct a structural {@code CREATE TABLE} with no
     * connector properties, which describes a different object rather than a worse copy of this one.
     * The generator has to drop it, and this is the only place that can: the row was found through
     * the cached metadata, so nothing upstream is aware that anything went wrong.
     */
    @Test
    void aTableWhoseStatementCannotBeReadIsDroppedToo() throws Exception {
        Database db = TrinoTestSupport.openDatabase();
        try {
            FailingTrinoDatabase failing = FailingTrinoDatabase.on(TABLE);
            failing.setConnection(new JdbcConnection(TrinoTestSupport.openRaw()));

            assertNull(snapshotOf(new TrinoTableSnapshotGenerator(), TABLE, failing),
                    "a table with no verbatim statement must be left out rather than kept without it");
        } finally {
            db.close();
        }
    }

    /**
     * A {@link JdbcDatabaseSnapshot}, because that is the type {@code TableSnapshotGenerator} casts to
     * when it reads metadata. {@code EmptyDatabaseSnapshot} is not one and answers nothing at all,
     * which would make every case here return null — passing for the right reason.
     */
    private static Table snapshotOf(SnapshotGenerator generator, String tableName) throws Exception {
        return snapshotOf(generator, tableName, null);
    }

    private static Table snapshotOf(SnapshotGenerator generator, String tableName, Database override)
            throws Exception {
        Database db = override != null ? override : TrinoTestSupport.openDatabase();
        try {
            Table example = new Table(db.getDefaultCatalogName(), SCHEMA, tableName);
            JdbcDatabaseSnapshot snapshot = new JdbcDatabaseSnapshot(
                    new liquibase.structure.DatabaseObject[0], db, new SnapshotControl(db));
            return (Table) generator.snapshot(example, snapshot, null);
        } finally {
            db.close();
        }
    }

    /**
     * Fails the per-object part of the read, leaving the lookup that found the row intact.
     *
     * <p>{@code readTable} is declared to throw the checked {@link DatabaseException}, so an unchecked
     * failure has to be smuggled in rather than thrown directly — which is exactly the awkwardness a
     * real JDBC layer produces, and the reason the guard catches {@code Exception} rather than
     * {@code DatabaseException}.
     */
    private static class FailingTableSnapshotGenerator extends TrinoTableSnapshotGenerator {

        private final DatabaseException checked;
        private final RuntimeException unchecked;

        private FailingTableSnapshotGenerator(DatabaseException failure) {
            this.checked = failure;
            this.unchecked = null;
        }

        private FailingTableSnapshotGenerator(RuntimeException failure) {
            this.checked = null;
            this.unchecked = failure;
        }

        @Override
        protected Table readTable(CachedRow tableMetadataResultSet, Database database) throws DatabaseException {
            if (unchecked != null) {
                throw unchecked;
            }
            throw checked;
        }
    }
}