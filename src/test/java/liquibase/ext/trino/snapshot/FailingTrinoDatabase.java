package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.ext.trino.database.TrinoDatabase;

import java.util.Locale;

/**
 * A Trino connection in which reading one object's DDL fails.
 *
 * <p>The failure is injected in the dialect rather than faked in Trino, because the interesting
 * failures are rare by nature: they need a connection that drops mid-walk, or a catalog that stops
 * answering. Reproducing that against a live server would either be flaky or would stand up a second
 * fixture for one test. Overriding the read the plugin makes is the same thing from the generator's
 * point of view — it sees a method that throws, and never learns where from.
 *
 * <p>Everything else stays real: the schema, the other objects, the metadata walk. That is what
 * makes the surviving snapshot worth asserting on.
 *
 * <p>Scope is deliberately limited to the DDL reads. The metadata walk cannot be failed per object
 * from here: the per-object {@code getTables} goes through {@code ResultSetCache}, and whether it
 * reaches the driver or is served from an earlier bulk read depends on a call counter in core
 * ({@code shouldBulkSelect} compares {@code getTimesSingleQueried(key) >= 3}), so which objects are
 * reachable is not something a test controls. A schema-level failure is out of scope on purpose.
 * {@code TrinoTableSnapshotGeneratorUnitTest} covers the generator's own guard directly, where the
 * failure can be placed without going through the cache.
 */
class FailingTrinoDatabase extends TrinoDatabase {

    /** Matches every object: reading any DDL fails. */
    static final String ANY_OBJECT = "*";

    private final String failingObject;
    private final RuntimeException uncheckedFailure;

    /**
     * @param failingObject    object name whose DDL reads fail, or {@link #ANY_OBJECT}
     * @param uncheckedFailure thrown instead of a {@link DatabaseException}, to cover the unchecked
     *                         path: acquiring the executor throws {@code UnexpectedLiquibaseException}
     *                         when it has been shut down, and the JDBC type conversion throws bare
     *                         {@code RuntimeException}s on a type it cannot map
     */
    FailingTrinoDatabase(String failingObject, RuntimeException uncheckedFailure) {
        this.failingObject = failingObject.toLowerCase(Locale.ROOT);
        this.uncheckedFailure = uncheckedFailure;
    }

    /** A read that fails the ordinary way, with a checked {@link DatabaseException}. */
    static FailingTrinoDatabase on(String objectName) {
        return new FailingTrinoDatabase(objectName, null);
    }

    /** A read that fails without arriving as Liquibase's own checked exception. */
    static FailingTrinoDatabase uncheckedOn(String objectName) {
        return new FailingTrinoDatabase(objectName,
                new UnexpectedLiquibaseException("executor is shut down"));
    }

    private boolean isTarget(String name) {
        return ANY_OBJECT.equals(failingObject)
                || (name != null && failingObject.equalsIgnoreCase(name));
    }

    /**
     * The unchecked failure when there is one, otherwise the given checked one.
     * <p>
     * Called from a method that may only declare {@link DatabaseException}, so the unchecked case is
     * thrown directly rather than wrapped — wrapping it would hide the very shape under test.
     */
    private DatabaseException checkedOrUnchecked(String what) {
        if (uncheckedFailure != null) {
            throw uncheckedFailure;
        }
        return new DatabaseException("injected failure " + what);
    }

    // --- The three DDL reads the snapshot makes ---

    @Override
    public String getViewDefinition(CatalogAndSchema schema, String name) throws DatabaseException {
        if (isTarget(name)) {
            throw checkedOrUnchecked("reading view " + name);
        }
        return super.getViewDefinition(schema, name);
    }

    @Override
    public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
        if (isTarget(tableName)) {
            throw checkedOrUnchecked("reading table " + tableName);
        }
        return super.getTableDefinition(schema, tableName);
    }

    @Override
    public String getViewDdl(CatalogAndSchema schema, String viewName) throws DatabaseException {
        if (isTarget(viewName)) {
            throw checkedOrUnchecked("reading view " + viewName);
        }
        return super.getViewDdl(schema, viewName);
    }
}