package liquibase.ext.trino.sqlgenerator;

import liquibase.change.ColumnConfig;
import liquibase.database.Database;
import liquibase.database.ObjectQuotingStrategy;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.SelectFromDatabaseChangeLogGenerator;
import liquibase.statement.core.SelectFromDatabaseChangeLogStatement;
import liquibase.util.StringUtil;

import java.util.Arrays;

/**
 * Delegated to {@link SelectFromDatabaseChangeLogGenerator}, which owns the where-clause, the
 * {@code ORDER BY} clause and all escaping. Only two things in its output are corrected:
 * <ul>
 *     <li>the base generator runs the joined column list through {@code .toUpperCase()}, emitting
 *     {@code SELECT ID, AUTHOR, ...}, while Trino stores column names in lower case;</li>
 *     <li>the base generator emits {@code LIMIT} only for Oracle/MySQL/PostgreSQL/DB2, and
 *     {@code TrinoDatabase} extends {@code H2Database}, so the clause is dropped.</li>
 * </ul>
 */
public class TrinoSelectFromDatabaseChangeLogGenerator extends SelectFromDatabaseChangeLogGenerator {

    @Override
    public int getPriority() {
        return TrinoDatabase.TRINO_PRIORITY_DATABASE;
    }

    @Override
    public boolean supports(SelectFromDatabaseChangeLogStatement statement, Database database) {
        return database instanceof TrinoDatabase;
    }

    @Override
    public Sql[] generateSql(SelectFromDatabaseChangeLogStatement statement, final Database database, SqlGeneratorChain sqlGeneratorChain) {
        String sql = lowerCaseColumnList(statement, database, super.generateSql(statement, database, sqlGeneratorChain)[0].toSql());

        if (statement.getLimit() != null) {
            sql += " LIMIT " + statement.getLimit();
        }

        return new Sql[]{
                new UnparsedSql(sql)
        };
    }

    /**
     * Undoes the base generator's {@code .toUpperCase()} on the column list by rebuilding that list
     * the way the base does and substituting it back, rather than lower-casing the whole statement,
     * which would corrupt case-sensitive literals in the where-clause.
     */
    private static String lowerCaseColumnList(SelectFromDatabaseChangeLogStatement statement, Database database, String sql) {
        String columns = joinColumnList(statement, database);
        String upperCased = columns.toUpperCase();

        if (!sql.contains(upperCased)) {
            // The base generator no longer upper-cases the column list, so this override is now a
            // no-op that would silently start emitting whatever it does. Fail loudly instead.
            throw new IllegalStateException("Expected the upper-cased column list \"" + upperCased + "\" in: " + sql);
        }

        return sql.replace(upperCased, columns);
    }

    private static String joinColumnList(SelectFromDatabaseChangeLogStatement statement, Database database) {
        ObjectQuotingStrategy currentStrategy = database.getObjectQuotingStrategy();
        database.setObjectQuotingStrategy(ObjectQuotingStrategy.LEGACY);
        try {
            return StringUtil.join(Arrays.asList(statement.getColumnsToSelect()), ",", (StringUtil.StringUtilFormatter<ColumnConfig>) column -> {
                if ((column.getComputed() != null) && column.getComputed()) {
                    return column.getName();
                } else {
                    return database.escapeColumnName(null, null, null, column.getName());
                }
            });
        } finally {
            database.setObjectQuotingStrategy(currentStrategy);
        }
    }
}
