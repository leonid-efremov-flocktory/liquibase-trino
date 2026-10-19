# Test Coverage Map and Baseline

This file is the acceptance artifact of the test-suite rewrite described in
`.opencode/plan.md`. It serves two purposes:

1. **Coverage map** — every test answers "what exactly does it verify". Stages 3–4 of
   the plan migrate tests onto Groovy/Spock + Testcontainers + the Liquibase Test
   Harness; a row-by-row comparison of this table before and after is what proves
   coverage did not shrink.
2. **Baseline** — the measured numbers (test counts, per-class times, JaCoCo
   instruction/branch per main class) captured before any rewrite step. Stage 5
   compares the final run against exactly these figures.

## How the baseline was captured

```
git rev-parse --short HEAD   # bf42c90
./run-tests.sh up            # docker-compose stand (silo + iceberg-rest + trino:464)
./run-tests.sh test          # mvn test in maven:3.9-eclipse-temurin-17 container
```

Run date: 2026-10-08. Prerequisite: `target/` wiped first, so `target/jacoco.exec`
contains a single session and the reports below reflect exactly one full run.
`./run-tests.sh test` fell back to the containerized Maven (no `mvn` on the host);
the log is a plain `mvn test` either way.

## Baseline numbers

- **Tests: 124 run, 0 failures, 0 errors, 0 skipped** — `BUILD SUCCESS`,
  total Maven time 02:30 min, in-test time 145.13 s.
- **17 surefire report pairs** (`target/surefire-reports/TEST-*.xml` + `.txt`):

```
TEST-liquibase.ext.trino.changelog.TrinoChangeLogUpdateIntegrationTest.xml
TEST-liquibase.ext.trino.changelog.TrinoChangelogFormatIntegrationTest.xml
TEST-liquibase.ext.trino.changelog.TrinoReadCommandsIntegrationTest.xml
TEST-liquibase.ext.trino.changelog.TrinoRollbackIntegrationTest.xml
TEST-liquibase.ext.trino.database.TrinoDatabaseIntegrationTest.xml
TEST-liquibase.ext.trino.database.TrinoDatabaseUnitTest.xml
TEST-liquibase.ext.trino.golden.TrinoGoldenSqlTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoDdlFetcherUnitTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoDiffChangelogIntegrationTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoDiffIntegrationTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoGenerateChangelogIntegrationTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoMetadataSnapshotIntegrationTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoSchemaSnapshotIntegrationTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoSnapshotCommandIntegrationTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoSnapshotFailureIntegrationTest.xml
TEST-liquibase.ext.trino.snapshot.TrinoTableSnapshotGeneratorUnitTest.xml
TEST-liquibase.ext.trino.sqlgenerator.TrinoSqlGeneratorsUnitTest.xml
```


### Per-class results (clean baseline run)

| # | Test class | Tests | Skipped | Time, s |
| --- | --- | ---: | ---: | ---: |
| 1 | `golden.TrinoGoldenSqlTest` | 18 | 0 | 82.03 |
| 2 | `changelog.TrinoChangelogFormatIntegrationTest` | 4 | 0 | 13.89 |
| 3 | `snapshot.TrinoSnapshotFailureIntegrationTest` | 11 | 0 | 9.04 |
| 4 | `snapshot.TrinoGenerateChangelogIntegrationTest` | 3 | 0 | 7.72 |
| 5 | `snapshot.TrinoDiffChangelogIntegrationTest` | 4 | 0 | 7.68 |
| 6 | `snapshot.TrinoDiffIntegrationTest` | 4 | 0 | 7.32 |
| 7 | `changelog.TrinoReadCommandsIntegrationTest` | 6 | 0 | 5.44 |
| 8 | `changelog.TrinoRollbackIntegrationTest` | 4 | 0 | 4.82 |
| 9 | `changelog.TrinoChangeLogUpdateIntegrationTest` | 6 | 0 | 3.69 |
| 10 | `snapshot.TrinoSnapshotCommandIntegrationTest` | 3 | 0 | 1.62 |
| 11 | `snapshot.TrinoSchemaSnapshotIntegrationTest` | 5 | 0 | 0.90 |
| 12 | `snapshot.TrinoTableSnapshotGeneratorUnitTest` | 5 | 0 | 0.36 |
| 13 | `snapshot.TrinoMetadataSnapshotIntegrationTest` | 3 | 0 | 0.26 |
| 14 | `database.TrinoDatabaseIntegrationTest` | 2 | 0 | 0.17 |
| 15 | `snapshot.TrinoDdlFetcherUnitTest` | 12 | 0 | 0.08 |
| 16 | `sqlgenerator.TrinoSqlGeneratorsUnitTest` | 16 | 0 | 0.07 |
| 17 | `database.TrinoDatabaseUnitTest` | 18 | 0 | 0.03 |
| | **TOTAL** | **124** | **0** | **145.13** |

