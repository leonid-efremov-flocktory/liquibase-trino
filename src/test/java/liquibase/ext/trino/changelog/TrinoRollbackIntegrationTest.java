package liquibase.ext.trino.changelog;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.resource.ClassLoaderResourceAccessor;
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
     * The class leaves the fixture rolled back and does not restore it: the next class that
     * needs the applied state gets it from {@link TrinoTestSupport#applyIfNeeded()}, and
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
                "Вывод rollback-sql должен содержать заголовок: " + sql);
        assertTrue(sql.contains("DROP VIEW IF EXISTS " + TrinoTestSupport.FIXTURE_VIEW),
                "Скрипт отката должен содержать удаление test_view: " + sql);
        assertTrue(sql.contains("DROP TABLE IF EXISTS " + TrinoTestSupport.FIXTURE_TABLE),
                "Скрипт отката должен содержать удаление test_table: " + sql);
        assertTrue(sql.contains("DELETE FROM " + TrinoTestSupport.FIXTURE_TABLE),
                "Скрипт отката должен содержать удаление строк, добавленных в v2: " + sql);

        // The writer path executes nothing: the objects are still in place.
        assertEquals("5", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE));
        assertEquals("1", TrinoTestSupport.countFixtureObjects(TrinoTestSupport.VIEW_NAME));
    }

    @Test
    @Order(2)
    void rollbackLastChangesetRestoresViewAndRemovesItsRows() throws Exception {
        TrinoTestSupport.rollback(db, 1);

        assertEquals("3", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE),
                "Откат v2 должен удалить добавленные им строки 4 и 5");
        assertEquals("3", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_VIEW),
                "Откат v2 возвращает вью к выборке без условия id > 1, поэтому она отдаёт все строки таблицы");

        assertEquals(List.of("common-schema-setup", "v1-test-table-and-view"),
                TrinoTestSupport.appliedChangesetIds(),
                "Из DATABASECHANGELOG должна исчезнуть запись только об откатанном changeset'е");

        assertLockReleased();
    }

    @Test
    @Order(3)
    void rollbackRemainingChangesetDropsTableAndView() throws Exception {
        TrinoTestSupport.rollback(db, 1);

        assertEquals("0", TrinoTestSupport.countFixtureObjects(TrinoTestSupport.VIEW_NAME),
                "Откат v1 должен удалить вью test_view");
        assertEquals("0", TrinoTestSupport.countFixtureObjects(TrinoTestSupport.TABLE_NAME),
                "Откат v1 должен удалить таблицу test_table");

        assertEquals(List.of("common-schema-setup"),
                TrinoTestSupport.appliedChangesetIds(),
                "Откат v1 должен убрать из DATABASECHANGELOG обе записи об объектах фикстуры, "
                        + "оставив только no-op changeset создания схемы");

        assertLockReleased();
    }

    /** Rollback takes the lock too: a stuck one would only show up on the next update. */
    private static void assertLockReleased() throws Exception {
        assertEquals(List.of("false|"), TrinoTestSupport.lockState(),
                "DATABASECHANGELOGLOCK должна остаться с LOCKED = false и пустым LOCKEDBY после rollback");
    }
}