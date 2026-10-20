# Main concept: Trino dialect for Liquibase via an H2-based shim

| | |
|---|---|
| **Status** | ACCEPTED |
| **Date** | 2026-10-20 |
| **Project** | `liquibase-trino` 0.2.2 — Liquibase 5.0.4, Trino 464 |
| **Decision** | Implement the Trino dialect as `TrinoDatabase extends H2Database`, overriding the parts of H2 that Trino rejects. |
| **Supersedes** | — |

This ADR records the main architectural decision of the plugin and the
alternatives that were considered, so that the choice is not re-litigated from
scratch later. It was written by reading the Liquibase 5.0.4 sources, not from
the plugin's own documentation, and it does not assume the current approach is
correct merely because it already exists.

---

## 1. Context

Liquibase selects a `Database` implementation **solely from the JDBC product
name** (`DatabaseMetaData.getDatabaseProductName()`; the Trino driver reports
`Trino`). The `liquibase.databaseClass` setting does **not** override that
choice. There is no built-in Trino support, so a class is needed that:

1. is recognised as the correct implementation for a Trino connection
   (`isCorrectDatabaseImplementation`);
2. is close enough to Trino's dialect that the `DATABASECHANGELOG` /
   `DATABASECHANGELOGLOCK` tracking tables and every query against them are
   valid;
3. generates SQL that Trino accepts.

Liquibase ships a set of built-in dialects (`H2Database`, `PostgresDatabase`,
`MySQLDatabase`, …) and an abstract base (`AbstractJdbcDatabase`). Any of the
three is a possible starting point:

- **A. H2-based shim** — extend `H2Database` and override what Trino rejects.
- **B. PostgreSQL-based shim** — extend `PostgresDatabase` / `AbstractPostgresDatabase`
  and override what Trino rejects (Postgres is the nearest dialect by meaning).
- **C. Purpose-built dialect** — extend `AbstractJdbcDatabase` directly and
  implement everything explicitly.

The project's stated goal is: *implement Trino + Liquibase with minimal effort,
but with quality and the full feature set*. "Minimal effort" is the primary
constraint; "quality / full feature set" is the secondary one. The decision
below is evaluated against both.

---

## 2. Decision

**Go with option A — the H2-based shim** — as it is implemented today:

```
TrinoDatabase extends H2Database
  ├── database/TrinoJdbcConnection.java          (refuses setAutoCommit(false))
  ├── sqlgenerator/  (4 generators)
  ├── snapshot/      (4 generators + DDL fetcher)
  └── diff/          (1 change generator)
```

`H2Database` already ships inside `liquibase-core`, so the plugin only adds the
H2 JDBC driver on top. The decision is accepted, not proposed, because the
plugin is already implemented and verified by 210 tests against a live Trino
stand (see §6). This ADR fixes the *rationale* and the *cost* of that choice.

---

## 3. Options considered

### 3.1 A. H2-based shim (selected)

**What is inherited for free.** `H2Database` is a complete dialect: quoting,
identifier case handling, date/time handling, date functions, sequence and
auto-increment plumbing, snapshot participation, datatype mapping. Almost none
of that has to be written.

**Why H2 fits Trino better than Postgres at the connection layer.** The decisive
fact is in `DatabaseUtils.initializeDatabase`
(`liquibase/database/core/DatabaseUtils.java:40-74`): it branches on
`database instanceof PostgresDatabase` and, for Postgres, runs
`SHOW SEARCH_PATH` and then `SET SEARCH_PATH TO …` on **every command**. Trino
has neither statement, so a Postgres-derived dialect would break the very first
`update`. No branch matches `H2Database`, so an H2-derived dialect runs **no
init SQL at all**. This is the single strongest argument for H2 over Postgres.

**Type mappings.** The standard H2 branches emit types that are also valid in
Trino:

| Liquibase type | H2 branch emits | Trino |
|---|---|---|
| `int` | `INT` | ✅ |
| `bigint` | `BIGINT` | ✅ |
| `smallint` | `SMALLINT` | ✅ |
| `tinyint` | `TINYINT` | ✅ |
| `boolean` | `BOOLEAN` | ✅ |
| `datetime` | `TIMESTAMP` | ✅ |
| `decimal` | `decimal` | ✅ |
| `varchar` | `varchar` | ✅ |

