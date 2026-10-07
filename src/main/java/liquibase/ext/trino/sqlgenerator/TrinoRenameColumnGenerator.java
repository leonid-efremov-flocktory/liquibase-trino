package liquibase.ext.trino.sqlgenerator;

import liquibase.database.Database;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.RenameColumnGenerator;
import liquibase.statement.core.RenameColumnStatement;

/**
 * Trino spells a column rename {@code ALTER TABLE ... RENAME COLUMN old TO new}.
 * <p>
 * This exists only because {@link TrinoDatabase} extends {@code H2Database}: the base generator
 * branches on the database type and matches the H2 form
 * ({@code ALTER COLUMN old RENAME TO new}), which Trino rejects with
 * {@code mismatched input 'RENAME'. Expecting: '.', 'DROP', 'SET'}. The generated SQL is otherwise
 * identical to the base generator's default branch, which is already the right one for Trino.
 */
public class TrinoRenameColumnGenerator extends RenameColumnGenerator {

    @Override
    public int getPriority() {
        return TrinoDatabase.TRINO_PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(RenameColumnStatement statement, Database database) {
        return database instanceof TrinoDatabase;
    }

    @Override
    public Sql[] generateSql(RenameColumnStatement statement, Database database, SqlGeneratorChain chain) {
        String sql = "ALTER TABLE "
                + database.escapeTableName(statement.getCatalogName(), statement.getSchemaName(),
                        statement.getTableName())
                + " RENAME COLUMN "
                + database.escapeColumnName(statement.getCatalogName(), statement.getSchemaName(),
                        statement.getTableName(), statement.getOldColumnName())
                + " TO "
                + database.escapeColumnName(statement.getCatalogName(), statement.getSchemaName(),
                        statement.getTableName(), statement.getNewColumnName());
        return new Sql[]{
                new UnparsedSql(sql, getAffectedOldColumn(statement), getAffectedNewColumn(statement))};
    }
}