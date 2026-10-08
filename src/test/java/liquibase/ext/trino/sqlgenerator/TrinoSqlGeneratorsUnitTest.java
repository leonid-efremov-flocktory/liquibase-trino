package liquibase.ext.trino.sqlgenerator;

import liquibase.change.ColumnConfig;
import liquibase.database.core.H2Database;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGenerator;
import liquibase.statement.NotNullConstraint;
import liquibase.statement.core.AddColumnStatement;
import liquibase.statement.core.CreateDatabaseChangeLogLockTableStatement;
import liquibase.statement.core.RenameColumnStatement;
import liquibase.statement.core.SelectFromDatabaseChangeLogStatement;
import liquibase.statement.SqlStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL, which the plugin's generators build, checked without a live Trino: each must apply only to
 * {@link TrinoDatabase}, the SELECT one must not upper-case the column list, and the two that exist
 * because Trino spells ALTER differently must emit Trino's spelling.
 * <p>
 * The generated SQL is compared as a string rather than run against a stand: these generators
 * only concatenate strings, so what matters here is exactly which tokens they emit and in what
 * case. The stand-based tests ({@code TrinoChangeLogUpdateIntegrationTest},
 * {@code TrinoReadCommands…}) stay responsible for proving the SQL actually works against Trino.
 */
class TrinoSqlGeneratorsUnitTest {

    private final TrinoDatabase db = new TrinoDatabase();

    // --- Dialect selection, common to all four generators ---

    /**
     * Each generator claims Trino and nothing else.
     * <p>
     * One parameterized test over all four rather than a pair per generator: the property is the
     * same one each time, and four copies of it could only start disagreeing — one generator
     * quietly losing its {@code H2Database} rejection would leave the other three asserting.
     */
    @ParameterizedTest(name = "the {0} generator applies to Trino")
    @MethodSource("generatorsWithTheirStatement")
    void appliesOnlyToTrino(String name, SqlGenerator generator, SqlStatement statement) {
        assertTrue(generator.supports(statement, db),
                "the " + name + " generator must recognise Trino");
        assertFalse(generator.supports(statement, new H2Database()),
                "the " + name + " generator must not hijack other dialects");
    }

    /**
     * The same four, checked against the priority Liquibase resolves dialects by. All four return
     * the same constant, which is the point: a generator that forgot to override it would fall back
     * to its base class's default and lose every Trino connection.
     */
    @ParameterizedTest(name = "the {0} generator uses the Trino priority")
    @MethodSource("generatorsWithTheirStatement")
    void usesTrinoPriority(String name, SqlGenerator generator, SqlStatement statement) {
        assertEquals(TrinoDatabase.TRINO_PRIORITY_DATABASE, generator.getPriority(),
                "the " + name + " generator must resolve Trino ahead of the dialects it extends");
    }

    /** Each generator, the name to report it by, and a statement of the kind it handles. */
    private static Stream<Arguments> generatorsWithTheirStatement() {
        return Stream.of(
                Arguments.of("lock table", new TrinoCreateDatabaseChangeLogLockTableGenerator(),
                        new CreateDatabaseChangeLogLockTableStatement()),
                Arguments.of("SELECT", new TrinoSelectFromDatabaseChangeLogGenerator(),
                        new SelectFromDatabaseChangeLogStatement("ID")),
                Arguments.of("add column", new TrinoAddColumnGenerator(),
                        new AddColumnStatement(null, null, "t", "txt", "VARCHAR(64)", null)),
                Arguments.of("rename column", new TrinoRenameColumnGenerator(),
                        new RenameColumnStatement(null, null, "t", "txt", "body", null)));
    }

    // --- TrinoCreateDatabaseChangeLogLockTableGenerator ---

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

    // --- TrinoAddColumnGenerator ---

    @Test
    void addColumnIncludesTheColumnKeyword() {
        // Trino rejects a bare ADD: "mismatched input '<name>'. Expecting: '.', 'ADD'".
        assertEquals("ALTER TABLE t ADD COLUMN txt VARCHAR(64)", addColumnSql());
    }

    @Test
    void addColumnKeepsNotNullAndDefault() {
        // The override only inserts the COLUMN keyword; everything else is still the base
        // generator's, so the constraint and default clauses must survive it.
        AddColumnStatement statement = new AddColumnStatement(
                null, null, "t", "txt", "VARCHAR(64)", "x", new NotNullConstraint());

        String sql = new TrinoAddColumnGenerator().generateSql(statement, db, null)[0].toSql();

        assertTrue(sql.startsWith("ALTER TABLE t ADD COLUMN txt VARCHAR(64)"), sql);
        assertTrue(sql.contains("NOT NULL"), "the NOT NULL clause must survive: " + sql);
        assertTrue(sql.contains("DEFAULT 'x'"), "the DEFAULT clause must survive: " + sql);
    }

    // --- TrinoRenameColumnGenerator ---

    @Test
    void renameColumnUsesTrinoSyntax() {
        // Not the H2 form the base generator would pick, because TrinoDatabase extends H2Database:
        // "ALTER COLUMN old RENAME TO new" is rejected with
        // "mismatched input 'RENAME'. Expecting: '.', 'DROP', 'SET'".
        assertEquals("ALTER TABLE t RENAME COLUMN txt TO body", renameColumnSql());
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

    private String addColumnSql() {
        return new TrinoAddColumnGenerator().generateSql(addColumn(), db, null)[0].toSql();
    }

    private String renameColumnSql() {
        return new TrinoRenameColumnGenerator().generateSql(renameColumn(), db, null)[0].toSql();
    }

    private AddColumnStatement addColumn() {
        return new AddColumnStatement(null, null, "t", "txt", "VARCHAR(64)", null);
    }

    private RenameColumnStatement renameColumn() {
        return new RenameColumnStatement(null, null, "t", "txt", "body", null);
    }
}