**What has to be overridden anyway.** H2 is not Trino, so the shim neutralises
a long list of inherited behaviour:

| Inherited from H2 | Why it breaks on Trino | Override |
|---|---|---|
| `unquotedObjectsAreUppercased = true` | Trino folds unquoted identifiers to lower case | constructor sets `false` |
| `setConnection()` runs `SELECT SCHEMA()` | Trino has no `schema()` function | bypass via `MethodHandle` to `AbstractJdbcDatabase.setConnection` |
| `getConnectionSchemaName()` returns `"PUBLIC"` | Trino has no `PUBLIC` schema; `super` runs `CALL current_schema` which Trino lacks | read `SELECT current_schema` |
| `getReservedWords()` calls `getDatabaseMajorVersion()` | the Trino driver answers by running `SELECT version()`, once per escaped identifier | return `V2_RESERVED_WORDS` directly |
| `getSchemaAndCatalogCase()` = `UPPER_CASE` | Trino metadata filters are case-sensitive and lower-case | `LOWER_CASE` |
| `escapeColumnName()` upper-cases | tracking-table columns are stored lower-case | lower-case both overloads |
| `supportsSequences()` = `true` | Trino has no sequences | `false` |
| `supports(Index/PK/FK/UniqueConstraint)` | Trino enforces none and the JDBC metadata calls fail | `false` |
| `setAutoCommit(false)` | the Trino driver rejects it | no-op + `TrinoJdbcConnection` wrapper |

**SQL-generator fixes** (4 classes): lock table without `PRIMARY KEY` and with
`TIMESTAMP` instead of `datetime`; `SELECT` from the changelog keeps its column
list lower-case and gains a plain `LIMIT`; `ADD COLUMN` gains the `COLUMN`
keyword; `RENAME COLUMN old TO new` replaces H2's `ALTER COLUMN … RENAME TO`.

**Snapshot / diff fixes** (5 classes): schema lookup with explicit
case-insensitive catalog+schema matching; verbatim `SHOW CREATE TABLE/VIEW`
captured as the `trino.ddl` attribute; per-object failure confinement; a diff
change generator that emits the verbatim statement instead of a lossy
`<createTable>`.

### 3.2 B. PostgreSQL-based shim

Postgres is the nearest dialect **by meaning** (common SQL, lower-case folding,
`current_schema`), and it would inherit several things that H2 gets wrong:

- `unquotedObjectsAreUppercased = false` and
  `getSchemaAndCatalogCase() == LOWER_CASE` — correct for Trino out of the box
  (`AbstractPostgresDatabase.java:63,174-176`).
- `getDatabaseChangeLogTableName()` / `…LockTableName()` are lower-cased
  (`AbstractPostgresDatabase.java:80-87`) — exactly what Trino needs.
- `escapeObjectName` special-cases `LiquibaseColumn` so tracking-table columns
  are not quoted (`AbstractPostgresDatabase.java:110-119`).
- `getConnectionSchemaNameCallStatement()` = `select current_schema()`
  (`AbstractPostgresDatabase.java:168-171`) — a statement Trino actually has.

But the inheritance is outweighed by what Postgres *activates*, and several of
those activations are **fundamentally incompatible with Trino**:

| Inherited from Postgres | Trino reality | Consequence |
|---|---|---|
| `DatabaseUtils.initializeDatabase` runs `SHOW`/`SET SEARCH_PATH` (`instanceof PostgresDatabase`) | Trino has no `search_path` | every command fails unless neutralised |
| `PostgresDatabase.setConnection()` queries `pg_settings` (`PostgresDatabase.java:137-162`) | Trino has no `pg_settings` | extra query per connect, logged as info |
| `supportsCatalogInObjectName()` = `false` (`AbstractPostgresDatabase.java:75-77`) | Trino names are `catalog.schema.table` | three-part names are dropped unless overridden |
| `supportsDropTableCascadeConstraints()` = `true` (`AbstractJdbcDatabase.java:772-775`) | Trino has no `DROP … CASCADE` | wrong drop SQL |
| `supportsEnumTypeSnapshot/CompositeType/CheckConstraint/StoredLogic` = `true` | Trino supports none | snapshot attempts that must be disabled |
| `SERIAL` / `GENERATED … AS IDENTITY` auto-increment (`PostgresDatabase.java:170-207`) | Trino has neither | wrong DDL for auto-increment changes |
| `isSystemObject` treats `pg_catalog`/`pg_toast` as system schemas | Trino has neither | irrelevant filters, but inherited |
| `ChangeSet.restoreSearchPath` on Postgres | Trino has no `search_path` | wrong rollback path |

