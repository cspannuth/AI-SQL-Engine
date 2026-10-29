# Indexes and query planning

The engine uses Berkeley DB primary and secondary databases for equality lookups.
It chooses access paths deterministically; no AI model participates in planning.

## Automatic constraint indexes

New tables receive internal indexes for PRIMARY KEY and UNIQUE constraints,
including composite keys. A key already stored as the physical row key does not
need a duplicate secondary index. INTEGER PRIMARY KEY and hidden row ID equality
lookups use the primary database directly. Legacy integer/text primary keys also
support direct lookups. Legacy REAL primary keys use a scan or secondary index:
their raw double encoding can distinguish signed zero even though SQL equality
does not.

Internal indexes have generated `__dbms_auto_` names and expose an `origin` of
`PRIMARY KEY` or `UNIQUE`. They follow inserts, updates, deletes, renames, schema
rewrites, transactions, savepoints, and restarts. DROP INDEX rejects internal
constraint indexes. Equivalent constraint declarations share an internal index.
Composite indexes omit a row when any indexed component is NULL, preserving the
engine's nullable unique-key rules. Proposed mutations are validated before writes;
batch key swaps and recursive action semantics retain their existing behavior.

User-created indexes retain `origin: "CREATE INDEX"`. CREATE INDEX still accepts
one declared column; composite SQL index declarations remain future work.
The metadata's `columns` array describes every index. The original `column` field
remains present for single-column indexes and is NULL for composite indexes.

## Existing databases

Build and open the existing directory through the writable engine:

```powershell
python scripts/build.py
java -cp ".build/classes;dbms/lib/*" DBMS ".local-db"
```

Writable startup transactionally backfills missing constraint indexes. The first
open can take time while existing rows are indexed. Later opens reuse those
indexes. Primary row keys and payloads, catalog version 6, and earlier row formats
are preserved. The original four-field user index metadata and scalar index-key
encoding remain readable.

DatabaseClient, the Python SQL agent, and DatabaseAdapter do not backfill indexes.
Opening one of those readers, describing schema, or running SELECT does not
create an index or scan unrelated tables for an upgrade. Open the database once
with DBMS to upgrade its index coverage, then close it before starting a reader.

## Planning and execution

EXPLAIN QUERY PLAN reports the actual loop order and access paths without scanning
table rows:

```sql
EXPLAIN QUERY PLAN
SELECT Movie.name, Person.name
FROM Movie, Director, Person
WHERE Director.director_id = Person.id
  AND Director.movie_id = Movie.id
  AND Person.name IN ('Joel Coen', 'Ethan Coen');
```

Inner and comma joins can be reordered using a small heuristic: favor available
unique/physical key probes, then indexed equality lookups, local filters, and
connected table scans. AND predicates run when their source rows become available;
OR predicates stay intact. Wildcard output columns retain their original SQL
source order. Use ORDER BY when row order matters.

Explicit CROSS JOIN preserves the declared loop order. Queries containing LEFT
JOIN or predicates with nested queries retain their original join scopes and
final WHERE evaluation. Those conservative paths can still use equality lookups.
The planner avoids text index probes when numeric comparison affinity could make
different text spellings equal.

Ordinary INSERT/UPDATE uniqueness checks probe the relevant keys and account for
all prepared additions and removals. Foreign keys probe indexed, unaffected
parent keys and reuse successful probes within a batch. Schema rewrites, cascade
overlays, and self-references retain full proposed-state validation where needed.
UPDATE and DELETE can also select an equality access path.

Child foreign key columns are not automatically indexed. Create explicit indexes
for frequently traversed relationships, for example:

```sql
CREATE INDEX IF NOT EXISTS director_person_idx ON Director(director_id);
```

That index lets the planner find a person's Director rows directly. Without it,
the person filter still runs before each Director scan.

## Reproduction and measured improvement

```powershell
python tests/performance_checks.py
python tests/run_tests.py
```

The focused fixture has 80 Movie rows, 80 Person rows, and 80 Director rows. Only
two people match the name filter, and the query returns two rows. The original
engine scanned 518,480 rows. Automatic parent indexes alone reduced that to
12,880; filtering and join ordering reduce it to 242, including after reopening
the database in another JVM. No explicit Director index is required for this
measurement.

Tests assert result values and scan counts, not machine-dependent elapsed times.
They also verify physical/composite lookups, mixed numeric/text affinity, LEFT
JOIN nulls, wildcard order, unique batch swaps, index protection, savepoint
restoration, legacy keys, old index metadata, and byte-preserving backfills.
The full suite compares supported SQL with SQLite and checks forced termination
and recovery. Existing `dbms/db` files are hashed before and after verification.

## Remaining limits

The planner uses heuristics rather than table statistics or a full cost model.
Range/order/prefix access, partial/expression indexes, user-declared composite
indexes, and temporary automatic query indexes remain work. IN/OR filters can
run early but do not yet perform multiple index seeks. Incoming reference checks,
cascade expansion, UPSERT, sorting/grouping, and savepoints can still scan or
materialize substantial data. These changes improve the tested workloads without
establishing arbitrary-scale performance or concurrent SQL sessions.
