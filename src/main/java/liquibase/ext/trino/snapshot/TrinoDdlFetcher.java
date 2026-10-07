package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
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
     * The {@code CREATE} statement for a snapshotted table or view.
     *
     * @return the statement with its trailing semicolon removed, or null when it cannot be read;
     *         a null here only costs the DDL change in a generated changelog, the rest of the
     *         snapshot stays intact
     */
    static String ddlFor(DatabaseObject object, Database database) {
        if (!(database instanceof TrinoDatabase)) {
            return null;
        }
        TrinoDatabase trino = (TrinoDatabase) database;
        CatalogAndSchema schema = catalogAndSchema(object, database);
        if (schema == null) {
            return null;
        }
        try {
            String ddl = object instanceof View
                    ? trino.getViewDdl(schema, object.getName())
                    : trino.getTableDefinition(schema, object.getName());
            return stripTrailingSemicolon(ddl);
        } catch (DatabaseException e) {
            // A relation that cannot be read must not fail the whole snapshot: the object is still
            // there, only its verbatim form is missing.
            return null;
        }
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