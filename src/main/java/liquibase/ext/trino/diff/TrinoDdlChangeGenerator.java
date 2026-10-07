package liquibase.ext.trino.diff;

import liquibase.change.Change;
import liquibase.change.core.RawSQLChange;
import liquibase.database.Database;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.AbstractChangeGenerator;
import liquibase.diff.output.changelog.ChangeGeneratorChain;
import liquibase.diff.output.changelog.MissingObjectChangeGenerator;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Column;
import liquibase.structure.core.Table;
import liquibase.structure.core.View;

/**
 * Emits a Trino table or view as its verbatim {@code CREATE} statement instead of a reconstructed
 * {@code <createTable>} / {@code <createView>}.
 * <p>
 * The structured changes cannot express what Trino actually needs, so nothing is lost by going
 * around them:
 * <ul>
 *   <li>{@code CreateTableChange} has no place for the connector properties. A Trino table's
 *       {@code partitioning}, {@code location}, {@code format} and {@code format_version} are
 *       accepted only in the {@code WITH (...)} clause, which core's generator does not emit.
 *   <li>{@code CreateViewChange.remarks} is written out only for a fixed list of database classes
 *       that Trino is not part of, and security mode has no field at all — so both
 *       {@code COMMENT} and {@code SECURITY DEFINER} would be dropped.
 *   <li>Column types read from {@code information_schema} are lossy: a {@code varchar} declared
 *       without a length reports {@code character_maximum_length = 2147483647}, which would
 *       recreate a different table.
 * </ul>
 * The statement recorded by the Trino snapshot generators is used as-is, so a generated changelog
 * reproduces the object exactly, including its comments and storage properties.
 */
public class TrinoDdlChangeGenerator extends AbstractChangeGenerator implements MissingObjectChangeGenerator {

    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        if (!(database instanceof TrinoDatabase)) {
            return PRIORITY_NONE;
        }
        // Must outrank MissingTableChangeGenerator / MissingViewChangeGenerator, which both sit at
        // PRIORITY_DEFAULT: the chain takes the first generator in priority order, so anything lower
        // would never be reached for a table or a view.
        if (Table.class.isAssignableFrom(objectType) || View.class.isAssignableFrom(objectType)) {
            return PRIORITY_DATABASE;
        }
        return PRIORITY_NONE;
    }

    @Override
    public Class<? extends DatabaseObject>[] runAfterTypes() {
        return null;
    }

    @Override
    public Class<? extends DatabaseObject>[] runBeforeTypes() {
        return null;
    }

    @Override
    public Change[] fixMissing(DatabaseObject missingObject, DiffOutputControl control,
                               Database referenceDatabase, Database comparisonDatabase,
                               ChangeGeneratorChain chain) {
        if (!(missingObject instanceof Table) && !(missingObject instanceof View)) {
            return EMPTY_CHANGE;
        }
        String ddl = missingObject.getAttribute(TrinoDatabase.DDL_ATTRIBUTE, String.class);
        if (ddl == null) {
            // No verbatim statement was captured. Deferring to the chain lets core produce its
            // lossy-but-valid change instead of dropping the object entirely.
            return chain.fixMissing(missingObject, control, referenceDatabase, comparisonDatabase);
        }

        RawSQLChange change = new RawSQLChange(ddl);
        // splitStatements:false on purpose. Two reasons, both from the formatted SQL writer:
        // it always prints "splitStatements:false" for a changeset and only rewrites that to true
        // when one change expands into several statements, and it always terminates the body with
        // the end delimiter. Trino rejects a statement that still ends in ';', and with splitting
        // off there is nothing left to strip it. The body therefore carries no trailing ';' — see
        // TrinoDdlFetcher.stripTrailingSemicolon.
        change.setSplitStatements(false);
        // The writer still terminates the body with the end delimiter, and Trino rejects a
        // statement that ends in ';'. An empty delimiter suppresses it, leaving the changeset
        // body as the bare statement.
        change.setEndDelimiter("");
        change.setComment("Verbatim " + (missingObject instanceof View ? "view" : "table")
                + " DDL from " + missingObject.getName());

        // The columns are covered by the DDL, so the structured generators must not also emit them.
        markColumnsHandled(missingObject, control, comparisonDatabase);

        return new Change[]{change};
    }

    /**
     * Tells the control that each column is already dealt with.
     * <p>
     * Otherwise the diff keeps reporting the columns as missing and a later generator adds a
     * separate {@code addColumn} change for each one — on top of a {@code CREATE TABLE} that
     * already declares them.
     */
    private void markColumnsHandled(DatabaseObject missingObject, DiffOutputControl control,
                                    Database comparisonDatabase) {
        if (!(missingObject instanceof Table) || ((Table) missingObject).getColumns() == null) {
            return;
        }
        for (Column column : ((Table) missingObject).getColumns()) {
            control.setAlreadyHandledMissing(column);
        }
    }
}