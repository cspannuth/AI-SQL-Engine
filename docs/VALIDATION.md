# Engine reliability checks

Run the complete suite from the repository root:

```text
python tests/run_tests.py
```

The runner regenerates the parser, compiles fresh sources, and uses disposable
database directories. It checks that existing `dbms/db` files are unchanged.
No external Python packages or model connections are needed.

To run just the new reliability checks:

```text
python tests/reliability_checks.py
python tests/reliability_checks.py --seed 42
python tests/reliability_checks.py --seed 11 --seed 23
```

The focused command also compiles fresh sources and checks existing database
hashes. It uses the checked-in generated parser; the complete suite additionally
verifies regeneration. Both commands include the recovery matrix and larger-data
checks. Allow several minutes for the separate JVMs and database recovery.

## Generated SQLite comparisons

`tests/reliability_checks.py` uses Python's seeded random generator and SQLite
reference engine. The default seeds are 7, 42, and 2026. Each seed generates a
32-row fixture and executes three successive JVM sessions against the same
temporary database, checking persistence as well as execution.

The default run compares 1,248 statements covering:

- NULLs, escaped/Unicode text, bounded arithmetic, CASE, COALESCE, predicates,
  joins, grouping, aggregates, correlated subqueries, and ordinary CTEs.
- Equality results before and after index creation, indexed updates/deletes,
  index recreation, and queries after reopening the database.
- Failed multi-row inserts and CHECK violations inside explicit transactions,
  preservation of earlier statements, savepoint rewind, commit, and rollback.
- UNIQUE, literal defaults, foreign-key violations, CASCADE updates, and SET NULL
  deletes, followed by complete table-state comparisons.

Comparisons check ordered result rows, success versus failure, and direct DML
affected counts. Numeric results compare by value; this does not prove identical
SQLite storage classes or formatting. Generated statements must be valid SQLite
syntax: only intentional integrity errors are accepted in these runs. Error
wording is engine specific. Unordered results and SQLite's unspecified bare
aggregate column choices are excluded from generated cases.

Failures report the seed, SQL, and observed difference. The executed SQL prefix
is saved under `.build/validation-failures/seed-<seed>.sql`, outside publishable
source. A runner failure saves the entire submitted batch. Re-run with the
reported seed, then reduce the failing statement to a small regression before
changing engine logic. Seeds reproduce cases for a given generator version;
save the transcript when changing the generator.

## Forced process termination

The recovery matrix adds six scenarios and 151 SQLite statement comparisons:

| Scenario | What is checked after reopening |
| --- | --- |
| Committed change followed by pending writes | The commit survives; later update/delete and cascades disappear |
| Failed multi-row batch | No batch rows or other uncommitted rows survive |
| Committed cascades followed by a pending delete | Committed parent/child changes survive together |
| Savepoint restores renamed schema and replaced indexes | Original schema/indexes survive; unfinished DDL disappears |
| Deferred foreign-key commit fails | The still-open transaction is discarded |
| Outer savepoint release followed by another transaction | The released transaction persists; later changes disappear |

The test waits for each SQL response before proceeding and kills the Java process
after the last acknowledged statement. SQLite's corresponding connection is
closed to discard its open transaction, then reopened for comparison. Recovery
probes check rows, uniqueness, references, missing uncommitted objects, and the
actual equality-index plan. Response waits have a timeout so runner failures do
not hang indefinitely. Failure transcripts record the termination boundary;
re-running the reliability command reproduces that boundary, while replaying SQL
alone does not perform the process kill.

These tests cover process termination at acknowledged statement boundaries.
Mid-write I/O fault injection, disk exhaustion, power loss, and damaged log files
remain unverified. The SQLite reference is closed normally; it is not itself a
crash-test subject.

## Larger-data and resource checks

`tests/ScaleTests.java` creates 4,096 rows using normal SQL batches, creates two
indexes, updates/deletes indexed rows, rewinds a savepoint, and closes the engine.
A separate JVM then checks the remaining 4,095 rows through the adapter.

Its 31 assertions cover exact aggregates and eight independently calculated group
totals, a lookup requiring only one scanned row, removed index entries, persisted
index metadata, row/byte truncation, complete SQL LIMIT results, result-artifact
metadata, complete CTE input, and scan/working-row/working-byte limits. A synthetic
clock drives an actual scan past its deadline without relying on machine speed.
Subsequent queries verify that limit failures leave no active transaction or
leaked execution budget.

This is a moderate correctness fixture, not a throughput benchmark or evidence
that arbitrarily large queries fit in memory. Savepoints still snapshot data in
memory; grouping, sorting, CTEs, and other operations still materialize working
data. Deadlines remain cooperative, and independent concurrent sessions remain
unsupported. Further SQLite differences are listed in
[SQL_COMPATIBILITY.md](SQL_COMPATIBILITY.md).

The subsequent schema gates add expression/time defaults, named constraints, and
DROP COLUMN, including two more schema-rewrite crash scenarios. They also run
in the complete suite; see [SCHEMA_CHANGES.md](SCHEMA_CHANGES.md) for commands,
older-format coverage, and remaining restrictions.
