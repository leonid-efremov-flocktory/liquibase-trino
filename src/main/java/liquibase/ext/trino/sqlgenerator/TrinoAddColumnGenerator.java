package liquibase.ext.trino.sqlgenerator;

import liquibase.database.Database;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.sqlgenerator.core.AddColumnGenerator;
import liquibase.statement.core.AddColumnStatement;

/**
 * Trino requires the {@code COLUMN} keyword, while the base generator emits a bare {@code ADD}
 * and Trino rejects it with {@code mismatched input '<name>'. Expecting: '.', 'ADD'}.
 * <p>
 * Only the keyword is changed. The rest of the clause — column type, default, nullability,
 * auto-increment — is still produced by the base generator, so this cannot drift away from it.
 */
public class TrinoAddColumnGenerator extends AddColumnGenerator {

    private static final String BASE_PREFIX = " ADD ";

    @Override
    public int getPriority() {
        return TrinoDatabase.TRINO_PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(AddColumnStatement statement, Database database) {
        return database instanceof TrinoDatabase;
    }

    @Override
    protected String generateSingleColumnSQL(AddColumnStatement statement, Database database) {
        String sql = super.generateSingleColumnSQL(statement, database);
        if (!sql.startsWith(BASE_PREFIX)) {
            // Rather than quietly reverting to SQL Trino rejects, fail on the assumption breaking:
            // a future Liquibase could rename or restructure what the base generator emits here.
            throw new IllegalStateException(
                    "Expected the base ADD COLUMN clause to start with \"" + BASE_PREFIX + "\", got: " + sql);
        }
        return " ADD COLUMN " + sql.substring(BASE_PREFIX.length());
    }
}