# Defaults, named constraints, and column removal

These improvements extend the existing Java engine and Berkeley DB storage.
They do not introduce another SQL backend or change physical row identities.

## Expression and time defaults

```sql
CREATE TABLE events (
    id INTEGER PRIMARY KEY,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    category TEXT DEFAULT (LOWER('GENERAL')),
    quantity INTEGER DEFAULT (2 + 3)
);
INSERT INTO events(id) VALUES(1),(2);
SELECT * FROM events ORDER BY id;
```

Defaults fill omitted INSERT columns, including INSERT SELECT. Explicit values,
including NULL, bypass their defaults. Expressions are evaluated for each inserted
row; CURRENT_DATE, CURRENT_TIME, CURRENT_TIMESTAMP, and supported date functions
share a UTC clock within the statement. INTEGER PRIMARY KEY allocation takes
precedence over its default. UPDATE does not apply defaults automatically.
Foreign-key SET DEFAULT actions evaluate the child's default before writing.

Use parentheses around calculations and function calls. The supported scalar
functions and stored-column type/size limits still apply. Column references,
parameters, aggregates, and subqueries are rejected in defaults. Aggregate and
unknown-function defaults fail during definition parsing, earlier than SQLite
may report the error. A valid expression that fails during evaluation, such as
ABS of the minimum signed integer, fails INSERT only when that default is needed.

On a populated table, ADD COLUMN accepts literal defaults, parenthesized literals,
and literal signs/casts. General calculations and clock/function defaults are
rejected. Empty tables can acquire expression/time defaults without backfilling
rows. Existing ADD restrictions on PRIMARY KEY, UNIQUE, NOT NULL, and REFERENCES
continue to apply.

Schema discovery exposes `hasDefault`, `defaultValue`, and `defaultSql`:

- Literal defaults remain in `defaultValue`; `defaultSql` is null.
- Expression/time defaults have `defaultValue: null` and their SQL in `defaultSql`.
- `hasDefault` distinguishes an explicit NULL default from an absent default.

Reading metadata compiles stored expressions for validation but never evaluates
them, reads table rows, or freezes a clock default into its discovery time.

## Named declarations

```sql
CREATE TABLE stock (
    id INTEGER CONSTRAINT stock_pk PRIMARY KEY,
    code TEXT CONSTRAINT code_required NOT NULL CONSTRAINT code_unique UNIQUE,
    quantity INTEGER CONSTRAINT initial_quantity DEFAULT (1 + 2),
    CONSTRAINT quantity_positive CHECK(quantity > 0)
);
```

CONSTRAINT names are supported for column PRIMARY KEY, NOT NULL, UNIQUE, CHECK,
REFERENCES, and DEFAULT declarations, and table PRIMARY KEY, UNIQUE, CHECK, and
FOREIGN KEY declarations. Composite order, referential actions, and deferral keep
their existing semantics. Names may be quoted and need not be unique, matching
SQLite. Naming a declaration does not change its enforcement.

The table's `constraints` metadata contains declaration records:

| Field | Meaning |
| --- | --- |
| `name` | Declared label, or null for an unnamed declaration |
| `kind` | PRIMARY KEY, NOT NULL, UNIQUE, CHECK, FOREIGN KEY, or DEFAULT |
| `columns` | Declared key/child columns, or the owning column for a column declaration |
| `expression` | CHECK/default SQL, otherwise null |
| `ownerColumn` | Column that owns an inline declaration; null for table declarations |

Foreign-key targets/actions remain in `foreignKeys`. Existing flags and lists
remain available; declarations supplement those fields rather than replace them.
CHECK errors report a declared name when available. Other constraint errors keep
their existing column/reference details. Renames update column ownership and
CHECK identifiers while preserving labels and quoted string values. Constraints
added through ADD COLUMN also retain their names.

Names do not introduce ALTER ADD/DROP CONSTRAINT or named conflict targets.
Catalogs older than version 6 have no recorded declaration names or ownership.

## DROP COLUMN

```sql
ALTER TABLE events ADD temporary_note TEXT;
ALTER TABLE events DROP COLUMN temporary_note;
```

The COLUMN keyword is optional. DROP rejects the final column, primary/unique
key members, indexed columns, and columns used by retained CHECK or foreign-key
definitions. Dependency checks use parsed CHECK references, so a matching word
inside a string literal or function name does not block removal.

Inline CHECK, REFERENCES, DEFAULT, and NOT NULL declarations belonging to the
removed column disappear with it. A table constraint or another column's CHECK
that depends on the removed column blocks the operation. All remaining columns,
defaults, names, and data remain intact. Dropping a declared `rowid` shadow makes
the hidden row ID visible again; hidden aliases themselves cannot be dropped.

All replacement rows and metadata are prepared and validated before writing.
Physical keys, integer widths, character encodings, and the row-format marker are
preserved. Surviving secondary indexes are detached and rebuilt transactionally
because column positions can change. Logical rejection preserves earlier work
in an explicit transaction. A failure during storage rewriting aborts the whole
transaction under the existing storage-error policy. ROLLBACK, savepoints, and
restart recovery cover both the schema and the rebuilt indexes.

Older catalogs cannot distinguish inline from table CHECK/REFERENCES declarations.
For those ambiguous legacy dependencies, DROP conservatively rejects removal.
Unrelated columns can still be removed. No declaration ownership is invented
during an upgrade. Rewriting rows and rebuilding indexes materializes working
data; this is not an online or streaming schema migration.

## Persistence and verification

CREATE and ALTER now write catalog version 6. Its column descriptors add optional
default SQL, and its table metadata adds declaration records. Version 5's key and
reference metadata remains intact. Versions 0-5 remain readable and writable;
opening a database does not migrate it. ALTER explicitly upgrades metadata while
retaining the original row encoding. Older engine binaries cannot read version 6.

Run the cumulative schema gates from the repository root:

```text
python scripts/build.py --regenerate
python tests/schema_checks.py --through defaults
python tests/schema_checks.py --through names
python tests/schema_checks.py --through drop
python tests/run_tests.py
```

The gates include 60 default, 41 named-constraint, and 89 DROP SQLite statement
comparisons, with separate JVMs for persistence. API tests inspect stored SQL and
names, preserve golden version 0-5 keys/encodings, and require an actual one-row
index lookup after removal and restart. Two further forced-termination scenarios
check committed and pending column rewrites after savepoint restoration. They
compare 33 SQL statements against SQLite and check the surviving index plan.
These remain acknowledged-boundary process kills, not arbitrary I/O fault tests.
All checks use temporary databases and verify existing `dbms/db` files are unchanged.

Behavioral references: [SQLite defaults and constraints](https://www.sqlite.org/lang_createtable.html)
and [ALTER TABLE](https://www.sqlite.org/lang_altertable.html).
