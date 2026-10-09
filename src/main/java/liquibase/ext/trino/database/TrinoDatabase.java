package liquibase.ext.trino.database;

import liquibase.CatalogAndSchema;
import liquibase.Scope;
import liquibase.database.AbstractJdbcDatabase;
import liquibase.database.DatabaseConnection;
import liquibase.database.jvm.JdbcConnection;
import liquibase.database.core.H2Database;
import liquibase.exception.DatabaseException;
import liquibase.executor.ExecutorService;
import liquibase.statement.core.RawSqlStatement;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.ForeignKey;
import liquibase.structure.core.Index;
import liquibase.structure.core.PrimaryKey;
import liquibase.structure.core.Table;
import liquibase.structure.core.UniqueConstraint;
import liquibase.structure.core.View;
import liquibase.util.StringUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

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
     * Attribute the verbatim {@code CREATE} statement is stored under on a snapshotted table or
     * view: written by the snapshot generators, read by {@code TrinoDdlChangeGenerator}.
     */
    public static final String DDL_ATTRIBUTE = "trino.ddl";

    /** The two fixed parts of the {@code <catalog>.information_schema.views} reference that
     * {@link #qualify} builds for the view-definition read. */
    private static final String INFORMATION_SCHEMA = "information_schema";
    private static final String VIEWS = "views";

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
     * <p>
     * The connection is wrapped in {@link TrinoJdbcConnection} on the way in, so that callers
     * holding the {@code Database}'s connection — the Liquibase Test Harness among them —
     * cannot switch Trino into a transaction. See that class for why.
     */
    private static final MethodHandle ABSTRACT_SET_CONNECTION = abstractSetConnection();

    public TrinoDatabase() {
        super.setCurrentDateTimeFunction("CURRENT_TIMESTAMP");
        // Trino stores unquoted identifiers in lower case, H2 defaults to upper case.
        super.unquotedObjectsAreUppercased = false;
    }

    @Override
    public void setConnection(DatabaseConnection conn) {
        DatabaseConnection effective =
                conn instanceof JdbcConnection && !(conn instanceof TrinoJdbcConnection)
                        ? new TrinoJdbcConnection((JdbcConnection) conn)
                        : conn;
        try {
            ABSTRACT_SET_CONNECTION.bindTo(this).invokeWithArguments(effective);
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

    /**
     * Declares the constraint and index types this plugin will not read or generate.
     * <p>
     * The Trino JDBC driver cannot answer for them: {@code getIndexInfo} throws
     * {@code SQLFeatureNotSupportedException("indexes not supported")}, while
     * {@code getPrimaryKeys} and {@code getImportedKeys} always return an empty result set
     * ({@code ... WHERE false}). Snapshot generators all guard on this flag before touching
     * JDBC metadata, so returning false here is what keeps {@code snapshot} from dying on the
     * first table and keeps {@code generate-changelog} from inventing constraints.
     */
    @Override
    public boolean supports(Class<? extends DatabaseObject> object) {
        if (Index.class.isAssignableFrom(object)
                || PrimaryKey.class.isAssignableFrom(object)
                || ForeignKey.class.isAssignableFrom(object)
                || UniqueConstraint.class.isAssignableFrom(object)) {
            return false;
        }
        return super.supports(object);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The URL may carry no catalog, and {@code TrinoConnection.getCatalog()} then returns null
     * — but Trino needs all three name parts. The session's own catalog is the only sensible
     * fallback; Liquibase caches the result in {@code defaultCatalogName} itself.
     */
    @Override
    protected String getConnectionCatalogName() throws DatabaseException {
        String fromUrl = super.getConnectionCatalogName();
        return isBlank(fromUrl) ? sessionSetting("current_catalog") : fromUrl;
    }

    /**
     * {@inheritDoc}
     * <p>
     * {@link H2Database} answers this from a field initialized to the literal {@code "PUBLIC"},
     * and since this class bypasses {@code H2Database.setConnection} (see
     * {@link #setConnection}) nothing ever overwrites it. Calling {@code super} would therefore
     * always return {@code "PUBLIC"}, which Trino has no such schema for: every snapshot looked
     * for {@code iceberg_catalog.public}, found nothing, and {@code snapshot} and
     * {@code generate-changelog} returned the catalog and the schema and nothing else.
     * <p>
     * {@code super} is unusable anyway: it would run {@code CALL current_schema}, which Trino
     * has no such statement for. The session's own schema is read directly instead, and Trino
     * reports an empty string rather than null when the session has none — see
     * {@link #sessionSetting(String)}.
     */
    @Override
    protected String getConnectionSchemaName() {
        return sessionSetting("current_schema");
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
        // Trino's HTTP endpoint listens on 8080;443 (HTTPS) was a mistake.
        return 8080;
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

    /**
     * The view body — the {@code SELECT} and nothing else, as the base class contract requires.
     * <p>
     * Read from {@code information_schema.views} rather than through the base implementation,
     * which resolves it through a SQL generator every dialect overrides differently, and unlike
     * {@link H2Database#getViewDefinition} this makes no assumption about the text: that code
     * calls {@code definition.startsWith("SELECT")} on a possibly null value.
     */
    @Override
    public String getViewDefinition(CatalogAndSchema schema, String name) throws DatabaseException {
        CatalogAndSchema target = schema.customize(this);
        return queryForString("SELECT view_definition FROM "
                + qualify(target.getCatalogName(), INFORMATION_SCHEMA, VIEWS)
                + " WHERE table_schema = '" + escapeStringForDatabase(target.getSchemaName())
                + "' AND table_name = '" + escapeStringForDatabase(name) + "'");
    }

    /**
     * The complete {@code CREATE TABLE} statement as Trino prints it, with no interpretation.
     * <p>
     * This is what {@code snapshot} records and what {@code generate-changelog} replays, so it has
     * to be verbatim: only {@code SHOW CREATE TABLE} carries the connector properties
     * ({@code partitioning}, {@code location} on S3, {@code format}, {@code format_version}) and
     * the table comment. {@code information_schema} exposes none of them.
     *
     * @return the DDL, or null when the table does not exist
     */
    public String getTableDefinition(CatalogAndSchema schema, String tableName) throws DatabaseException {
        return showCreate("TABLE", schema, tableName);
    }

    /**
     * The complete {@code CREATE VIEW} statement as Trino prints it, comment and security mode
     * included.
     * <p>
     * Liquibase's own {@code <createView>} change can carry neither: {@code remarks} is emitted
     * only for a hardcoded list of database classes in {@code CreateViewChange} that Trino is not
     * part of, and {@code SECURITY DEFINER} has no field at all.
     *
     * @return the DDL, or null when the view does not exist
     */
    public String getViewDdl(CatalogAndSchema schema, String viewName) throws DatabaseException {
        return showCreate("VIEW", schema, viewName);
    }

    /**
     * Runs {@code SHOW CREATE <type>}, reporting a missing object as "not found" rather than as a
     * failure.
     * <p>
     * Trino answers a missing table with an error ({@code Table ... does not exist}) instead of the
     * empty result {@code DatabaseMetaData.getTables} would give, so a snapshot of a schema that
     * has just been altered would otherwise abort on the first object that vanished in between.
     */
    private String showCreate(String type, CatalogAndSchema schema, String name) throws DatabaseException {
        CatalogAndSchema target = schema.customize(this);
        String qualified = qualify(target.getCatalogName(), target.getSchemaName(), name);
        try {
            return queryForString("SHOW CREATE " + type + " " + qualified);
        } catch (DatabaseException e) {
            if (isMissingObject(e)) {
                return null;
            }
            throw e;
        }
    }

    /**
     * Builds a fully qualified name — used by {@code SHOW CREATE} and by
     * {@link #getViewDefinition} for the {@code information_schema.views} read — quoting only
     * where Trino requires it.
     * <p>
     * Deliberately not {@code escapeObjectName}: that method depends on the connection's quoting
     * strategy, and under {@code QUOTE_ALL_OBJECTS} — which the changelog writer's reference
     * database uses — it quotes every part of the name. Trino echoes the spelling it was given, so
     * the statement recorded here would come back as {@code "schema"."view"} instead of
     * {@code schema.view}. Same object, different text, and the changelog would no longer match what
     * an unqualified connection produces.
     * <p>
     * Trino folds unquoted identifiers to lower case, so a name that is already lower case and free
     * of special characters is passed through as-is. Anything else is quoted, which preserves the
     * spelling for names that genuinely need it.
     */
    private String qualify(String catalog, String schema, String name) {
        StringBuilder qualified = new StringBuilder();
        if (catalog != null) {
            qualified.append(identifier(catalog)).append('.');
        }
        if (schema != null) {
            qualified.append(identifier(schema)).append('.');
        }
        return qualified.append(identifier(name)).toString();
    }

    /** An identifier as Trino will echo it back: bare when safe to leave unquoted, else quoted. */
    private static String identifier(String name) {
        if (name == null) {
            return null;
        }
        if (SAFE_IDENTIFIER.matcher(name).matches()) {
            return name;
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** A name Trino stores unquoted: already lower case, no quoting or whitespace needed. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private static boolean isMissingObject(DatabaseException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.contains("does not exist")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String queryForString(String sql) throws DatabaseException {
        return Scope.getCurrentScope().getSingleton(ExecutorService.class)
                .getExecutor("jdbc", this)
                .queryForObject(new RawSqlStatement(sql), String.class);
    }

    /**
     * Reads a session variable such as {@code current_catalog}; null when the session has none.
     * <p>
     * An unset session variable comes back as SQL NULL, and {@code trimToNull} covers the empty
     * string alongside it. Note what the NULL case costs the caller: Trino reports no schema at
     * all for a URL that names only a catalog, so {@link #getConnectionSchemaName()} returns null,
     * {@link #getDefaultSchema()} then carries a null schema, and {@code snapshot} /
     * {@code generate-changelog} find no schema to work with and return the catalog alone. That is
     * why those commands need the schema named — see README, "Snapshot and generate-changelog".
     * A failure is logged and swallowed because the caller,
     * {@code getConnectionSchemaName()}, cannot declare a checked exception: losing the fallback
     * only means the user has to pass the schema explicitly.
     */
    private String sessionSetting(String name) {
        if (!(getConnection() instanceof JdbcConnection)) {
            return null;
        }
        try (java.sql.Statement statement =
                     ((JdbcConnection) getConnection()).getUnderlyingConnection().createStatement();
             ResultSet rs = statement.executeQuery("SELECT " + name)) {
            return rs.next() ? StringUtil.trimToNull(rs.getString(1)) : null;
        } catch (SQLException e) {
            Scope.getCurrentScope().getLog(getClass()).warning("Could not read " + name + " from Trino", e);
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void setAutoCommit(boolean b) throws DatabaseException {
        // The Trino JDBC driver does not support setAutoCommit(false).
    }
}