`AbstractPostgresDatabase` is branched on in 12+ places across `snapshot`,
`change` and `datatype` (`JdbcSnapshotGenerator`, `IndexSnapshotGenerator`,
`UniqueConstraintSnapshotGenerator`, `ColumnSnapshotGenerator`,
`ColumnAutoIncrementService`, `TableSnapshotGenerator`, `JdbcDatabaseSnapshot`,
`SnapshotGeneratorFactory`, `AddAutoIncrementChange`,
`AddNotNullConstraintChange`, `CreateProcedureChange`, `LoadDataChange`). Each
is a place where the Postgres assumption can silently produce Trino-invalid
SQL. The amount of neutralisation is therefore **at least as large as H2's, and
the neutralisations are more dangerous** because they touch identity, catalogs
and DDL semantics rather than cosmetics.

**Verdict:** PostgreSQL is the worst of the three options for Trino. It is
conceptually close, but `search_path` and the catalog-less model are hard
conflicts, not cosmetic ones.

### 3.3 C. Purpose-built dialect (`AbstractJdbcDatabase`)

Liquibase's own extension guide prescribes this for a database that is "more
unique": extend `AbstractJdbcDatabase` and implement what differs. It would mean
writing explicitly:

- identity: `getShortName`, `getPriority`, `getDefaultDatabaseProductName`,
  `getDefaultDriver`, `getDefaultPort`, `isCorrectDatabaseImplementation`;
- case and quoting: `unquotedObjectsAreUppercased = false`,
  `getSchemaAndCatalogCase() == LOWER_CASE`, lower-cased
  `getDatabaseChangeLogTableName/LockTableName`, lower-cased
  `escapeColumnName`, `isCaseSensitive() == false`;
- session names: `getConnectionCatalogName()` from `current_catalog`,
  `getConnectionSchemaName()` from `current_schema` (and the call statement);
- capabilities: no sequences, no auto-increment, no DDL in transaction, no
  constraints/indexes, catalogs in object names as Trino needs them;
- dialect utilities: reserved words, date functions, `getConcatSql` (`||`),
  date literals;
- the same 9 generator classes as today (lock table, select-from-changelog,
  add/rename column, schema/table/view snapshots, DDL fetcher, DDL change).

The advantage is the mirror image of the shim's weakness: **core takes the
generic path**, so there are no H2 or Postgres branches to inherit. No
`ColumnSnapshotGeneratorH2` activates; no datatype branch emits
`BINARY LARGE OBJECT`; no `search_path` query is issued. Behaviour is fully
predictable and every line is owned.

The cost is real:

- more code to write and to keep in sync with new Liquibase methods;
- **no automatic pickup of upstream fixes.** An H2-derived dialect inherits
  every correction Liquibase makes to `H2Database`; a purpose-built dialect must
  re-apply them by hand;
- a higher chance of omitting a detail that inheritance would have supplied
  silently (the lower-cased changelog table names are the canonical example —
  forget them and `DATABASECHANGELOG` is created in one case and read in
  another).

**Verdict:** objectively the cleanest and most maintainable long-term shape,
but the most expensive to reach, and it does not serve the project's primary
constraint (minimal effort) today. It is a candidate for a future rewrite, not
for the current milestone.

---

## 4. Comparison

