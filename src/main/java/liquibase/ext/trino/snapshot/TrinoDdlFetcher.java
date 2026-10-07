package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.Scope;
import liquibase.database.Database;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Schema;
import liquibase.structure.core.View;

/**
 * Reads the verbatim {@code CREATE} statement of a Trino relation.
 * <p>
 * The snapshot generators need the statement Trino itself prints, not a reconstruction of it:
 * {@code information_schema} carries neither the connector properties
 * ({@code partitioning}, {@code location}, {@code format}, {@code format_version}) nor comments,
 * so anything assembled from it would describe a different table than the one that exists.
 */
final class TrinoDdlFetcher {

    /** Attribute the statement is stored under on the snapshotted relation. */
    static final String DDL_ATTRIBUTE = "trino.ddl";

    private TrinoDdlFetcher() {
    }

    /**
     * A read that may fail with anything — Liquibase's checked exceptions, the executor's own
     * {@link liquibase.exception.UnexpectedLiquibaseException}, or a bare {@code RuntimeException}
     * from a JDBC type conversion.
     */
    @FunctionalInterface
    interface Read<T> {
        T get() throws Exception;
    }

    /**
     * Runs a per-object read, and turns any failure into a warning plus a null.
     *
     * <p>The one place where a failure is confined to the object it happened on, for both object
     * types and for both kinds of read. {@code SnapshotGeneratorChain} does not isolate generators
     * per object: anything a generator throws escapes to the command, so a {@code generate-changelog}
     * that dies on one bad table of two hundred produces no changelog at all and never says which
     * object was at fault. Returning null leaves the object out and lets the rest be recorded.
     *
     * <p>It is a helper rather than a {@code try/catch} in each generator because the rule has to be
     * one rule. Duplicated per generator, the two copies drift, and there is no test that notices a
     * drift between them — only that each still handles a failure.
     *
     * @param example the object to name in the warning, which is not necessarily the one returned
     * @param what    what failed, in the wording a user would use
     * @return whatever {@code read} produced, or null if it failed
     */
    static <T> T confinedToObject(DatabaseObject example, Database database, String what, Read<T> read) {
        try {
            return read.get();
        } catch (Exception e) {
            // Exception rather than DatabaseException because the failure does not have to come
            // from the query. Getting the executor alone throws UnexpectedLiquibaseException when
            // it is shut down, and a type conversion inside the JDBC layer throws bare
            // RuntimeExceptions. Both would otherwise abort the command over a single object.
            warn(example, database, what, e);
            return null;
        }
    }

    /**
     * Records the verbatim {@code CREATE} statement on a snapshotted table or view.
     *
     * <p>The single place where a failed read is turned into an outcome, for both object types. It
     * answers one question — can this object be snapshotted faithfully? — and it answers it the same
     * way for a table and for a view, so neither generator has to repeat the decision or the catch.
     *
     * <p>An object whose statement cannot be read is not kept with degraded metadata. Core would then
     * emit a structural {@code CREATE} from {@code information_schema} instead, and that is not a
     * degraded copy of the object but a different one: a Trino catalog over a shared metastore
     * routinely holds tables of another connector, and one that {@code SHOW CREATE TABLE} refuses to
     * describe needs {@code external_location}, {@code format} and {@code serde}, none of which have
     * a column in {@code information_schema}. A view has the same shape of problem — a comment and a
     * security mode survive in no field of {@code CreateViewChange}. Losing the object is recoverable;
     * creating a different object under its name is not.
     *
     * @return false when the relation must be left out of the snapshot because its statement could
     *         not be read; true otherwise, whether the statement was recorded or there was nothing
     *         to record
     */
    static boolean attachVerbatimDdl(DatabaseObject object, Database database) {
        return confinedToObject(object, database,
                "its verbatim DDL could not be read, and without it the " + describeKind(object)
                        + " cannot be reproduced faithfully, so it is left out of the snapshot",
                () -> {
                    String ddl = readVerbatim(object, database);
                    if (ddl != null) {
                        object.setAttribute(DDL_ATTRIBUTE, ddl);
                    }
                    return Boolean.TRUE;
                }) != null;
    }

    /**
     * The {@code CREATE} statement for a snapshotted table or view.
     *
     * @return the statement with its trailing semicolon removed, or null when there is nothing to
     *         read — not a Trino connection, no schema, or the object no longer exists server-side
     * @throws Exception whatever the read threw; {@link #attachVerbatimDdl} confines it to one object
     */
    private static String readVerbatim(DatabaseObject object, Database database) throws Exception {
        if (!(database instanceof TrinoDatabase)) {
            return null;
        }
        TrinoDatabase trino = (TrinoDatabase) database;
        CatalogAndSchema schema = catalogAndSchema(object, database);
        if (schema == null) {
            return null;
        }
        String ddl = object instanceof View
                ? trino.getViewDdl(schema, object.getName())
                : trino.getTableDefinition(schema, object.getName());
        return stripTrailingSemicolon(ddl);
    }

    private static String describeKind(DatabaseObject object) {
        return object instanceof View ? "the view" : "the table";
    }

    /**
     * Names an object the way a user would, for messages that name what was skipped.
     */
    static String describe(DatabaseObject object, Database database) {
        String kind = object instanceof View ? "view" : "table";
        CatalogAndSchema schema = catalogAndSchema(object, database);
        StringBuilder described = new StringBuilder(kind).append(' ');
        if (schema != null) {
            if (isPresent(schema.getCatalogName())) {
                described.append(schema.getCatalogName()).append('.');
            }
            if (isPresent(schema.getSchemaName())) {
                described.append(schema.getSchemaName()).append('.');
            }
        }
        return described.append(object.getName()).toString();
    }

    /**
     * Reports an object that was left out of the snapshot, and why.
     * <p>
     * A warning and not an error: the command still succeeds and produces a changelog, but the
     * object is missing from it, so silently carrying on would hide a real gap.
     */
    static void warn(DatabaseObject object, Database database, String what, Exception e) {
        Scope.getCurrentScope().getLog(TrinoDdlFetcher.class)
                .warning("Skipping " + describe(object, database) + ": " + what, e);
    }

    private static CatalogAndSchema catalogAndSchema(DatabaseObject object, Database database) {
        Schema schema = object.getSchema();
        if (schema == null) {
            return database.getDefaultSchema();
        }
        String catalog = schema.getCatalogName() != null ? schema.getCatalogName() : database.getDefaultCatalogName();
        String name = schema.getName() != null ? schema.getName() : database.getDefaultSchemaName();
        return new CatalogAndSchema(catalog, name);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isEmpty();
    }

    /**
     * Drops the trailing semicolon.
     * <p>
     * {@code SHOW CREATE} prints one, and the generated changelog appends its own statement
     * separator. Keeping both would leave {@code ;;} in the SQL file.
     */
    private static String stripTrailingSemicolon(String ddl) {
        if (ddl == null) {
            return null;
        }
        String trimmed = ddl.trim();
        return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}