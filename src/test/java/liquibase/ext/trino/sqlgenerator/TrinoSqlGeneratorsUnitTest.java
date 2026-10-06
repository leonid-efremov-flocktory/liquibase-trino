package liquibase.ext.trino.sqlgenerator;

import liquibase.change.ColumnConfig;
import liquibase.database.core.H2Database;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.sql.Sql;
import liquibase.statement.core.CreateDatabaseChangeLogLockTableStatement;
import liquibase.statement.core.SelectFromDatabaseChangeLogStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL, which the plugin's two generators build, checked without a live Trino: both must apply
 * only to {@link TrinoDatabase}, and the SELECT one must not upper-case the column list.
 * <p>
 * The generated SQL is compared as a string rather than run against a stand: these generators
 * only concatenate strings, so what matters here is exactly which tokens they emit and in what
 * case. The stand-based tests ({@code TrinoChangeLogUpdateIntegrationTest},
 * {@code TrinoReadCommands…}) stay responsible for proving the SQL actually works against Trino.
 */
class TrinoSqlGeneratorsUnitTest {

    private final TrinoDatabase db = new TrinoDatabase();

    // --- TrinoCreateDatabaseChangeLogLockTableGenerator ---

    @Test
    void lockTableAppliesOnlyToTrino() {
        assertTrue(new TrinoCreateDatabaseChangeLogLockTableGenerator()
                .supports(new CreateDatabaseChangeLogLockTableStatement(), db));
        assertFalse(new TrinoCreateDatabaseChangeLogLockTableGenerator()
                .supports(new CreateDatabaseChangeLogLockTableStatement(), new H2Database()),
                "the lock-table generator must not hijack other dialects");
    }

    @Test
    void lockTableUsesTrinoPriority() {
        assertEquals(TrinoDatabase.TRINO_PRIORITY_DATABASE,
                new TrinoCreateDatabaseChangeLogLockTableGenerator().getPriority());
    }

    @Test
    void lockTableHasNoPrimaryKey() {
        // Trino rejects CONSTRAINT ... PRIMARY KEY in CREATE TABLE, so the ID column is created
        // without one. A primary key here would fail the very first update.
        String sql = lockTableSql();
        assertFalse(sql.toUpperCase().contains("PRIMARY KEY"),
                "DATABASECHANGELOGLOCK must be created without a primary key, but got: " + sql);
        assertTrue(sql.contains("NOT NULL"), "the ID and LOCKED columns must stay NOT NULL: " + sql);
    }

    @Test
    void lockTableUsesTimestampNotDatetime() {
        // H2's default is "datetime", which Trino does not know; the override returns "timestamp".
        String sql = lockTableSql();
        assertTrue(sql.contains("TIMESTAMP"), "LOCKGRANTED must be a timestamp: " + sql);
        assertFalse(sql.toLowerCase().contains("datetime"),
                "Trino has no datetime type: " + sql);
    }

    // --- TrinoSelectFromDatabaseChangeLogGenerator ---

    @Test
    void selectAppliesOnlyToTrino() {
        assertTrue(new TrinoSelectFromDatabaseChangeLogGenerator()
                .supports(new SelectFromDatabaseChangeLogStatement("ID"), db));
        assertFalse(new TrinoSelectFromDatabaseChangeLogGenerator()
                .supports(new SelectFromDatabaseChangeLogStatement("ID"), new H2Database()),
                "the SELECT generator must not hijack other dialects");
    }

    @Test
    void selectUsesTrinoPriority() {
        assertEquals(TrinoDatabase.TRINO_PRIORITY_DATABASE,
                new TrinoSelectFromDatabaseChangeLogGenerator().getPriority());
    }

    @Test
    void selectKeepsColumnListLowerCase() {
        // The base generator runs the joined column list through .toUpperCase(); Trino stores
        // column names in lower case, so that override is what makes the query findable.
        assertEquals("SELECT id,author,filename FROM databasechangelog",
                selectSql(new SelectFromDatabaseChangeLogStatement("ID", "AUTHOR", "FILENAME")));
    }

    @Test
    void selectSupportsWhereClauseAndOrderBy() {
        // The ByTag where-clause is what tag/tagExists go through, and the direction keyword is
        // upper-cased while the column it applies to stays lower-case.
        SelectFromDatabaseChangeLogStatement statement =
                new SelectFromDatabaseChangeLogStatement(
                        new SelectFromDatabaseChangeLogStatement.ByTag("v1"),
                        new ColumnConfig().setName("ID"))
                        .setOrderBy("DATEEXECUTED DESC");

        assertEquals("SELECT id FROM databasechangelog WHERE tag='v1' ORDER BY dateexecuted DESC",
                selectSql(statement));
    }

    @Test
    void selectAppliesLimitForTrino() {
        // The override emits a plain "LIMIT n". The base class only does that for
        // MySQL/Postgres, so without the override a limit would be silently dropped here.
        String sql = selectSql(new SelectFromDatabaseChangeLogStatement("ID").setLimit(1));
        assertTrue(sql.endsWith(" LIMIT 1"), "the LIMIT clause must survive: " + sql);
    }

    private String lockTableSql() {
        Sql[] sql = new TrinoCreateDatabaseChangeLogLockTableGenerator()
                .generateSql(new CreateDatabaseChangeLogLockTableStatement(), db, null);
        return sql[0].toSql();
    }

    private String selectSql(SelectFromDatabaseChangeLogStatement statement) {
        Sql[] sql = new TrinoSelectFromDatabaseChangeLogGenerator().generateSql(statement, db, null);
        return sql[0].toSql();
    }
}