package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;

/**
 * A connection whose every {@code SHOW CREATE} fails, in either of the two shapes a real failure
 * arrives in.
 *
 * <p>It extends {@link WorkingTrinoDatabase} so that it holds a statement too: the object is
 * readable in every other respect, and the fetcher's verdict has to be about the statement alone.
 * That is why the two live together.
 *
 * <p>{@link FailingTrinoDatabase} is the other way round — it fails one named object and leaves the
 * rest real, for the integration tests that need the rest of a schema to survive.
 */
class RejectingTrinoDatabase extends WorkingTrinoDatabase {

    private final boolean unchecked;

    private RejectingTrinoDatabase(boolean unchecked) {
        this.unchecked = unchecked;
    }

    /** A read that fails the ordinary way, with a checked {@link DatabaseException}. */
    static RejectingTrinoDatabase checked() {
        return new RejectingTrinoDatabase(false);
    }

    /**
     * A read that fails without arriving as Liquibase's own checked exception.
     *
     * <p>Thrown directly rather than wrapped: wrapping it would hide the very shape under test.
     * Acquiring the executor alone throws {@link UnexpectedLiquibaseException} when it has been shut
     * down, and the JDBC type conversion layer throws bare {@code RuntimeException}s on a type it
     * cannot map.
     */
    static RejectingTrinoDatabase unchecked() {
        return new RejectingTrinoDatabase(true);
    }

    @Override
    public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
        throw fail();
    }

    @Override
    public String getViewDdl(CatalogAndSchema schema, String viewName) throws DatabaseException {
        throw fail();
    }

    private DatabaseException fail() {
        if (unchecked) {
            throw new UnexpectedLiquibaseException("executor is shut down");
        }
        return new DatabaseException("SHOW CREATE failed");
    }
}