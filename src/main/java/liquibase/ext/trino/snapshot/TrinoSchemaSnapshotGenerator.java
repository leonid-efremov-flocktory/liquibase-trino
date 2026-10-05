package liquibase.ext.trino.snapshot;

import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.compare.DatabaseObjectComparatorFactory;
import liquibase.exception.DatabaseException;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.InvalidExampleException;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.snapshot.jvm.SchemaSnapshotGenerator;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Catalog;
import liquibase.structure.core.Schema;
import liquibase.util.JdbcUtil;

import java.sql.ResultSet;
import java.sql.SQLException;

public class TrinoSchemaSnapshotGenerator extends SchemaSnapshotGenerator {

    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        if (database instanceof TrinoDatabase) {
            return TrinoDatabase.TRINO_PRIORITY_DATABASE;
        }
        return PRIORITY_NONE;
    }

    @Override
    public Class<? extends SnapshotGenerator>[] replaces() {
        return new Class[]{SchemaSnapshotGenerator.class};
    }

    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot)
            throws DatabaseException, InvalidExampleException {
        Database database = snapshot.getDatabase();

        String catalogName = ((Schema) example).getCatalogName();
        String schemaName = example.getName();
        if (catalogName == null) {
            catalogName = database.getDefaultCatalogName();
        }
        if (schemaName == null) {
            schemaName = database.getDefaultSchemaName();
        }
        Schema exampleSchema = new Schema(catalogName, schemaName);

        Schema match = null;
        try (ResultSet schemas = ((JdbcConnection) database.getConnection()).getMetaData().getSchemas()) {
            while (schemas.next()) {
                String tableSchem = JdbcUtil.getValueForColumn(schemas, "TABLE_SCHEM", database);
                String tableCat = JdbcUtil.getValueForColumn(schemas, "TABLE_CATALOG", database);

                if (catalogName != null && tableCat != null && !catalogName.equalsIgnoreCase(tableCat)) {
                    continue;
                }

                Schema schema = new Schema(new Catalog(tableCat), tableSchem);
                if (DatabaseObjectComparatorFactory.getInstance().isSameObject(
                        schema, exampleSchema, snapshot.getSchemaComparisons(), database)) {
                    if (match == null) {
                        match = schema;
                    } else {
                        throw new InvalidExampleException("Found multiple catalog/schemas matching " + catalogName + "." + schemaName);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }

        if (match != null && (match.getName() == null
                || match.getName().equalsIgnoreCase(database.getDefaultSchemaName()))) {
            match.setDefault(true);
        }
        return match;
    }
}
