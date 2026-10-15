package liquibase.ext.trino

import liquibase.harness.AdvancedHarnessSuite
import liquibase.harness.generateChangelog.GenerateChangelogTest
import liquibase.harness.snapshot.SnapshotObjectTests
import org.junit.platform.suite.api.SelectClasses

@SelectClasses([SnapshotObjectTests, GenerateChangelogTest])
class AdvancedHarnessSuiteIT extends AdvancedHarnessSuite {}