| Criterion | A. H2 shim | B. Postgres shim | C. Purpose-built |
|---|---|---|---|
| Effort to a working plugin | **Low** | Medium | **High** |
| Effort to reach the current feature set | **Low** (already done) | Medium–High | High |
| Inherited init SQL on connect | none ✅ | `SHOW`/`SET SEARCH_PATH` ❌ | none ✅ |
| Identifier case correct out of the box | no (override) | **yes** ✅ | no (implement) |
| Catalog-in-object-name model | neutral (override) | **catalog-less** ❌ | full control ✅ |
| Inherited core branches that must be neutralised | ~8 | ~10+ (more severe) | 0 |
| H2/Postgres-specific datatype branches | yes (see §5) | yes (serial/identity) | none ✅ |
| Fragile bypasses | `MethodHandle` to a private method, connection wrapper | search_path + pg_settings + serial | none ✅ |
| Picks up upstream dialect fixes | **yes** ✅ | yes | no ❌ |
| Predictability / auditability | medium | low | **high** ✅ |
| Long-term maintenance burden | medium | **high** | low–medium |
| Dialect fidelity to Trino | medium | medium | **high** |

**Summary.** A wins on implementation effort and on "no init SQL". C wins on
maintainability, predictability and dialect fidelity. B wins on nothing that
matters for Trino and loses on several hard conflicts.

---

## 5. Why H2 was selected — and what it costs

The selection is **not** "H2 because that is what was already built". It rests
on a concrete, verifiable property:

> `DatabaseUtils.initializeDatabase` issues `SHOW SEARCH_PATH` / `SET SEARCH_PATH`
> for any `instanceof PostgresDatabase`, and Trino supports neither statement.
> `H2Database` matches no branch, so an H2-derived dialect needs no init SQL.
> — `liquibase/database/core/DatabaseUtils.java:40-74`

Given the primary constraint (minimal effort) and the secondary one (a working,
feature-complete plugin), H2 is the dialect whose inherited behaviour is
**cheapest to correct**:

- its type branches are mostly Trino-compatible (§3.1 table);
- it has no init SQL;
- it already lives in `liquibase-core`, so there is no extra dialect dependency.

The price of that choice, recorded here so it is not forgotten:

1. **Inheritance is a liability as well as an asset.** `TrinoDatabase` is an
   `H2Database` to every `instanceof H2Database` check in core. Concretely,
   `ColumnSnapshotGeneratorH2` activates and replaces the column snapshot
   generator (`liquibase/snapshot/jvm/ColumnSnapshotGeneratorH2.java:14-16`),
   and H2 branches in `IntType`, `BigIntType`, `SmallIntType`, `TimestampType`,
   `DoubleType`, `NumberType`, `CharType`, `BlobType`, `ClobType`, `TinyIntType`
   and `XMLType` all apply. Most are harmless; a few are not (see §6.3).
2. **The bypasses are fragile.** `setConnection` reaches
   `AbstractJdbcDatabase.setConnection` through
   `MethodHandles.findSpecial`, because Java cannot call a grandparent method and
   `H2Database.setConnection` runs `SELECT SCHEMA()` first
   (`TrinoDatabase.java:71-107`). A private method and its signature are now a
   load-bearing part of this plugin; a Liquibase refactor can break it at
   runtime, not at compile time.
3. **The shim must be actively maintained against Liquibase**, not just against
   Trino. Every upgrade is a chance for an inherited H2 branch to change
   behaviour.

---

## 6. Consequences

### 6.1 Benefits

- A working, feature-complete plugin with minimal code: 11 main classes.
- No init SQL, no extra connect-time queries.
- Standard types map to Trino-valid SQL.
- Full tracking-table support: `update`, `rollback`, `status`, `history`, `tag`,
  `tagExists`, `update-sql`.
- `snapshot` and `generate-changelog` carry the server's own `SHOW CREATE`
  statement verbatim, so connector properties (`partitioning`, `location`,
  `format`, comments) survive.
- Verified by the official Liquibase Test Harness plus tailor-made specs:
  **210 tests, 150 executed, 60 skipped**, JaCoCo 90.4% instruction /
  76.3% branch (see `TEST-COVERAGE.md`).

### 6.2 Costs

- ~25 overrides in `TrinoDatabase` alone, of which roughly half exist only to
  cancel inherited H2 behaviour rather than to add Trino behaviour.
- Two fragile bypasses (`MethodHandle`, connection wrapper).
- The plugin's correctness is coupled to `H2Database` and
  `AbstractJdbcDatabase` internals.

### 6.3 Known gaps inherited from H2 (not covered by tests)

