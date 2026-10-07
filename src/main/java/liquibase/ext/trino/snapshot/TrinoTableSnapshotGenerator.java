package liquibase.ext.trino.snapshot;

import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.snapshot.jvm.TableSnapshotGenerator;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Table;

/**
 * Records the verbatim {@code CREATE TABLE} statement on each snapshotted table.
 * <p>
 * Everything else is left to {@link TableSnapshotGenerator}: relations are still found through
 * JDBC metadata and their columns are still read column by column. What is added is the attribute
 * {@link TrinoDdlFetcher#DDL_ATTRIBUTE}, because the connector properties and the table comment
 * exist only in {@code SHOW CREATE TABLE} — {@code information_schema} reports neither, and the
 * column types it does report are lossy ({@code varchar} with no length comes back as
 * {@code character_maximum_length = 2147483647}).
 */
public class TrinoTableSnapshotGenerator extends TableSnapshotGenerator {

    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        if (!(database instanceof TrinoDatabase)) {
            // Not in the chain, so replaces() is never consulted and the core generator stands.
            return PRIORITY_NONE;
        }
        if (!Table.class.isAssignableFrom(objectType)) {
            // For every other type the inherited value is the correct one: this generator only
            // stands in for TableSnapshotGenerator, and must keep its place in the chain for the
            // types it adds itself to. Handing out PRIORITY_DEFAULT here would demote it below
            // the PRIORITY_ADDITIONAL generators, and their addTo pass would stop running —
            // the same failure as in TrinoSchemaSnapshotGenerator.
            return super.getPriority(objectType, database);
        }
        // The slot TableSnapshotGenerator itself occupies. Anything higher would put this
        // generator ahead of the addTo generators, which would starve them of chain responses.
        return PRIORITY_DEFAULT;
    }

    @Override
    public Class<? extends SnapshotGenerator>[] replaces() {
        return new Class[]{TableSnapshotGenerator.class};
    }

/**
     * Snapshots the table, or nothing at all if it cannot be snapshotted faithfully.
     * <p>
     * A failure is confined to the object it happened on. The base class throws on a connection
     * that dropped mid-run, on a type it cannot map, on metadata it cannot read — and
     * {@code SnapshotGeneratorChain} lets all of that escape, aborting the command over one table out
     * of hundreds.
     * <p>
     * Both decisions are delegated. {@link TrinoDdlFetcher#attachVerbatimDdl} records the verbatim
     * statement and reports whether the table can be represented without it — and this is the only
     * place left that can drop the table, because it was already found through the cached metadata
     * by the time its statement is read.
     */
    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot)
            throws DatabaseException {
        Database database = snapshot.getDatabase();
        DatabaseObject table = TrinoDdlFetcher.confinedToObject(example, database,
                "could not be snapshotted", () -> super.snapshotObject(example, snapshot));
        if (table instanceof Table && !TrinoDdlFetcher.attachVerbatimDdl(table, database)) {
            return null;
        }
        return table;
    }
}