# SQL completion stages

Each stage is compiled and tested before starting the next. Earlier assertion
counts record the gates at that point; current counts come from the runner. Final verification
also runs the complete regression suite. Supported forms and limits are tracked
here and in SQL_COMPATIBILITY.md; this is not a claim of full SQLite parity.

## 1. Schema changes — verified

Implemented ALTER TABLE ADD [COLUMN], RENAME [COLUMN], and RENAME TO. Added columns
populate existing rows with adjusted defaults and validate their constraints before
writing. Renames update CHECK identifiers and incoming/outgoing foreign-key
references, preserving literals. Catalog version 4 separates row encoding from
schema version so original and version 1/2/3 data need not migrate their keys.

Validation: clean compilation and parser regeneration, 518 engine assertions,
43 constraint assertions, 107 adapter assertions, 126 existing SQLite comparisons,
and 50 schema SQLite comparisons including a new JVM. Tests cover rollback,
blocked changes, defaults, references, hidden-name shadowing, and original-format
data. Added CHECK validation has its own regression because Python's bundled
SQLite 3.35 predates SQLite 3.37's existing-row validation for added CHECKs.

DROP COLUMN was left to the follow-up [schema work](SCHEMA_CHANGES.md), now
implemented. Changing arbitrary column types/constraints remains unsupported.

## 2. Indexes and access paths — verified

Implemented CREATE [UNIQUE] INDEX, DROP INDEX, IF [NOT] EXISTS, and EXPLAIN QUERY
PLAN. Berkeley DB secondary indexes are populated from existing rows and maintain
themselves transactionally on writes. The planner uses equality indexes for
constant/bound values and previously bound join inputs; other predicates retain
scans. UNIQUE indexes enforce non-null uniqueness and can back referenced keys.
Renames, added columns, rollback, table deletion, and restart preserve index state.

Validation: compilation, prior stage gates, 41 index SQLite comparisons, and
IndexTests verifying SEARCH plans and exactly one scanned row for a lookup in a
100-row table. Read-only adapter discovery includes index definitions and permits
EXPLAIN QUERY PLAN under the same table policy. Range/order index optimization,
expression/partial/composite indexes, and cost-based planning remain future work.

## 3. Query composition — verified

Implemented scalar and correlated subqueries, EXISTS/NOT EXISTS, IN/NOT IN queries,
derived FROM tables, ordinary WITH CTEs (including column lists and nested scopes),
and UNION/UNION ALL/INTERSECT/EXCEPT with final ORDER BY/LIMIT. Inner reads share
the statement transaction and resource budgets; preview limits apply only to the
final result. Column affinity and types flow through derived tables and CTEs.

Validation: prior gates plus 41 composition SQLite comparisons and QueryTests for
nested table authorization, parameter binding, empty-result types, and complete
CTE/compound intermediates under a one-row preview. CHECK still rejects subqueries.
Recursive CTEs are rejected explicitly; window functions and lateral joins remain
outside this stage.

## 4. Expressions and functions — verified

Implemented searched/simple CASE, CAST to INTEGER/INT, REAL, TEXT/CHAR/VARCHAR,
and NUMERIC, lazy COALESCE/IFNULL/IIF, NULLIF, ABS, ROUND, scalar MIN/MAX, LENGTH,
ASCII LOWER/UPPER, TRIM/LTRIM/RTRIM, Unicode SUBSTR/SUBSTRING and INSTR, REPLACE,
TYPEOF, and QUOTE. DATE/TIME/DATETIME/STRFTIME/JULIANDAY/UNIXEPOCH support UTC ISO
inputs, Julian/Unix inputs, start-of modifiers, and day/time/integral month/year
shifts. CURRENT_DATE/TIME/TIMESTAMP share a statement clock. CHECK rejects clock-
dependent evaluations; unknown functions and invalid arities fail at parse time.

