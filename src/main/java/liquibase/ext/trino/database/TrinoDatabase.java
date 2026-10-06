package liquibase.ext.trino.database;

import liquibase.CatalogAndSchema;
import liquibase.database.AbstractJdbcDatabase;
import liquibase.database.DatabaseConnection;
import liquibase.database.core.H2Database;
import liquibase.exception.DatabaseException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal shim over {@link H2Database}. Liquibase selects a {@code Database}
 * implementation by connection product name only (Trino), and
 * {@code liquibase.databaseClass} does not override that choice. H2 is the nearest
 * supported dialect: all its type mappings are valid in Trino and, unlike Postgres,
 * it runs no init SQL on connect. See README for the full rationale.
 */
public class TrinoDatabase extends H2Database {

    public static final String PRODUCT_NAME = "Trino";
    public static final int TRINO_PRIORITY_DATABASE = 510;

    /**
     * Calls {@code AbstractJdbcDatabase.setConnection} directly, bypassing the
     * {@code H2Database} override.
     * <p>
     * Needed because {@code H2Database.setConnection()} issues {@code SELECT SCHEMA()} before
     * calling {@code super} to cache the connection schema. Trino has no {@code schema()}
     * function (it is {@code current_schema}), so the query fails and every connect logs
     * {@code "Could not read current schema name: Function 'schema' not registered"}.
     * <p>
     * There is no other way around it: {@code AbstractJdbcDatabase} keeps {@code connection}
     * private, and Java cannot reach a grandparent method with a plain {@code super}. It must
     * be {@code findSpecial} rather than {@code Method.invoke}: the latter is a virtual call
     * and would land in {@code H2Database} again, recursing forever.
     */
    private static final MethodHandle ABSTRACT_SET_CONNECTION = abstractSetConnection();

    public TrinoDatabase() {
        super.setCurrentDateTimeFunction("CURRENT_TIMESTAMP");
        // Trino stores unquoted identifiers in lower case, H2 defaults to upper case.
        super.unquotedObjectsAreUppercased = false;
    }

    @Override
    public void setConnection(DatabaseConnection conn) {
        try {
            ABSTRACT_SET_CONNECTION.bindTo(this).invokeWithArguments(conn);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MethodHandle abstractSetConnection() {
        try {
            // privateLookupIn is required: findSpecial needs private access to specialCaller,
            // not to the current class.
            return MethodHandles.privateLookupIn(H2Database.class, MethodHandles.lookup())
                    .findSpecial(
                            AbstractJdbcDatabase.class,
                            "setConnection",
                            MethodType.methodType(void.class, DatabaseConnection.class),
                            H2Database.class);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public CatalogAndSchema.CatalogAndSchemaCase getSchemaAndCatalogCase() {
        // Trino stores unquoted identifiers in lower case and its metadata filters are
        // case-sensitive, so H2's UPPER_CASE default made system.jdbc.tables miss the schema
        // and Liquibase retry the CREATE.
        return CatalogAndSchema.CatalogAndSchemaCase.LOWER_CASE;
    }

    @Override
    public String getShortName() {
        return "trino";
    }

    @Override
    protected String getDefaultDatabaseProductName() {
        return PRODUCT_NAME;
    }

    @Override
    public Integer getDefaultPort() {
        return 443;
    }

    @Override
    public int getPriority() {
        return TRINO_PRIORITY_DATABASE;
    }

    @Override
    public String getCurrentDateTimeFunction() {
        return "CURRENT_TIMESTAMP";
    }

    @Override
    public boolean isCaseSensitive() {
        // Trino JDBC reports metadata in lower case.
        return false;
    }

    @Override
    protected Set<String> getReservedWords() {
        // H2Database reaches this same set, but only after getDatabaseMajorVersion(), which the
        // Trino driver answers by running "SELECT version()". isReservedWord() asks for the set
        // once per escaped identifier, so that costs one round-trip per column. The set H2 would
        // have chosen does not depend on the cluster: it is V2 whenever the major version is >= 2,
        // and Trino reports 464. Return it directly instead of deriving it from a query.
        return V2_RESERVED_WORDS;
    }

    @Override
    public String escapeColumnName(String catalogName, String schemaName, String tableName, String columnName) {
        String escaped = super.escapeColumnName(catalogName, schemaName, tableName, columnName);
        return escaped == null ? null : escaped.toLowerCase(Locale.US);
    }

    @Override
    public String escapeColumnName(String catalogName, String schemaName, String tableName, String columnName, boolean quoteNamesThatMayBeFunctions) {
        String escaped = super.escapeColumnName(catalogName, schemaName, tableName, columnName, quoteNamesThatMayBeFunctions);
        return escaped == null ? null : escaped.toLowerCase(Locale.US);
    }

    @Override
    public boolean isCorrectDatabaseImplementation(DatabaseConnection conn) throws DatabaseException {
        String productName = conn.getDatabaseProductName();
        return productName != null && PRODUCT_NAME.trim().equalsIgnoreCase(productName.trim());
    }

    @Override
    public String getDefaultDriver(String url) {
        if (url.startsWith("jdbc:trino")) {
            return "io.trino.jdbc.TrinoDriver";
        }
        return null;
    }

    @Override
    public boolean supportsSequences() {
        return false;
    }

    @Override
    public boolean supportsDDLInTransaction() {
        // Trino runs DDL outside transactions; this also keeps Liquibase from
        // calling setAutoCommit(false).
        return false;
    }

    @Override
    public void setAutoCommit(boolean b) throws DatabaseException {
        // The Trino JDBC driver does not support setAutoCommit(false).
    }
}