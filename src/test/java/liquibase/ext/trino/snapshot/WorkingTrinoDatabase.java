package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.exception.DatabaseException;
import liquibase.ext.trino.database.TrinoDatabase;

/**
 * A Trino connection that answers a fixed statement, without needing a server.
 *
 * <p>{@code TrinoDdlFetcher} asks a Database for {@code SHOW CREATE}, and without a connection there
 * is nothing to ask. This subclass answers whatever {@link #ddl} holds — or nothing at all, when it
 * is null, which is what an object that no longer exists server-side looks like.
 *
 * <p>It sits next to {@link FailingTrinoDatabase} because the two are used together: the fetcher's
 * verdict is only meaningful next to the case where it succeeds, and until both lived here this class
 * was nested in {@code TrinoDdlFetcherUnitTest} while a same-named, differently-behaving double sat
 * beside it in the package.
 */
class WorkingTrinoDatabase extends TrinoDatabase {

    /** The statement every read returns. Semicolon-terminated, as the server would send it. */
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