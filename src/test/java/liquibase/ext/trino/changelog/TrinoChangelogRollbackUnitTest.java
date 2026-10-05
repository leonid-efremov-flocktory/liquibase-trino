package liquibase.ext.trino.changelog;

import liquibase.Scope;
import liquibase.change.Change;
import liquibase.change.core.EmptyChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Parsing of the fixture's rollback blocks for the Trino dialect, without a stand.
 * <p>
 * The contents of those blocks are not checked here — {@link TrinoRollbackIntegrationTest}
 * parses the same fixture and then runs the generated SQL on the stand. This class keeps
 * only what is visible without Trino: that an empty {@code <rollback/>} parses into an
 * {@link EmptyChange}.
 */
class TrinoChangelogRollbackUnitTest {

    private static final String CHANGELOG = TrinoTestSupport.CHANGELOG;
    private static final String AUTHOR = "Leonid-Efremov";

    @Test
    void emptyXmlRollbackParsesAsEmptyChange() throws Exception {
        ChangeSet schemaSetup = findChangeSet(parseChangelog(), "common-schema-setup");
        List<Change> rollback = schemaSetup.getRollback().getChanges();

        assertEquals(1, rollback.size());
        assertInstanceOf(EmptyChange.class, rollback.get(0));
    }

    private DatabaseChangeLog parseChangelog() throws Exception {
        TrinoDatabase db = new TrinoDatabase();
        ChangeLogParameters params = new ChangeLogParameters(db);
        params.setContexts(TrinoTestSupport.CONTEXT);

        ResourceAccessor accessor = new ClassLoaderResourceAccessor();
        ChangeLogParser parser = ChangeLogParserFactory.getInstance().getParser(CHANGELOG, accessor);
        AtomicReference<DatabaseChangeLog> changelog = new AtomicReference<>();
        Scope.child(Scope.Attr.database, db, () -> changelog.set(parser.parse(CHANGELOG, params, accessor)));
        return changelog.get();
    }

    private ChangeSet findChangeSet(DatabaseChangeLog changeLog, String id) {
        return changeLog.getChangeSets().stream()
                .filter(cs -> id.equals(cs.getId()) && AUTHOR.equals(cs.getAuthor()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Changeset '" + id + "' not found in " + CHANGELOG));
    }
}