These are H2 datatype branches that emit SQL Trino rejects. They are reachable
through ordinary change types and are **not** pinned by the current suite:

| Liquibase type | H2 emits | Trino |
|---|---|---|
| `blob` | `BINARY LARGE OBJECT` / `BLOB` / `VARBINARY` (`BlobType.java:23-34`) | `BLOB`/`BINARY LARGE OBJECT` unsupported |
| `clob` | `CLOB` / `LONGVARCHAR` (`ClobType.java:97-113`) | unsupported |
| `xml` | `CLOB` for H2 (`XMLType.java:66-71`) | unsupported |
| `char` (large) | `CHARACTER LARGE OBJECT` (`CharType.java:71-73`) | unsupported |

If any of these types is needed, the fix is to register a Trino datatype
mapping or a dedicated generator — i.e. to move toward option C for that type.
This is the clearest early signal that the shim is reaching its limit.

---

## 7. When to revisit this decision

Re-open this ADR (write a new one that supersedes it) when any of the following
holds:

1. A required change type needs a Trino-specific datatype that H2 maps to an
   incompatible SQL type (the §6.3 list).
2. A Liquibase upgrade breaks a bypass (`MethodHandle` target, private field,
   `setConnection` signature) — treat it as a compatibility incident.
3. The number of "neutralise H2" overrides grows faster than the number of
   Trino-specific ones; that is the point where option C becomes cheaper than
   option A.
4. Cross-catalog or catalog-qualified object names become a first-class
   requirement (`supportsCatalogInObjectName` currently inherits `false` from
   `AbstractJdbcDatabase`; Trino itself is three-part).
5. The plugin is promoted to a supported/first-class artifact with a long
   maintenance horizon — at that point the predictability of option C outweighs
   its one-off cost.

---

## 8. Evidence

All Liquibase references are to `org.liquibase:liquibase-core:5.0.4` sources.

- `liquibase/database/core/DatabaseUtils.java:40-74` — `search_path` for
  `instanceof PostgresDatabase`; no branch for `H2Database`.
- `liquibase/database/core/H2Database.java:500-535` — `setConnection()` runs
  `SELECT SCHEMA()`.
- `liquibase/database/core/H2Database.java:232,338-340` — `connectionSchemaName`
  defaults to `"PUBLIC"`.
- `liquibase/database/core/H2Database.java:375-385` — `getReservedWords()` calls
  `getDatabaseMajorVersion()`.
- `liquibase/database/AbstractJdbcDatabase.java:143-165` — `setConnection`
  adjusts auto-commit.
- `liquibase/database/AbstractJdbcDatabase.java:772-775` —
  `supportsDropTableCascadeConstraints` includes `AbstractPostgresDatabase`.
- `liquibase/database/core/AbstractPostgresDatabase.java:75-77,80-87,110-119,168-176`
  — catalog-less model, lower-cased changelog names, `LiquibaseColumn`
  quoting, `current_schema()`.
- `liquibase/database/core/PostgresDatabase.java:137-162,170-207,263-274` —
  `pg_settings` query, serial/identity, search-path restore.
- `liquibase/datatype/core/{BlobType,ClobType,XMLType,CharType}.java` — H2
  branches emitting Trino-invalid types.
- `liquibase/snapshot/jvm/ColumnSnapshotGeneratorH2.java:14-16` — activates on
  `instanceof H2Database`.
- Plugin-side: `src/main/java/liquibase/ext/trino/database/TrinoDatabase.java`,
  `TrinoJdbcConnection.java`; `TEST-COVERAGE.md`; `README.md`.
- Liquibase extension guide, "Add a Database", milestone 1 step 1 — the two
  sanctioned starting points (subclass a compatible dialect, or extend
  `AbstractJdbcDatabase`).

---

## 9. References

- `README.md` — supported commands, dependencies, known limitations.
- `description.md` — the per-method rationale for each override.
- `TEST-COVERAGE.md` — test counts and coverage baseline.
- `.opencode/plan.md` — the test-suite rewrite that produced the harness layer.
- Liquibase "Add a Database" guide:
  <https://contribute.liquibase.com/extensions-integrations/extension-guides/add-a-database/>
