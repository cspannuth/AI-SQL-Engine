# SQL engine extensions

The six implementation stages are recorded in [IMPLEMENTATION_STAGES.md](IMPLEMENTATION_STAGES.md).
The following forms are implemented and tested. The engine remains a custom
SQLite-oriented subset; [SQL_COMPATIBILITY.md](SQL_COMPATIBILITY.md) tracks gaps.

## Schema changes and indexes

```sql
CREATE TABLE accounts (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    visits INTEGER NOT NULL DEFAULT 0
);
ALTER TABLE accounts ADD COLUMN label TEXT DEFAULT 'new';
ALTER TABLE accounts RENAME COLUMN label TO display_name;

CREATE INDEX email_lookup ON accounts(email);
EXPLAIN QUERY PLAN SELECT id FROM accounts WHERE email='person@example.com';
DROP INDEX email_lookup;
```

ALTER also supports `RENAME TO new_table`. Renames update CHECK identifiers,
foreign-key descriptors, and explicit index definitions without replacing string
literals. ADD validates existing rows and retains physical keys and encodings.
ADD PRIMARY KEY/UNIQUE is prohibited; an added NOT NULL column needs a non-null
literal default, and an added REFERENCES column must default to NULL.

CREATE [UNIQUE] INDEX accepts one declared column and optional IF NOT EXISTS.
DROP INDEX accepts IF EXISTS. Secondary indexes populate existing data and follow
inserts, updates, deletes, schema renames, rollback, and restart transactionally.
The planner selects equality lookups for constants, positional parameters, and
earlier join inputs. Other access paths remain scans. EXPLAIN resolves plans
without scanning table rows, including derived/CTE metadata.

DROP COLUMN, expression/partial/composite indexes, index order/range optimization,
automatic constraint indexes, and cost-based planning remain unsupported.

## Query composition

```sql
WITH frequent AS (
    SELECT id,email FROM accounts WHERE visits>5
)
SELECT email FROM frequent
UNION
SELECT email FROM accounts WHERE visits=0
ORDER BY email;

SELECT a.id,
       (SELECT COUNT(*) FROM accounts b WHERE b.visits>a.visits) AS more_active
FROM accounts a
WHERE EXISTS(SELECT 1 FROM accounts b WHERE b.id=a.id)
ORDER BY a.id;
```

Scalar queries return their first row/value, or NULL for an empty result, and
must project one column. IN/NOT IN queries also require one column and retain
SQL null semantics. FROM supports `(SELECT ...) alias`. Ordinary WITH scopes may
have explicit column lists and refer to other CTEs; cycles/recursive CTEs fail.

UNION, UNION ALL, INTERSECT, and EXCEPT associate left-to-right. Their final
ORDER BY uses output names or positions, followed by an optional LIMIT/OFFSET.
Queries must have equal column counts. Use ORDER BY for deterministic ordering.

Intermediate queries retain full results under execution budgets. A final adapter
preview must not change an inner COUNT, membership test, or aggregate. Nested
physical tables are authorized before execution, including inside functions,
derived tables, CTEs, and compound branches. No model is involved in execution.

## Expressions and functions

```sql
SELECT email,
       CASE WHEN visits=0 THEN 'inactive' ELSE 'active' END AS status,
       COALESCE(display_name,email) AS name,
       CAST(visits AS TEXT) AS visits_text
FROM accounts;

SELECT DATE('2024-02-28','+1 day'),
       STRFTIME('%Y-%m','2024-02-29 12:34:56');
```

Supported scalar functions:

- Null/conditional: COALESCE, IFNULL, NULLIF, IIF; searched and simple CASE.
- Numeric: ABS, ROUND, multi-argument MIN/MAX. One-argument MIN/MAX remain aggregates.
- Text: LENGTH, LOWER, UPPER, TRIM, LTRIM, RTRIM, SUBSTR/SUBSTRING, REPLACE, INSTR,
  TYPEOF, QUOTE. Case folding is ASCII; substring positions count Unicode code points.
- Conversion: CAST to INTEGER/INT, REAL, TEXT/CHAR/VARCHAR, NUMERIC. Explicit casts
  are separate from the engine's strong stored-column type rules.
