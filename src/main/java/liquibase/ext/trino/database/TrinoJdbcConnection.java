package liquibase.ext.trino.database;

import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;

/**
 * A {@link JdbcConnection} that refuses to leave autoCommit mode.
 * <p>
 * {@link TrinoDatabase#setAutoCommit} already no-ops, but Liquibase is not the only caller:
 * the Liquibase Test Harness flips autoCommit directly on the connection it pulls out of the
 * {@link liquibase.database.Database} ({@code DatabaseTestContext.openConnection} calls
 * {@code databaseConnection.setAutoCommit(false)}), which bypasses the {@code Database} hook.
 * On Trino that poisons the session: the driver wraps everything in an implicit transaction,
 * and the next metadata one-liner — {@code TrinoDatabaseMetaData.getUserName()}, which
 * {@code AbstractJdbcDatabase.setConnection} runs on every connect — comes back
 * {@code "Current transaction is aborted, commands ignored until end of transaction block"},
 * so the harness fails to build its {@code Database} at all (plan stage 2).
 * <p>
 * Swallowing the call keeps the connection on the driver default (autoCommit true), which is
 * also what the dialect expects: {@code TrinoDatabase.supportsDDLInTransaction()} is false, so
 * Liquibase never wants DDL inside a transaction either.
 */
public class TrinoJdbcConnection extends JdbcConnection {

    public TrinoJdbcConnection(JdbcConnection delegate) {
        super(delegate.getUnderlyingConnection());
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws DatabaseException {
        // Deliberately ignored: see the class Javadoc.
    }
}
