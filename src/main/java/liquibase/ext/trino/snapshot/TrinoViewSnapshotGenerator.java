package liquibase.ext.trino.snapshot;

import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotGenerator;
import liquibase.snapshot.jvm.ViewSnapshotGenerator;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.View;

/**
 * Records the verbatim {@code CREATE VIEW} statement on each snapshotted view.
 * <p>
 * The base class fills {@code View.definition} from {@code information_schema}, which is the bare
 * {@code SELECT} — no {@code COMMENT}, no {@code SECURITY DEFINER}. Liquibase's own
 * {@code <createView>} change can express neither: {@code remarks} is emitted only for a fixed list
 * of database classes that Trino is not part of, and security mode has no field at all. The
 * attribute {@link TrinoDdlFetcher#DDL_ATTRIBUTE} carries the whole statement instead.
 */
public class TrinoViewSnapshotGenerator extends ViewSnapshotGenerator {

    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        if (!(database instanceof TrinoDatabase)) {
            return PRIORITY_NONE;
        }
        if (!View.class.isAssignableFrom(objectType)) {
            // Must keep the inherited priority for the types this generator adds itself to,
            // otherwise the addTo pass that attaches views to their schema stops running.
            // See TrinoSchemaSnapshotGenerator.
            return super.getPriority(objectType, database);
        }
        // The slot ViewSnapshotGenerator itself occupies.
        return PRIORITY_DEFAULT;
    }

    @Override
    public Class<? extends SnapshotGenerator>[] replaces() {
        return new Class[]{ViewSnapshotGenerator.class};
    }

    /**
 * Snapshots the view, or nothing at all if it cannot be snapshotted faithfully.
 * <p>
 * A failure is confined to the view it happened on. {@code ViewSnapshotGenerator} reads the body
 * through {@link liquibase.ext.trino.database.TrinoDatabase#getViewDefinition}, which throws, and
 * {@code SnapshotGeneratorChain} lets that escape and aborts the command over one view.
 * <p>
 * The second decision is delegated rather than repeated: {@link TrinoDdlFetcher#attachVerbatimDdl}
 * reads the verbatim statement and reports whether the view can be represented without it. The same
 * call serves a table, and the same rule applies to both — a view whose {@code COMMENT} or
 * {@code SECURITY DEFINER} did not survive into {@code CreateViewChange} is not a degraded copy of
 * the original either.
 */
    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot)
            throws DatabaseException {
        Database database = snapshot.getDatabase();
        DatabaseObject view = TrinoDdlFetcher.confinedToObject(example, database,
                "could not be snapshotted", () -> super.snapshotObject(example, snapshot));
        if (view instanceof View && !TrinoDdlFetcher.attachVerbatimDdl(view, database)) {
            return null;
        }
        return view;
    }
}