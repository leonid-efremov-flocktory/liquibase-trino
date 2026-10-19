# Liquibase Trino Plugin

A Liquibase extension that lets Liquibase manage DDL migrations against a data warehouse
built on [Trino](https://trino.io/) and [Apache Iceberg](https://iceberg.apache.org/).

Liquibase picks a `Database` implementation based solely on the JDBC connection's
product name (`DatabaseMetaData.getDatabaseProductName()` — the Trino driver reports
`Trino`), and the `liquibase.databaseClass` setting does not force that choice. There is
no built-in Trino support, so we need a class that:

1. is recognized as the correct implementation for a Trino connection
   (`isCorrectDatabaseImplementation()`);
2. is as close to Trino's dialect as possible, so that the `DATABASECHANGELOG` /
   `DATABASECHANGELOGLOCK` tracking tables and the queries against them are valid.

PostgreSQL is the nearest dialect, but it cannot be used: Liquibase runs
`SHOW SEARCH_PATH` / `SET SEARCH_PATH` on connect, and Trino has no such thing.
**H2** lacks that init SQL and provides type mappings that are all valid in Trino:
`datetime`→`TIMESTAMP`, `boolean`→`BOOLEAN`, `int`→`INT`, `varchar`→`VARCHAR`.

`H2Database` ships inside `liquibase-core`; all this plugin adds on top is the H2 JDBC driver.

## Supported commands

| Command | Supported | Notes |
| --- | --- | --- |
| `update` / `migrate` | yes | Applies XML, YAML and JSON changelogs, `<sql>`, `<sqlFile>`, and the DDL and data change types pinned by the golden tests. |
| `rollback` / `rollback-sql` | yes | Rollback blocks in changelogs work; `TrinoRollbackSpec` parses the fixture and executes the generated SQL on the stand. |
| `generate-changelog` | yes | Emits verbatim DDL as raw SQL, not as `<createTable>` — see [below](#snapshot-and-generate-changelog) for the schema argument and the required `.trino.sql` output name. |
| `status`, `history`, `tag`, `tagExists` | yes | Go through `TrinoSelectFromDatabaseChangeLogGenerator`. |
| `update-sql` | yes | Used by the golden tests. |
| `snapshot` | yes | Emits the verbatim DDL as the `trino.ddl` attribute on tables and views. Needs the schema named — see below. |
| `diff`, `diffChangelog` | partially | A difference confined to `trino.ddl` is detected and reported, but no change is generated for it. Covered by `TrinoDiffSpec` and `TrinoDiffChangelogSpec`; see [Known limitations](#known-limitations). |
| `dropAll` | no | Reads foreign keys through the JDBC metadata API, which Trino does not have. |

## Dependencies

All dependencies live in `pom.xml` — the single source of truth:

| Dependency | Scope | Why |
|---|---|---|
| `io.trino:trino-jdbc:464` | compile | Trino JDBC driver; reports product name `Trino` |
| `com.h2database:h2:2.4.240` | compile | H2 driver for `H2Database`, which `TrinoDatabase` extends |
| `org.liquibase:liquibase-core:5.0.4` | provided | Supplied by Liquibase itself, not bundled |
| `org.junit.jupiter:junit-jupiter:6.1.0` | test | Unit tests |
| `org.junit.platform:junit-platform-suite:6.1.0` | test | `@SelectClasses` suites that bind each harness level to one class |
| `org.spockframework:spock-core:2.4-groovy-5.0` | test | The integration specs the harness cannot express |
| `org.apache.groovy:groovy:5.0.6` | test | Groovy runtime+compiler for the specs above (`groovy-json` alongside) |

> **Note on h2.** The Liquibase 5.0.4 distribution already ships `internal/lib/h2.jar` at
> the same version (2.4.240). When building an image, h2 must be excluded from the
> copied dependencies — otherwise two H2 drivers end up on the classpath and Liquibase
> prints `*** Duplicate JAR files ***` on every start.

## Installation

Add to the consumer's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.leonid-efremov-flocktory</groupId>
    <artifactId>liquibase-trino</artifactId>
    <version>0.1.2</version>
</dependency>
```

Or with a single Maven invocation:

```bash
mvn dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DexcludeArtifactIds=h2 \
    -Dartifact=io.github.leonid-efremov-flocktory:liquibase-trino:0.1.2
```

Liquibase loads every jar from `internal/lib` and `internal/extensions`, so no separate
plugin registration is required.

## Build

```bash
mvn package
```

Requires JDK 17.

## Release

Releases are driven by tags. Pushing `v*` runs
[`.github/workflows/test.yml`](.github/workflows/test.yml), which:

1. verifies the tag matches the `<version>` in `pom.xml`;
2. runs `./run-tests.sh` — brings the stand up, waits until Trino answers `SELECT 1`,
   runs `mvn test`, tears the stand down.

```bash
# git tag -f
git tag v0.2.2 && git push origin main && git push origin v0.2.2
```

## Tests

Integration tests need the stand from `src/test/resources/docker/docker-compose-464.yml`
(catalog `iceberg_catalog`, port 8081):

```bash
./run-tests.sh          # start the stand, run the suite, stop it
./run-tests.sh up       # only start it (e.g. for manual debugging)
./run-tests.sh down     # stop it
./run-tests.sh test     # run the tests against an already running stand
./run-tests.sh ps       # show the objects created by the migrations
```

Extra arguments after the command are passed through to Maven, e.g.
`./run-tests.sh test -Dtest=TrinoRollbackSpec`. If Maven is installed on the host
the script uses it, otherwise it runs the build in a `maven:3.9-eclipse-temurin-17` container
(Trino is reachable there via `host.docker.internal`). The URL and user can be overridden
with `TRINO_TEST_URL` / `TRINO_TEST_USER`; a `TRINO_TEST_URL` override replaces one of the
defaults above and must name the catalog on a stand that is already running.

**210 tests in total: 150 execute and 60 are skipped** (the harness cases pinned as
`INVALID TEST` for change types Trino does not support). Of the 150, 47 need no stand —
take well under a second and cover the dialect and SQL generators from H2 only:

```bash
./run-tests.sh test -Punit      # or: mvn test -Punit
```

(The reported 210 counts two extra Spock containers for data-driven `where:` blocks;
logically the suite is 208.)

The split is by tag, not by file name: every test needing the stand carries
`@Tag("integration")` (the Spock specs inherit it from `TrinoIntegrationSpec`), and `-Punit`
excludes that tag. Name-based matching (`**/*IntegrationTest.java`) missed a class whose
name does not advertise the dependency — `TrinoTableSnapshotGeneratorUnitTest` — so
`-Punit` quietly needed docker. The two suite classes (`*HarnessSuiteIT`) carry no tag and
are skipped by file name for the opposite reason: a JUnit Platform suite is a shell that
would otherwise drag the official harness into a "unit" run. `@EnabledIf` and the Spock
`@IgnoreIf` still guard the default build, where no stand is running: such tests skip rather
than fail.

All command-level tests share a single fixture,
`src/test/resources/liquibase/ext/trino/test-changelog.xml`.

Tests live in two languages: unit tests in `src/test/java` (JUnit 5), and the integration
specs the harness cannot express in `src/test/groovy` (Spock, under the shared base
`TrinoIntegrationSpec`):

```
src/test/java/liquibase/ext/trino/
├── TrinoTestSupport.java          # shared fixture plumbing: connection, reset, state readers
├── database/                      # the dialect: properties, and picking it from a connection
├── sqlgenerator/                  # the SQL the generators emit
└── snapshot/                      # snapshot discovery; the snapshot/generate-changelog commands

src/test/groovy/liquibase/ext/trino/
├── TrinoIntegrationSpec.groovy    # the Spock base: @Tag("integration") + stand gate
├── *HarnessSuiteIT.groovy         # the two Liquibase Test Harness suites under test
├── changelog/                     # update, rollback, status/history/tag, changelog formats
├── snapshot/                      # snapshot failure, diff and diffChangelog
└── harness/                       # the dbUrl seam between run-tests.sh and harness-config.yml
```

### The Liquibase Test Harness layer

The dialect is verified by the official
[Liquibase Test Harness](https://github.com/liquibase/liquibase-test-harness), at two
verification levels — Contributed (`ChangeObjectTests`, `ChangeDataTests`) and Advanced
(`SnapshotObjectTests`, `GenerateChangelogTest`). The resources follow the harness
convention: under `src/test/resources/liquibase/harness/`, one directory per suite with a
`trino/` subdirectory for per-database expectations — changelogs to apply, expected SQL
files, expected snapshots and result sets.

The 18 golden cases (one per supported change type, the SQL pinned verbatim) live in the
harness's `change/` suite now. `expectedSql/trino/*.sql` replaces the old golden layer;
cases for change types Trino rejects are pinned as `INVALID TEST -- …` in the expected
file, which the harness treats as "known unsupported, skip". GitHub runs each level as its
own matrix job on every pull request
([`.github/workflows/test-harness.yml`](.github/workflows/test-harness.yml)), while
pushing a tag runs the full suite (see [Release](#release)).

The commands the harness cannot express — its diff specs need `dropAll` (Trino refuses
`CASCADE`), and its `rollback`/`read-commands`/changelog-format/`DATABASECHANGELOGLOCK`
probes are Liquibase-version specific — stay as tailor-made Spock specs under `changelog/`
and `snapshot/`.

### Test stand

Services in `src/test/resources/docker/docker-compose-464.yml`:

| Service | Image | Role |
| --- | --- | --- |
| `silo` | `pgsty/silo` | S3-compatible object storage (a maintained MinIO fork) |
| `silo-init` | `pgsty/silo` | one-shot init job that creates the `warehouse` bucket via `mcli`, then exits |
| `iceberg-rest` | `apache/iceberg-rest-fixture` | Iceberg REST catalog over a SQLite-backed `JdbcCatalog` |
| `trino` | `trinodb/trino:464` | the Trino cluster under test |

## Known limitations

- **The schema has to be named** for `snapshot` and `generate-changelog`, and getting it
  wrong is silent — see [above](#snapshot-and-generate-changelog).
- **Verbatim DDL cannot express a difference that Trino does not report.** The snapshot is
  whatever `SHOW CREATE` said, so anything Trino does not print is not in the changelog. In
  practice this means the unsupported constraint types below: an Iceberg table's constraints
  are not recoverable from the DDL text.
- **A generated changelog only replays against the same catalog and schema.** The DDL carries
  `location = 's3://…'`, so replaying it elsewhere points the table at the original storage.
- **Rollback for a generated changeset drops the object.** A generated table or view changeset
  carries no rollback of its own, so `rollback` on one drops what the DDL created rather than
  restoring a previous definition. Changelog authors who need a restorable migration have to write
  the rollback block themselves.
- **Snapshotting more than one schema works, but only inside one catalog.** `--schemas` takes a
  comma-separated list and all of them come back in one snapshot — `--schemas=a,b` returns both
  `a` and `b` with their tables. Every name in that list, however, is
  resolved in the connection's default catalog: Liquibase builds each entry as
  `new CatalogAndSchema(null, name)` and lets the database fill the catalog in, so
  `--schemas=system.runtime` looks for `iceberg_catalog.runtime` and finds nothing. There is no
  way to snapshot across catalogs, and no API for "every schema this connection can see" — the
  schemas have to be enumerated.
- **`update` after `rollback` in the same JVM can silently do nothing.** Liquibase 5 added a
  fast path to `update`: before taking the lock, `AbstractUpdateCommandStep` asks
  `FastCheckService` whether there is anything to run. That service caches its answer per
  JVM under a key of `contexts/labels/schema/catalog/URL/changelog path`, and `rollback`
  never invalidates it (`UpdateCommandStep.cleanUp()` resets the history service only, and
  `RollbackCommandStep` does not touch fast-check at all). The result is a sequence like
  *update → no-op update → rollback → update*, where the final `update` prints
  `Database is up to date, no changesets to execute`, never acquires the lock, and applies
  nothing — while the summary printed by that same command reports `Run: 2`. A missing
  `Successfully acquired change log lock` line in the log is the tell, and it holds in
  Liquibase 5.0.4 and on `master` as well.

  `TrinoTestSupport` works around it by calling `FastCheckService.clearCache()` before each
  `update`/`rollback`, and `applyIfNeeded()` verifies the resulting `DATABASECHANGELOG` row
  count through plain JDBC so a no-op update fails loudly instead of leaving the stand rolled
  back. This is a Liquibase bug rather than a plugin one and reproduces without this plugin.
  The sequence `update → rollback → update` is therefore **not** covered by a test: it is
  Liquibase's bug, and the workaround is verified indirectly, by `applyIfNeeded()`.

  Do not read `Run: N` as "N changesets executed". Without a `DefaultChangeExecListener`,
  Liquibase fills it from `statusVisitor.getChangeSetsToRun().size()` — the changesets it
  *expects* to run. Assert on the database, not on the summary.