Validation: prior gates plus 77 expression SQLite comparisons (including 42
substring boundary combinations), UTC epoch API checks, lazy error branches,
integer overflow/clamping, nulls, Unicode, grouping, nested authorization, and
typed scalar results. BLOB casts, full SQLite formatting/collation behavior,
local-time/subsecond/auto/floor/ceiling modifiers, fractional month/year shifts,
and additional strftime directives remain unsupported.

## 5. Broader writes — verified

Implemented INSERT ... SELECT (including WITH-prefixed writes), a single ON
CONFLICT clause with DO NOTHING or DO UPDATE/WHERE and excluded values, and
RETURNING for INSERT/UPDATE/DELETE. Conflicts are simulated per input row;
prepared change sets and RETURNING evaluation precede storage writes. Correlated
read expressions also work in UPDATE and DELETE. Only directly affected rows
contribute to RETURNING and affected counts. Read-only policy rejects write ASTs
even when prefixed with WITH or followed by RETURNING.

Validation: all earlier gates plus 49 write SQLite comparisons, covering batch
conflicts, unique indexes, ignored input IDs, CHECK failures, source snapshots,
upsert WHERE, generated IDs, returned row images, and rollback after projection
errors. Tests use SQLite changes() because the bundled Python/SQLite combination
misreports INSERT RETURNING cursor.rowcount. Multiple ON CONFLICT clauses,
INSERT OR REPLACE/IGNORE, and RETURNING aggregates/subqueries remain unsupported.

## 6. Composite integrity, referential actions, and savepoints — verified

Implemented ordered/composite PRIMARY KEY, UNIQUE, and foreign keys; ON DELETE
and ON UPDATE NO ACTION/RESTRICT/CASCADE/SET NULL/SET DEFAULT; and immediate or
deferred references. Cascades expand into a validated graph before writing, and
deferred violations fail the outer commit while leaving it repairable. Catalog
version 5 persists key order, composite descriptors, actions, and deferral while
preserving prior row encodings and schema versions.

SAVEPOINT, RELEASE, and ROLLBACK TO support nesting, repeated names, outer marks
that own transactions, and schema/data/index restoration. Rewind restores raw
snapshots and rebuilds secondary indexes. It never replays SQL. Full rollback or
process termination also undoes work performed after savepoint restoration.

Validation: 77 integrity and 72 savepoint SQLite comparisons; IntegrityTests for
persisted metadata, reusable snapshots, and a one-row index budget after restore;
API regression for reads avoiding unrelated deferred tables; and forced crash
recovery after catalog/index restoration. Savepoint memory and rewind cost scale
with engine-owned database size. Recursion is bounded at depth 1000. Exact SQLite
multi-row/self-referential action ordering and scalable undo-based savepoints
remain future work.

## Reproducing the gates

```text
python scripts/build.py --regenerate
python tests/stage_checks.py --through schema
python tests/stage_checks.py --through indexes
python tests/stage_checks.py --through queries
python tests/stage_checks.py --through expressions
python tests/stage_checks.py --through writes
python tests/stage_checks.py --through integrity
python tests/stage_checks.py --through savepoints
python tests/run_tests.py
```

The final suite includes all gates, the older engine/adapter/client tests, 873
SQLite statement comparisons, restart/rollback tests, and forced recovery. Tests
hash existing `dbms/db` files to verify they are not changed.

The later reliability step adds 1,248 generated SQLite comparisons, six recovery
scenarios with 151 reference statements, and a 4,096-row fixture with 31 assertions.
These run in the default suite alongside the original 873 comparisons. See
[VALIDATION.md](VALIDATION.md) for coverage and reproduction instructions.

## Follow-up schema improvements

Implemented expression/UTC time defaults, named declarations, and dependency-checked
DROP COLUMN. Each feature passed its compilation and cumulative schema gate before
the next was implemented. Current gates compare 60 default, 41 named-constraint,
and 89 column-removal statements with SQLite across JVM restarts. Additional API
checks cover metadata and golden version 0-5 keys/encodings. Two forced-termination
scenarios compare 33 reference statements around committed/pending schema rewrites
and inspect restored index plans. See [SCHEMA_CHANGES.md](SCHEMA_CHANGES.md).
