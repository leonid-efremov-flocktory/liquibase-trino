package liquibase.ext.trino.changelog

import liquibase.Liquibase
import liquibase.database.Database
import liquibase.ext.trino.TrinoIntegrationSpec
import liquibase.ext.trino.TrinoTestSupport
import liquibase.resource.ClassLoaderResourceAccessor
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Rollback of fixture changesets against a live Trino.
 *
 * <p>The tests run in order: first the writer variant of rollback, which executes nothing, then
 * real {@code rollback} calls. The fixture is never re-applied — once applied, each changeset
 * can be rolled back exactly once, so {@code @Stepwise} is what keeps the order meaningful, and
 * the verification of each rollback lives in the same feature that performs it: a failing
 * intermediate assertion would otherwise leave the stand in a state the next feature does not
 * expect.
 *
 * <p>The class leaves the fixture fully rolled back and does not restore it: the next class that
 * needs the applied state gets it from {@link TrinoTestSupport#applyIfNeeded()}, and re-applying
 * it here would apply the same changesets twice per run.
 */
@Stepwise
class TrinoRollbackSpec extends TrinoIntegrationSpec {

    @Shared
    Database db

    def setupSpec() {
        TrinoTestSupport.applyIfNeeded()
        db = TrinoTestSupport.openChangelogDatabase()
    }

    def "rollback-sql writes statements without executing them"() {
        when:
        StringWriter output = new StringWriter()
        def liquibase = new Liquibase(TrinoTestSupport.CHANGELOG, new ClassLoaderResourceAccessor(), db)
        liquibase.rollback(2, TrinoTestSupport.CONTEXT, output)
        def sql = output.toString()

        then: "the generated script carries the header and the two drops"
        sql.contains("Rollback 2 Change(s) Script")
        sql.contains("DROP VIEW IF EXISTS " + TrinoTestSupport.FIXTURE_VIEW)
        sql.contains("DROP TABLE IF EXISTS " + TrinoTestSupport.FIXTURE_TABLE)
        sql.contains("DELETE FROM " + TrinoTestSupport.FIXTURE_TABLE)

        and: "the writer path executes nothing, so the objects are still in place"
        TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE) == "5"
        TrinoTestSupport.countFixtureObjects(TrinoTestSupport.VIEW_NAME) == "1"
    }

    def "rolling back the last changeset restores the view and removes its rows"() {
        when:
        TrinoTestSupport.rollback(db, 1)

        then: "rolling back v2 removes the rows 4 and 5 it added"
        TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE) == "3"
        TrinoTestSupport.count(TrinoTestSupport.FIXTURE_VIEW) == "3"

        and: "only the rolled back changeset disappears from DATABASECHANGELOG"
        TrinoTestSupport.appliedChangesetIds() == ["common-schema-setup", "v1-test-table-and-view"]
        lockReleased()
    }

    def "rolling back the remaining changeset drops the table and view"() {
        when:
        TrinoTestSupport.rollback(db, 1)

        then: "rolling back v1 drops the view and the table"
        TrinoTestSupport.countFixtureObjects(TrinoTestSupport.VIEW_NAME) == "0"
        TrinoTestSupport.countFixtureObjects(TrinoTestSupport.TABLE_NAME) == "0"

        and: "only the no-op changeset that created the schema is left"
        TrinoTestSupport.appliedChangesetIds() == ["common-schema-setup"]
        lockReleased()
    }

    /**
     * The fixture's first changeset carries an empty {@code <rollback/>}: the schemas it created
     * are deliberately not dropped, so rolling it back has nothing to do.
     *
     * <p>That makes this the only place the empty block is exercised against a live stand. It used
     * to be asserted indirectly by a stand-less unit test that only checked the parse result
     * ({@code assertInstanceOf(EmptyChange.class, …)}); asserting it here is stronger, because
     * it also pins the observable consequence — the changeset disappears from
     * {@code DATABASECHANGELOG} while its schemas survive, which is what "no-op" has to mean.
     */
    def "rolling back an empty block undoes nothing but forgets the changeset"() {
        when:
        TrinoTestSupport.rollback(db, 1)

        then: "the changeset row is gone"
        TrinoTestSupport.appliedChangesetIds() == []

        and: "but the schema it created has survived"
        TrinoTestSupport.queryFirstColumn("SELECT schema_name FROM " + TrinoTestSupport.catalog()
                + ".information_schema.schemata WHERE schema_name = '"
                + TrinoTestSupport.FIXTURE_SCHEMA_NAME + "'")
                == [TrinoTestSupport.FIXTURE_SCHEMA_NAME]
        lockReleased()
    }

    /** Rollback takes the lock too: a stuck one would only show up on the next update. */
    private static boolean lockReleased() {
        return TrinoTestSupport.lockState() == ["false|"]
    }
}
