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

    @Override
    protected DatabaseObject snapshotObject(DatabaseObject example, DatabaseSnapshot snapshot)
            throws DatabaseException {
        DatabaseObject view = super.snapshotObject(example, snapshot);
        if (view instanceof View) {
            String ddl = TrinoDdlFetcher.ddlFor(view, snapshot.getDatabase());
            if (ddl != null) {
                view.setAttribute(TrinoDdlFetcher.DDL_ATTRIBUTE, ddl);
            }
        }
        return view;
    }
}