- UTC dates: DATE, TIME, DATETIME, STRFTIME, JULIANDAY, UNIXEPOCH, and
  CURRENT_DATE/CURRENT_TIME/CURRENT_TIMESTAMP. Clock calls share a statement time.

Date inputs support valid ISO dates/times/offsets, Julian values, and numeric Unix
timestamps with `unixepoch`. Modifiers support start of day/month/year, numeric
day/hour/minute/second shifts, and integral month/year shifts with default ceiling
behavior. STRFTIME supports `%Y %m %d %H %M %S %f %j %w %W %s %J %%`.
Malformed inputs/unknown directives yield NULL. Localtime/UTC conversion modifiers,
auto/subsecond/floor/ceiling modifiers, fractional month/year shifts, BLOB casts,
and additional formatting/collation behavior are outside this subset.

CASE, COALESCE, IFNULL, and IIF evaluate only the needed branch. Arity and unknown
function errors are checked even for empty queries. CHECK permits deterministic
scalar functions but rejects clock-dependent date calls during row validation.

## Broader writes

```sql
INSERT INTO accounts(email) VALUES('person@example.com')
ON CONFLICT(email) DO UPDATE SET visits=accounts.visits+1
RETURNING id,email,visits;

INSERT INTO accounts(email,visits)
SELECT 'second@example.com',2
RETURNING *;

UPDATE accounts SET visits=visits+1 WHERE id=1 RETURNING id,visits;
DELETE FROM accounts WHERE id=2 RETURNING id,email;
```

One ON CONFLICT clause can name a single/composite unique target or omit it.
DO NOTHING ignores uniqueness conflicts; DO UPDATE supports assignments and WHERE.
Unqualified columns refer to the original row; `excluded.column` refers to the
attempted insert. Decisions are made per input row, including earlier batch rows.
NOT NULL/CHECK failures are not ignored. UNIQUE indexes can be conflict targets.

INSERT SELECT materializes its source before modifying the target. WITH can prefix
INSERT, UPDATE, and DELETE. Correlated read expressions work in UPDATE/DELETE.
RETURNING supports columns, scalar expressions, aliases, and `*`; hidden row IDs
must be explicit. Inserts/updates return new row images and deletes return old
images. Only direct changes contribute to returned rows and affected counts.
All projections finish before the first write, so projection errors remain atomic.

Multiple conflict clauses, OR REPLACE/IGNORE, RETURNING aggregates/subqueries and
qualified wildcards, and full SQLite self-referential mutation ordering are
unsupported. The read-only adapter rejects all writes, regardless of prefix or
RETURNING. Use the engine CLI or trusted DBMS.execute calls for mutations.

## Composite integrity and savepoints

```sql
CREATE TABLE membership (
    account_id INTEGER NOT NULL,
    team TEXT NOT NULL,
    PRIMARY KEY(account_id,team),
    FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

BEGIN;
SAVEPOINT before_edit;
UPDATE accounts SET visits=visits+1;
ROLLBACK TO before_edit;
RELEASE before_edit;
COMMIT;
```

Primary, UNIQUE, and foreign keys support composite column lists. Reference
actions support NO ACTION, RESTRICT, CASCADE, SET NULL, SET DEFAULT, and deferral.
A failed deferred COMMIT keeps the transaction open for repair or rollback.
Actions are planned together and validated before writing. See [CONSTRAINTS.md](CONSTRAINTS.md).

SAVEPOINT can start a transaction. Marks may nest and reuse names; commands select
the most recent matching name. ROLLBACK TO preserves that mark and removes newer
ones. RELEASE removes the named mark and newer marks; releasing the outer mark
commits only when SAVEPOINT started the transaction. Schema, data, and index changes
are restored together, and an outer ROLLBACK still discards released inner work.

Savepoints currently copy engine-owned schemas and raw primary rows into memory.
Rewind restores those bytes and rebuilds secondary indexes; it does not replay
SQL or reevaluate defaults. This simple implementation costs space proportional
to database size per mark and is intended for the current single serialized
session, not large concurrent workloads.
