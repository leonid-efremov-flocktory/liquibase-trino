package liquibase.ext.trino.changelog

import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.Database
import liquibase.ext.trino.TrinoIntegrationSpec
import liquibase.ext.trino.TrinoTestSupport
import spock.lang.Shared
import spock.lang.Stepwise

/**
 * Liquibase commands that read the tracking table back rather than write to it:
 * {@code status}, {@code history} and {@code tag}.
 *
 * <p>Milestone 1 of the "add a database" guide lists these as things that must work once
 * {@code update} does. All of them funnel through
 * {@link liquibase.ext.trino.sqlgenerator.TrinoSelectFromDatabaseChangeLogGenerator}, the
 * plugin's only SQL generator for reads, so a defect in it surfaces here as wrong output
 * rather than as an exception — which is why these assert on values, not on "did not throw".
 *
 * <p>The fixture is applied once for the whole run and only read from here. {@code tag} is
 * the exception: it writes a tag column, which {@code cleanupSpec()} clears. The two tag
 * features run last and in order — {@code @Stepwise} keeps them that way, and the first
 * asserts the tag is absent before it writes one.
 */
@Stepwise
class TrinoReadCommandsSpec extends TrinoIntegrationSpec {

    private static final String TAG = "release-1"

    @Shared
    Database db

    @Shared
    Liquibase liquibase

    def setupSpec() {
        TrinoTestSupport.applyIfNeeded()
        db = TrinoTestSupport.openChangelogDatabase()
        liquibase = TrinoTestSupport.liquibase(db)
    }

    def cleanupSpec() {
        // tag() wrote a tag column; clearing it keeps the shared fixture in the state the
        // other classes expect. Plain SQL, since liquibase.tag(null) is rejected.
        TrinoTestSupport.execute("UPDATE " + TrinoTestSupport.CHANGELOG_SCHEMA
                + ".databasechangelog SET tag = NULL WHERE tag = '" + TAG + "'")
    }

    // --- status ---

    def "status reports nothing pending after update"() {
        when:
        StringWriter output = new StringWriter()
        liquibase.reportStatus(true, TrinoTestSupport.CONTEXT, output)

        then:
        output.toString().contains("is up to date")
    }

    def "status knows every changeset already ran"() {
        when:
        def statuses = liquibase.getChangeSetStatuses(TrinoTestSupport.CONTEXT, new LabelExpression())

        then:
        TrinoTestSupport.idsOf(statuses, { it.changeSet.id }) == TrinoTestSupport.APPLIED_CHANGESETS

        and: "each changeset is seen as run, not pending, and with a readable checksum"
        // A null stored checksum would mean status could not read the row back, which is
        // exactly what the read generator is supposed to make possible.
        statuses.every { it.previouslyRan }
        statuses.every { !it.willRun }
        statuses.every { it.storedCheckSum != null }
    }

    def "listUnrunChangeSets is empty after update"() {
        expect:
        liquibase.listUnrunChangeSets(TrinoTestSupport.CONTEXT, new LabelExpression()) == []
    }

    // --- history ---

    def "history reads changesets back in execution order"() {
        when:
        def history = TrinoTestSupport.historyService(db)
        def ran = history.getRanChangeSets()

        then:
        TrinoTestSupport.idsOf(ran, { it.id }) == TrinoTestSupport.APPLIED_CHANGESETS

        and: "history exposes orderexecuted and a timestamp so a rollback could target a changeset"
        ran.collect { it.orderExecuted } == [1, 2, 3]
        ran.every { it.dateExecuted != null }
    }

    // --- tag / tagExists ---

    def "tag is recorded and found again"() {
        when:
        def existedBefore = liquibase.tagExists(TAG)
        liquibase.tag(TAG)

        then:
        !existedBefore
        liquibase.tagExists(TAG)
        // Read back through a tagged SELECT, not merely stored: this is the ByTag where-clause
        // path of the read generator.
        changesetIdsWhereTagIs("tag = '" + TAG + "'") == ["v2-extend-test-table-and-view"]
    }

    def "tag leaves earlier changesets untagged"() {
        expect:
        // tag IS NULL, not tag = '': a tag column written as an empty string would satisfy the
        // equality check and hide the difference between "no tag" and "empty tag".
        changesetIdsWhereTagIs("tag IS NULL") == ["common-schema-setup", "v1-test-table-and-view"]
    }

    /** Ids of the changesets the tag column holds the given value for, in execution order. */
    private static List<String> changesetIdsWhereTagIs(String tagCondition) {
        return TrinoTestSupport.queryFirstColumn("SELECT id FROM "
                + TrinoTestSupport.CHANGELOG_SCHEMA + ".databasechangelog WHERE " + tagCondition
                + " ORDER BY orderexecuted")
    }
}
