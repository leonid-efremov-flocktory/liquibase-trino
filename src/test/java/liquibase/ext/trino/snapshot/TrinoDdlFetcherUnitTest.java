package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.database.core.H2Database;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.structure.core.Table;
import liquibase.structure.core.View;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link TrinoDdlFetcher} behaves when the statement cannot be read.
 * <p>
 * The fetch is the one place where a relation is touched twice — once through JDBC metadata, once
 * through {@code SHOW CREATE} — so it is also the place a single bad object takes the whole command
 * down. Every case here is a failure mode the fetcher has to absorb: the command still has to
 * produce a changelog for the objects that did answer.
 * <p>
 * {@code TrinoDdlFetcher} is package-private, so this test sits beside it. Everything that needs a
 * live connection is in TrinoSnapshotFailureIntegrationTest.
 */
class TrinoDdlFetcherUnitTest {

    private static final CatalogAndSchema WHERE = new CatalogAndSchema("iceberg_catalog", "dev_test_schema");

    // --- A failure while reading the statement ---

    /**
     * The ordinary case: the query itself failed.
     *
     * <p>{@code false} means the caller must leave the object out of the snapshot. That is the whole
     * point of the method returning a verdict rather than a statement: a null would be
     * indistinguishable from an object that simply has no statement, and the caller would then keep
     * the object and let core reconstruct a {@code CREATE TABLE} from partial metadata.
     */
    @Test
    void tableDdlFailureSaysTheObjectCannotBeKept() {
        assertFalse(TrinoDdlFetcher.attachVerbatimDdl(table(), new FailingTrinoDatabase("table")),
                "a table whose statement cannot be read must not be kept without it");
    }

    /** The same rule, and it is literally the same method: no view-specific behaviour to drift. */
    @Test
    void viewDdlFailureSaysTheObjectCannotBeKept() {
        assertFalse(TrinoDdlFetcher.attachVerbatimDdl(view(), new FailingTrinoDatabase("view")),
                "a view is treated exactly like a table here");
    }

    /**
     * Not every failure arrives as a {@link DatabaseException}. Acquiring the executor alone throws
     * {@link UnexpectedLiquibaseException} when it has been shut down, and the JDBC type conversion
     * layer throws bare {@code RuntimeException}s — a schema whose columns report a type Trino
     * cannot map to a {@code java.sql} type hits exactly that. Catching only the checked exception
     * would leave the command dying on one object.
     */
    @Test
    void runtimeFailureSaysTheObjectCannotBeKept() {
        assertFalse(TrinoDdlFetcher.attachVerbatimDdl(table(), new FailingTrinoDatabase("runtime")),
                "an unchecked failure must be handled like a checked one; the catch is Exception, "
                        + "not DatabaseException");
    }

    /**
     * Without a Trino connection there is nothing to read, and that is not a failure — so the
     * object stays. Verified separately that the failure warning was logged.
     */
    @Test
    void nonTrinoDatabaseKeepsTheObject() {
        assertTrue(TrinoDdlFetcher.attachVerbatimDdl(table(), new OtherDatabase()),
                "the fetch must stay inert for a database that is not Trino");
    }

    // --- Naming what was skipped ---

    /**
     * A skipped object has to be identifiable in the log. The warning says
     * {@code Skipping table catalog.schema.name: ...}, and a name that came out as {@code null} or
     * without its catalog would send the reader looking in the wrong place.
     */
    @Test
    void describeNamesTheQualifiedObject() {
        assertEquals("table iceberg_catalog.dev_test_schema.broken",
                TrinoDdlFetcher.describe(table(), new TrinoDatabase()),
                "a skipped table must be named the way a user would write it");
    }

    @Test
    void describeSaysViewForAView() {
        assertEquals("view iceberg_catalog.dev_test_schema.broken",
                TrinoDdlFetcher.describe(view(), new TrinoDatabase()),
                "a skipped view must not be reported as a table");
    }

    /**
     * An object with no schema of its own falls back to the connection's. Without that, the message
     * would read {@code table null.broken}, which is worse than useless.
     */
    @Test
    void describeFallsBackToTheConnectionSchema() {
        TrinoDatabase db = new TrinoDatabase();
        db.setDefaultCatalogName("iceberg_catalog");
        db.setDefaultSchemaName("dev_test_schema");

        assertEquals("table iceberg_catalog.dev_test_schema.broken",
                TrinoDdlFetcher.describe(tableWithoutSchema(), db),
                "an object without its own schema must be described in the connection's schema");
    }