78 of the 124 tests are `@Tag("integration")` (all `*IntegrationTest`,
`TrinoGoldenSqlTest`, `TrinoTableSnapshotGeneratorUnitTest`); the other 46 run
without a stand (`-Punit` profile excludes the 78).

### JaCoCo baseline (instruction + branch, all 10 main classes)

From `target/site/jacoco/jacoco.xml`, single session, bundle analyzed with 10 classes:

| Main class | Instr missed | Instr covered | Instr % | Branch missed | Branch covered | Branch % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `database.TrinoDatabase` | 77 | 283 | 78.6% | 20 | 24 | 54.5% |
| `diff.TrinoDdlChangeGenerator` | 11 | 96 | 89.7% | 4 | 16 | 80.0% |
| `snapshot.TrinoDdlFetcher` | 6 | 194 | 97.0% | 5 | 29 | 85.3% |
| `snapshot.TrinoSchemaSnapshotGenerator` | 12 | 97 | 89.0% | 6 | 18 | 75.0% |
| `snapshot.TrinoTableSnapshotGenerator` | 0 | 55 | 100.0% | 0 | 8 | 100.0% |
| `snapshot.TrinoViewSnapshotGenerator` | 2 | 53 | 96.4% | 1 | 7 | 87.5% |
| `sqlgenerator.TrinoAddColumnGenerator` | 6 | 23 | 79.3% | 1 | 1 | 50.0% |
| `sqlgenerator.TrinoCreateDatabaseChangeLogLockTableGenerator` | 0 | 92 | 100.0% | 0 | 0 | n/a |
| `sqlgenerator.TrinoRenameColumnGenerator` | 0 | 62 | 100.0% | 0 | 0 | n/a |
| `sqlgenerator.TrinoSelectFromDatabaseChangeLogGenerator` | 7 | 93 | 93.0% | 2 | 6 | 75.0% |
| **BUNDLE** | **121** | **1048** | **89.6%** | **39** | **109** | **73.6%** |

Acceptance rule for stage 5: no main class may drop below its baseline instruction
or branch numbers; a drop must be explained by pointing at the test that now covers
the line instead.


## Coverage map: capability → test

### Golden SQL cases — migrated to harness `change/changelogs/trino/` (18 cases)

