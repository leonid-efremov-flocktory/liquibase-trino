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
import java.util.Iterator;
import java.util.List;

/**
 * Copy of the base generator without its {@code .toUpperCase()} on the column list: even
 * after lower-case escaping, the base generator emits {@code SELECT ID, AUTHOR, ...},
 * while Trino stores column names in lower case.
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
        List<ColumnConfig> columnsToSelect = Arrays.asList(statement.getColumnsToSelect());
        ObjectQuotingStrategy currentStrategy = database.getObjectQuotingStrategy();
        database.setObjectQuotingStrategy(ObjectQuotingStrategy.LEGACY);
        try {
            String sql = "SELECT " + StringUtil.join(columnsToSelect, ",", (StringUtil.StringUtilFormatter<ColumnConfig>) column -> {
                if ((column.getComputed() != null) && column.getComputed()) {
                    return column.getName();
                } else {
                    return database.escapeColumnName(null, null, null, column.getName());
                }
            }) + " FROM " +
                    database.escapeTableName(database.getLiquibaseCatalogName(), database.getLiquibaseSchemaName(), database.getDatabaseChangeLogTableName());

            SelectFromDatabaseChangeLogStatement.WhereClause whereClause = statement.getWhereClause();
            if (whereClause != null) {
                sql += whereClause.generateSql(database);
            }

            if ((statement.getOrderByColumns() != null) && (statement.getOrderByColumns().length > 0)) {
                sql += " ORDER BY ";
                Iterator<String> orderBy = Arrays.asList(statement.getOrderByColumns()).iterator();

                while (orderBy.hasNext()) {
                    String orderColumn = orderBy.next();
                    String[] orderColumnData = orderColumn.split(" ");
                    sql += database.escapeColumnName(null, null, null, orderColumnData[0]);
                    if (orderColumnData.length == 2) {
                        sql += " ";
                        sql += orderColumnData[1].toUpperCase();
                    }
                    if (orderBy.hasNext()) {
                        sql += ", ";
                    }
                }
            }
            
            if (statement.getLimit() != null) {
                sql += " LIMIT " + statement.getLimit();
            }

            return new Sql[]{
                    new UnparsedSql(sql)
            };
        } finally {
            database.setObjectQuotingStrategy(currentStrategy);
        }
    }
}