    /** The catalog is dropped when there is none, rather than printed as a leading dot. */
    @Test
    void describeOmitsAnAbsentCatalog() {
        TrinoDatabase db = new TrinoDatabase();
        db.setDefaultSchemaName("dev_test_schema");

        assertEquals("table dev_test_schema.broken",
                TrinoDdlFetcher.describe(tableWithoutSchema(), db));
    }

    /** No connection and no schema at all: the bare name is still better than a null. */
    @Test
    void describeFallsBackToTheBareName() {
        assertEquals("table broken", TrinoDdlFetcher.describe(tableWithoutSchema(), new TrinoDatabase()),
                "with nothing to qualify the name by, the name itself must still be there");
    }

    /**
     * The happy path, so the failure cases above cannot pass by the fetcher quietly returning null
     * for everything.
     */
    @Test
    void aReadableTableKeepsItsStatementWithoutTheSemicolon() {
        Table table = table();

        assertTrue(TrinoDdlFetcher.attachVerbatimDdl(table, new WorkingTrinoDatabase()));
        assertEquals("CREATE TABLE iceberg_catalog.dev_test_schema.broken (id integer)",
                table.getAttribute(TrinoDatabase.DDL_ATTRIBUTE, String.class),
                "a readable table must keep its statement, minus the trailing semicolon");
    }

    @Test
    void aStatementWithoutASemicolonIsKeptAsIs() {
        WorkingTrinoDatabase db = new WorkingTrinoDatabase();
        db.ddl = "CREATE TABLE iceberg_catalog.dev_test_schema.ok (id integer)";
        Table table = table();

        assertTrue(TrinoDdlFetcher.attachVerbatimDdl(table, db));
        assertEquals(db.ddl, table.getAttribute(TrinoDatabase.DDL_ATTRIBUTE, String.class),
                "a statement without a trailing semicolon must not lose its last character");
    }

    @Test
    void aMissingObjectIsNotAFailure() {
        WorkingTrinoDatabase db = new WorkingTrinoDatabase();
        db.ddl = null;
        Table table = table();

        assertTrue(TrinoDdlFetcher.attachVerbatimDdl(table, db),
                "an object that no longer exists server-side has no statement, which is not a "
                        + "failure: the object stays, exactly as it did before this change");
        assertNull(table.getAttribute(TrinoDatabase.DDL_ATTRIBUTE, String.class),
                "and nothing is recorded for it");
    }

    // --- Fixtures ---

    private static Table table() {
        return new Table(WHERE.getCatalogName(), WHERE.getSchemaName(), "broken");
    }

    /** A table that carries no schema of its own, so the connection's has to be used. */
    private static Table tableWithoutSchema() {
        Table table = new Table();
        table.setName("broken");
        return table;
    }

    private static View view() {
        return new View(WHERE.getCatalogName(), WHERE.getSchemaName(), "broken");
    }

    /** Answers a fixed statement, or none when it is null. */
    private static class WorkingTrinoDatabase extends TrinoDatabase {
        String ddl = "CREATE TABLE iceberg_catalog.dev_test_schema.broken (id integer);";

        @Override
        public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
            return ddl;
        }

        @Override
        public String getViewDdl(CatalogAndSchema schema, String viewName) throws DatabaseException {
            return ddl;
        }
    }

    /**
     * Fails the way a real read fails, in each of the shapes a real read can fail in.
     *
     * @param failure {@code "table"} or {@code "view"} to fail {@code SHOW CREATE} with a
     *                {@link DatabaseException}, {@code "runtime"} to fail it with an
     *                {@link UnexpectedLiquibaseException}
     */
    private static class FailingTrinoDatabase extends WorkingTrinoDatabase {

        private final String failure;

        private FailingTrinoDatabase(String failure) {
            this.failure = failure;
        }

        @Override
        public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
            return fail();
        }

        @Override
        public String getViewDdl(CatalogAndSchema schema, String viewName) throws DatabaseException {
            return fail();
        }

        private String fail() throws DatabaseException {
            if ("runtime".equals(failure)) {
                // What ExecutorService.getExecutor throws when it has been shut down, and what the
                // JDBC type conversion throws on a type it cannot map.
                throw new UnexpectedLiquibaseException("executor is shut down");
            }
            throw new DatabaseException("SHOW CREATE failed");
        }
    }

    /**
     * Stands in for any other dialect, to prove the fetch keys off the database type and not off
     * the absence of a connection. A subclass of TrinoDatabase would not do here: it would still
     * pass the {@code instanceof} check.
     */
    private static class OtherDatabase extends H2Database {
    }
}