These 18 cases used to be `TrinoGoldenSqlTest`. In stage 3 they moved to
`src/test/resources/liquibase/harness/change/changelogs/trino/*.xml`, checked against
`expectedSql/trino/*.sql` by the standard `ChangeObjectTests`. Object names are per-case
as a safety net; the harness 1.0.12 does roll back between cases (`RollbackToDate` —
`rollback --date`), so the chains are additionally balanced end to end. The table records
what each case exercised in the old suite; the harness now asserts generated SQL; snapshot
files exist but are `{}` (the harness's sanctioned "skip snapshot verification" — see the
plan's stage-4 note).

| # | Case | Changelog | Verifies after apply |
| --- | --- | --- | --- |
| 1 | `createSchema` | `createSchema.xml` | schema `trino_harness_schema` exists |
| 2 | `dropSchema` | `dropSchema.xml` | schema `trino_harness_schema` dropped |
| 3 | `createTable` | `createTable.xml` | table `create_table` exists |
| 4 | `addColumn` | `addColumn.xml` | table `add_column` has 2 columns |
| 5 | `dropColumn` | `dropColumn.xml` | column `txt` of `drop_column` gone |
| 6 | `renameColumn` | `renameColumn.xml` | column `body` exists in `rename_column` |
| 7 | `renameTable` | `renameTable.xml` | table `rename_table_after` exists |
| 8 | `dropTable` | `dropTable.xml` | table `drop_table` gone |
| 9 | `createView` | `createView.xml` | view `create_view_v` exists |
| 10 | `dropView` | `dropView.xml` | view `drop_view_v` gone |
| 11 | `insert` | `insert.xml` | `insert_t` has 2 rows |
| 12 | `delete` | `delete.xml` | `delete_t` has 1 row |
| 13 | `sql` | `sql.xml` | table `sql_t` created by raw `<sql>` |
| 14 | `sqlFile` | `sqlFile.xml` | table `sql_file_t` created by `<sqlFile>` |
| 15 | `partitionedTable` | `partitionedTable.xml` | `partitioned_table_t` has 2 columns; partitioning pinned verbatim |
| 16 | `comments` | `comments.xml` | table/column comments on `comments_t` |
| 17 | `complexTypes` | `complexTypes.xml` | `complex_types_t` holds ARRAY/ROW columns |

**Raw-SQL cases:** 7 of the 18 (`sql`, `sqlFile`, `comments`, `complexTypes`,
`partitionedTable`, `createSchema`, `dropSchema`) carry raw `<sql>`/`<sqlFile>`, so they do
not exercise any SQL generator — their value is changelog parsing, statement splitting and
type handling, and the snapshot round-trip. The other 11 go through a generator.


### Changelog / update / rollback — `changelog` package (4 specs, 20 tests)

Migrated to Groovy/Spock in plan stage 8; the class names below are the ones that verify
these capabilities today.

| Test class | Tests | What it verifies |
| --- | ---: | --- |
| `TrinoChangeLogUpdateSpec` | 6 | Full `update` from scratch (dropAll + fixture): all changesets applied and tracking tables created; every changeset recorded in `DATABASECHANGELOG`; rows carry execution metadata; `DATABASECHANGELOGLOCK` released; fixture's table + data + view exist; repeated `update` does not duplicate data (idempotency) |
| `TrinoChangelogFormatSpec` | 4 | `<sqlFile>` changelog applies in XML/YAML/JSON equivalently (property expansion + relative resource paths resolve identically across parsers); XML sqlFile changelog rolls back |
| `TrinoReadCommandsSpec` | 6 | Read-side commands through `TrinoSelectFromDatabaseChangeLogGenerator`: `status` empty after update / knows each ran changeset; `listUnrunChangeSets` empty; `history` reads changesets back in execution order; `tag` recorded and found again; tag leaves earlier changesets untagged |
| `TrinoRollbackSpec` | 4 | `rollback-sql` writes statements without executing them; `rollback` of last changeset restores view and removes its rows; rollback of remaining changeset drops table+view; rollback of an empty block undoes nothing but still records the changeset |


### Snapshot / diff — `snapshot` package (9 classes, 50 tests)

Mixed Java unit tests and Groovy/Spock specs; the three diff/failure entries were migrated
in plan stage 8.

| Test class | Tests | What it verifies |
| --- | ---: | --- |
| `TrinoDdlFetcherUnitTest` | 12 | `TrinoDdlFetcher` failure modes without a stand: failed DDL read marks the object unkeepable (table/view/runtime), non-Trino database keeps the object, `describe()` naming fallbacks, statement kept without trailing semicolon, missing object is not a failure |
| `TrinoTableSnapshotGeneratorUnitTest` | 5 | Per-object guard: readable table still snapshotted; unreadable table skipped instead of fatal; unchecked failure skipped; skipped table named in a warning; table whose statement cannot be read is dropped from snapshot |
| `TrinoSnapshotFailureSpec` | 11 | End-to-end resilience: unreadable table/view dropped from snapshot but command survives; several unreadable objects; `generate-changelog` survives and omits them; every object skipped ⇒ no changelog file; skipped object named in a warning (parameterized over command × object kind); healthy connection records everything |
| `TrinoSnapshotCommandIntegrationTest` | 3 | `snapshot` command via `CommandScope`: fixture objects carry verbatim `trino.ddl`; repeated runs identical; unsupported constraint metadata does not fail |
| `TrinoMetadataSnapshotIntegrationTest` | 3 | Regression: `has()` finds existing tables (lower-case schema filter vs H2 UPPER_CASE dialect), missing table not found, metadata schema filter is lowercase |
| `TrinoSchemaSnapshotIntegrationTest` | 5 | `TrinoSchemaSnapshotGenerator`: schema found in default catalog; missing schema not found; case-insensitive lookup; schema in another catalog keeps its own catalog; shared schema name resolves to the requested catalog |
| `TrinoGenerateChangelogIntegrationTest` | 3 | `generate-changelog`: output contains verbatim DDL; generated SQL equals `SHOW CREATE` character for character; generated changelog replays to identical DDL (round trip) |
| `TrinoDiffSpec` | 4 | What `diff` reports today, pinned as-is: object on one side reported; identical object not reported; column difference reported against its table; connector-properties-only difference reported as changed |
| `TrinoDiffChangelogSpec` | 4 | What `diffChangelog` writes: missing table emitted as verbatim DDL; emitted statement equals server's; unexpected table emitted as drop; changed table with no generator produces no change |


### Database dialect — `database` package (2 classes, 20 tests)

| Test class | Tests | What it verifies |
| --- | ---: | --- |
| `TrinoDatabaseIntegrationTest` | 2 | Live connection is claimed by the dialect (product-name selection); selected dialect lowers identifiers |
| `TrinoDatabaseUnitTest` | 18 | Dialect properties without a stand: short name, priority, current date-time function, lower schema/catalog case, no sequences, no DDL in transaction, default driver for Trino URL only, table/schema/column lower-casing, `caseSensitive = false`, product name, reserved-word lookup without version query, `setAutoCommit` ignored |

### SQL generators — `sqlgenerator` package (1 class, 16 tests)

| Test class | Tests | What it verifies |
| --- | ---: | --- |
| `TrinoSqlGeneratorsUnitTest` | 16 | Each generator applies only to `Trino` and uses Trino priority; lock table has no `PRIMARY KEY` and uses `TIMESTAMP`; `SELECT` keeps column list lower-case, supports `WHERE`/`ORDER BY`, applies `LIMIT`; `ADD COLUMN` includes the keyword, keeps `NOT NULL`/`DEFAULT`; `RENAME COLUMN` uses Trino syntax |

## Known limitations recorded at baseline

- The stand is brought up via `docker-compose` (`src/test/resources/docker/` +
  `run-tests.sh`), following the harness convention
  (`src/test/resources/docker/docker-compose-<VERSION>.yml`). Testcontainers are **out of
  scope** for this plan.
- The 18 golden cases are now harness resources under
  `src/test/resources/liquibase/harness/change/{changelogs,expectedSql}/trino/`; the Java
  `TrinoGoldenSqlTest` and `liquibase/ext/trino/golden/` are gone.
- The default harness changelogs are inherited: `expectedSql/trino/` also carries a file
  per default changelog, `INVALID TEST -- <reason>` for the changetypes Trino lacks
  (PK/FK/index/unique/sequence/procedure/trigger/defaults/…). `1initScript` seeds the
  `authors`/`posts` tables those defaults operate on.
- `TrinoTestSupport.withoutLiquibaseBookkeeping()` was removed with the golden test; the
  harness strips Liquibase bookkeeping itself in `TestUtils.parseQuery()`.
- `FastCheckService.clearCache()` workaround (Liquibase 5.0.4 bug) stays regardless
  of the harness migration.

## Harness migration outcome (stages 4–5)

The plan's stages 4 and 5 landed `src/test/groovy/liquibase/ext/trino/ContributedHarnessSuiteIT.groovy`
(`extends BaseHarnessSuite`, no annotations — the base class already `@SelectClasses([ChangeObjectTests, ChangeDataTests])`).
It runs in the **default** build; the `unit` profile excludes it via `<exclude>**/*SuiteIT.java</exclude>`.

- **`ChangeObjectTests` — 68 run, 45 skipped, 0 failed.** The 45 skips are `INVALID TEST`
  expectedSql entries (unchanged); the 23 executed cases compare generated vs expected SQL AND
  (because `harness-config.yml` sets `revalidateSql: true`) apply the changeset and run the
  rollback. Rollback chain fixes were required in `renameTable.xml`, `dropTable.xml`, `dropView.xml`;
  `createTableTimestamp.json`/`setTableRemarks.json` were added as `{}` to shadow non-empty root
  defaults. `revalidateSql: false` would silently skip `update`/snapshot/resultset — it must stay `true`.
- **`ChangeDataTests` — 5 run, 1 skipped, 0 failed.** Self-contained cases under
  `liquibase/harness/data/{changelogs,expectedSql,checkingSql,expectedResultSet}/trino/`:
  `insert`/`delete` (byte-identical copies of the change-changelogs, not moves — see the note
  below), `loadData` (CSV into `INT`/`VARCHAR` only —
  non-prepared `loadData` emits `DATE`/`TIMESTAMP` as quoted strings Trino rejects),
  `loadUpdateData` (`INVALID TEST` — core emits `MERGE INTO ... KEY(id)`, unparseable by Trino).
  Each executed case verifies the result set through `checkingSql` + `expectedResultSet`.
- **Builds:** `mvn -Punit test` = 47/0/0 (no stand, no socket); full `mvn test` with the stand
  green; `TrinoHarnessConnectionSpec` asserts `revalidateSql == true` (guards the above).

This section supersedes the baseline numbers above for the harness portion; the `changelog`,
`snapshot`, `database` and `sqlgenerator` package counts are unchanged.

Note on `insert`/`delete`: the `data/changelogs/trino/{insert,delete}.xml` files are
**copies** of `change/changelogs/trino/{insert,delete}.xml`, not moves. Both suites therefore
execute the same `insert_t`/`delete_t` objects: `ChangeObjectTests` checks generated SQL,
`ChangeDataTests` adds the result-set check via `checkingSql`/`expectedResultSet`. The plan
worded stage 5 as "move"; keeping both is deliberate, because moving would drop `insert`/
`delete` from `ChangeObjectTests`. The two sets do not interfere — harness isolates cases with
`rollback --date`, and suite order does not matter.

## Harness migration outcome (stages 6–8)

### Snapshot + generateChangelog — `AdvancedHarnessSuiteIT`

`src/test/groovy/liquibase/ext/trino/AdvancedHarnessSuiteIT.groovy`
(`@SelectClasses([SnapshotObjectTests, GenerateChangelogTest])`; the annotation overrides the
base suite's selection, so the composition is explicit).

- **`SnapshotObjectTests` — 9 run, 4 skipped, 0 failed.** The 8 common changelogs plus the
  Trino `addColumn.xml` override. `addPrimaryKey` / `addUniqueConstraint` /
  `addForeignKeyConstraint` / `createIndex` are `INVALID TEST -- …` in
  `snapshot/expectedSnapshot/trino/<name>.json` (the sentinel is checked before `update`).
  `addColumn` is overridden because the common changelog assigns `valueDate` as a quoted
  string, which Trino's strict typing rejects (same core cause as `loadData`). For
  `createTable`/`createView`/`snapshotCatalogAndSchema` the extra catalog-qualified
  `trino.ddl` attribute is pinned with a regex — that is where verbatim `SHOW CREATE`
  survives, since `GeneralSnapshotComparator` matches strings as regex.
- **`GenerateChangelogTest` — 13 run, 10 skipped, 0 failed.** A `generateChangelog/expectedSql/trino/<name>.sql`
  exists per input changelog: `INVALID TEST` for the unsupported types and `addColumn`, a real
  file for `createTable`/`createView`; the `createView` input changelog is additionally
  overridden (the common one reads the absent harness table `authors`).
- **Caveat.** For xml/yml/json the spec does **not** compare the generated changelog's
  content — it only runs generation and checks the stored-object directories (Trino has
  none); the SQL-format verification is commented out upstream. The verbatim round-trip
  "generated changelog replays the same DDL" is therefore **not** covered by the harness and
  stays in `TrinoGenerateChangelogIntegrationTest`. `TrinoSnapshotCommandIntegrationTest`,
  `TrinoMetadataSnapshotIntegrationTest` and `TrinoSchemaSnapshotIntegrationTest` also remain
  (plugin-specific table/schema discovery, incl. cross-catalog). They stay as Java classes —
  the acceptance section below records the final shape.

### `diff` / `diffChangelog` — not migrated (plan stage 7 deviation)

Both harness specs are unexecutable against Trino; the behaviour stays in
`TrinoDiffIntegrationTest` / `TrinoDiffChangelogIntegrationTest`:

- `liquibase.harness.compatibility.advanced.AdvancedTest` (whose resource layout —
  `initSql/{primary,secondary}`, `expectedDiff/<db>/diff.txt` — the plan quotes) calls
  `dropAll`. Liquibase core emits `DROP TABLE … CASCADE`, which Trino rejects; `dropAll` is
  unsupported (README). Verified with a `CommandScope("dropAll")` probe on the stand.
- `liquibase.harness.diff.DiffTests` / `DiffChangelogTests` match target/reference by
  `name`+`version` from `harness-config.yml`. A self-diff needs a second `trino` entry, but
  two same-name/same-version entries collapse to one in `buildTestInput`; separating them
  requires a fake version, which makes every other suite iterate an uninitialized stand.

Even if runnable, such a spec pins only literal diff text and cannot re-express what the Java
tests assert: verbatim `SHOW CREATE`, a difference visible only in `trino.ddl`, "changed table
yields an empty changelog", and the unqualified DROP for an unexpected table.

### Accepted coverage shape

The 18 change cases carry `expectedSnapshot/trino/*.json = {}` — the harness's sanctioned
"skip snapshot verification". Snapshot structure is asserted only in the stage-6 suite, so
`trino.ddl` (the plugin's core value) is proven for `createTable`/`createView` there, not per
change case. This is a deliberate KISS trade-off, not a gap to close in this plan; stage 10's
JaCoCo before/after records it.

### Groovy/Spock specs — the 7 migrated classes (plan stage 8)

The last Java integration classes that the harness cannot express moved to Groovy/Spock under
one base, `TrinoIntegrationSpec` (`@Tag("integration")` +
`@IgnoreIf(value = { !TrinoTestSupport.isReachable() }, inherited = true)`). `@IgnoreIf` is the
one that has to be marked inherited — Spock's `@IgnoreIf` defaults its own `inherited` flag to
`false`, so without it a subclass runs against a down stand and errors instead of skipping.

- `changelog/{TrinoChangeLogUpdate,TrinoChangelogFormat,TrinoReadCommands,TrinoRollback}Spec.groovy`
  replace the four `*IntegrationTest` classes;
- `snapshot/{TrinoSnapshotFailure,TrinoDiff,TrinoDiffChangelog}Spec.groovy` replace the three
  `*IntegrationTest` classes (diff per the stage-7 deviation);
- `harness/TrinoHarnessConnectionSpec` now extends the same base.

The capabilities and counts are unchanged (see the tables above); only the language and class
names differ. `TrinoTestSupport` still holds `snapshotJson` / `generateChangelog*` — stage 10
decided to keep them: the four remaining `snapshot` Java classes are their callers, and
migrating those first would be a rewrite stage of its own, out of scope for acceptance.

### Totals

Full `mvn test` with the stand: **210 run, 0 failed, 0 errors, 60 skipped** (45 change +
1 data + 4 snapshot + 10 generateChangelog `INVALID TEST`). `mvn -Punit test` = **47/0/0**,
no stand.

The 210 is **2 more than the 208 logical tests** the migration preserved, and the surplus is a
reporting artifact, not extra assertions: each Spock data-driven `where:` feature emits an extra
zero-assertion container node in the surefire XML (here the XML/YAML/JSON format feature and the
command × object-kind warning feature). Stage 10's before/after comparison should read the
logical count, not the raw surefire number.

## Stage 10 acceptance — coverage before/after

Full `mvn test` against the stand after the migration: **210 run / 0 failed / 0 errors /
60 skipped** (the skipped are the harness `INVALID TEST` cases — 45 change + 1 data +
4 snapshot + 10 generateChangelog). `mvn -Punit test` without the stand: **47/0/0**
(19 `TrinoDatabaseUnitTest` + 16 `TrinoSqlGeneratorsUnitTest` + 12 `TrinoDdlFetcherUnitTest`).
The pre-migration baseline was **124 tests, 78 of them tagged `integration`**.

JaCoCo from `target/site/jacoco/jacoco.csv` (run of 2026-10-15, all suites in one session),
each class against its baseline row above:

| Main class | Instr % baseline → now | Branch % baseline → now | Verdict |
| --- | --- | --- | --- |
| `database.TrinoDatabase` | 78.6% → 81.6% | 54.5% → 64.6% | improved |
| `diff.TrinoDdlChangeGenerator` | 89.7% → 89.7% | 80.0% → 80.0% | equal |
| `snapshot.TrinoDdlFetcher` | 97.0% → 97.0% | 85.3% → 85.3% | equal |
| `snapshot.TrinoSchemaSnapshotGenerator` | 89.0% → 89.0% | 75.0% → 75.0% | equal |
| `snapshot.TrinoTableSnapshotGenerator` | 100.0% → 100.0% | 100.0% → 100.0% | equal |
| `snapshot.TrinoViewSnapshotGenerator` | 96.4% → 96.4% | 87.5% → 87.5% | equal |
| `sqlgenerator.TrinoAddColumnGenerator` | 79.3% → 79.3% | 50.0% → 50.0% | equal |
| `sqlgenerator.TrinoCreateDatabaseChangeLogLockTableGenerator` | 100.0% → 100.0% | n/a | equal |
| `sqlgenerator.TrinoRenameColumnGenerator` | 100.0% → 100.0% | n/a | equal |
| `sqlgenerator.TrinoSelectFromDatabaseChangeLogGenerator` | 93.0% → 93.0% | 75.0% → 75.0% | equal |
| `database.TrinoJdbcConnection` | no baseline → 100.0% | n/a | new row, see below |
| **bundle (same 10 classes)** | **89.6% → 90.4%** | **73.6% → 76.3%** | improved |

The acceptance rule holds: no class dropped below its baseline instruction or branch
coverage; the only mover is `TrinoDatabase` (69 instr / 17 branch missed against the
baseline 77 / 20).

**The 11th class.** The baseline table lists 10 classes, but `src/main` has 11:
`database.TrinoJdbcConnection` already existed in the baseline commit and was never given
a baseline row. It is recorded here as a new row at 100% instruction coverage — no branch
code to cover, so no branch column. The bundle totals above therefore compare the same 10
classes on both sides (current bundle over all 11 classes is 90.5% instruction; the branch
figure is identical).

