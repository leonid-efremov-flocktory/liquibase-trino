package liquibase.ext.trino.changelog;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rollback of fixture changesets against a live Trino.
 * <p>
 * The tests run in order: first the writer variant of rollback, which executes nothing, then
 * real {@code rollback} calls. The fixture is never re-applied — once applied, each changeset
 * can be rolled back exactly once.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("integration")
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoRollbackIntegrationTest {

    private static Database db;

    @BeforeAll
    static void setup() throws Exception {
        TrinoTestSupport.applyIfNeeded();
        db = TrinoTestSupport.openChangelogDatabase();
    }

    /**
     * The order matters and is not just about rearranging tests: each changeset is rolled
     * back exactly once, so the stand's state at the start of a test is set by the previous
     * one. Rollback and the verification of its result deliberately live in the same method
     * — otherwise a failing intermediate assertion would leave the stand in a state the
     * next test does not expect.
     * <p>
     * The class leaves the fixture fully rolled back and does not restore it: the next class
     * that needs the applied state gets it from {@link TrinoTestSupport#applyIfNeeded()}, and
     * re-applying it here would apply the same changesets twice per run.
     */

    @Test
    @Order(1)
    void rollbackSqlWritesStatementsWithoutExecutingThem() throws Exception {
        StringWriter output = new StringWriter();
        Liquibase liquibase = new Liquibase(
                TrinoTestSupport.CHANGELOG,
                new ClassLoaderResourceAccessor(),
                db);
        liquibase.rollback(2, TrinoTestSupport.CONTEXT, output);

        String sql = output.toString();
        assertTrue(sql.contains("Rollback 2 Change(s) Script"),
                "the rollback-sql output must contain the header: " + sql);
        assertTrue(sql.contains("DROP VIEW IF EXISTS " + TrinoTestSupport.FIXTURE_VIEW),
                "the rollback script must contain the drop of test_view: " + sql);
        assertTrue(sql.contains("DROP TABLE IF EXISTS " + TrinoTestSupport.FIXTURE_TABLE),
                "the rollback script must contain the drop of test_table: " + sql);
        assertTrue(sql.contains("DELETE FROM " + TrinoTestSupport.FIXTURE_TABLE),
                "the rollback script must contain the delete of the rows v2 added: " + sql);

        // The writer path executes nothing: the objects are still in place.
        assertEquals("5", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE));
        assertEquals("1", TrinoTestSupport.countFixtureObjects(TrinoTestSupport.VIEW_NAME));
    }

    @Test
    @Order(2)
    void rollbackLastChangesetRestoresViewAndRemovesItsRows() throws Exception {
        TrinoTestSupport.rollback(db, 1);

        assertEquals("3", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE),
                "rolling back v2 must remove the rows 4 and 5 it added");
        assertEquals("3", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_VIEW),
                "rolling back v2 puts the view back to the selection without id > 1, "
                        + "so it returns every row of the table");

        assertEquals(List.of("common-schema-setup", "v1-test-table-and-view"),
                TrinoTestSupport.appliedChangesetIds(),
                "only the rolled back changeset must disappear from DATABASECHANGELOG");

        assertLockReleased();
    }

    @Test
    @Order(3)
    void rollbackRemainingChangesetDropsTableAndView() throws Exception {
        TrinoTestSupport.rollback(db, 1);

        assertEquals("0", TrinoTestSupport.countFixtureObjects(TrinoTestSupport.VIEW_NAME),
                "rolling back v1 must drop test_view");
        assertEquals("0", TrinoTestSupport.countFixtureObjects(TrinoTestSupport.TABLE_NAME),
                "rolling back v1 must drop test_table");

        assertEquals(List.of("common-schema-setup"),
                TrinoTestSupport.appliedChangesetIds(),
                "rolling back v1 must clear both fixture object rows from DATABASECHANGELOG, "
                        + "leaving only the no-op changeset that created the schema");

        assertLockReleased();
    }

    /**
     * The fixture's first changeset carries an empty {@code <rollback/>}: the schemas it created
     * are deliberately not dropped, so rolling it back has nothing to do.
     * <p>
     * That makes this the only place the empty block is exercised against a live stand. It used
     * to be asserted indirectly by a stand-less unit test that only checked the parse result
     * ({@code assertInstanceOf(EmptyChange.class, …)}); asserting it here is stronger, because
     * it also pins the observable consequence — the changeset disappears from
     * {@code DATABASECHANGELOG} while its schemas survive, which is what "no-op" has to mean.
     */
    @Test
    @Order(4)
    void rollbackOfAnEmptyBlockUndoesNothingButForgetsTheChangeset() throws Exception {
        TrinoTestSupport.rollback(db, 1);

        assertEquals(List.of(), TrinoTestSupport.appliedChangesetIds(),
                "rolling back the empty <rollback/> must remove the changeset row too");

        assertEquals(List.of(TrinoTestSupport.FIXTURE_SCHEMA_NAME),
                TrinoTestSupport.queryFirstColumn(
                        "SELECT schema_name FROM " + TrinoTestSupport.catalog()
                                + ".information_schema.schemata WHERE schema_name = '"
                                + TrinoTestSupport.FIXTURE_SCHEMA_NAME + "'"),
                "the schemas the rolled back changeset created must have survived");

        assertLockReleased();
    }

    /** Rollback takes the lock too: a stuck one would only show up on the next update. */
    private static void assertLockReleased() throws Exception {
        assertEquals(List.of("false|"), TrinoTestSupport.lockState(),
                "DATABASECHANGELOGLOCK must remain with LOCKED = false and an empty LOCKEDBY "
                        + "after the rollback");
    }
}