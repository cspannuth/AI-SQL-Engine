# Defaults and constraints

New tables support literal defaults, single/composite keys, CHECK expressions,
and single/composite foreign keys. The engine enforces these rules
independently of the client or any AI workflow.

```sql
CREATE TABLE customers (
    id INTEGER PRIMARY KEY,
    email TEXT NOT NULL UNIQUE
);

CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    status TEXT DEFAULT 'pending',
    total REAL NOT NULL DEFAULT 0 CHECK(total >= 0)
);

INSERT INTO customers(email) VALUES('person@example.com');
INSERT INTO orders(customer_id) VALUES(1);
SELECT * FROM orders;
-- id=1, customer_id=1, status='pending', total=0.0
```

## DEFAULT

Column defaults can be text, signed numeric literals, or NULL. Options may appear
in different orders: `INTEGER DEFAULT 7 NOT NULL` and `INTEGER NOT NULL DEFAULT 7`
are equivalent.

Defaults fill omitted columns. An explicitly supplied NULL stays NULL and must
satisfy NOT NULL and other constraints. Without a declared default, an omitted
column receives NULL. `INSERT INTO table_name DEFAULT VALUES` inserts one row
using the defaults for every column. Defaults do not apply to UPDATE.

INTEGER PRIMARY KEY retains its allocation behavior: omitting it or supplying
NULL generates a row ID, even if that column declares a literal default. See
[ROW_IDENTITY.md](ROW_IDENTITY.md).

Parenthesized expressions, function/time defaults, and parameters in schema
definitions are unsupported. Defaults use the engine's existing stored types and
length limits when a row is inserted; declaring a default does not disable type
or constraint validation.

## UNIQUE

Declare a unique column inline or after the column definitions:

```sql
CREATE TABLE accounts (
    id INTEGER PRIMARY KEY,
    email TEXT UNIQUE,
    handle TEXT,
    UNIQUE(handle)
);
```

Non-null values must be unique on INSERT and UPDATE, including within the same
multi-row statement. Multiple NULL values are allowed. Add NOT NULL when a value
is required. Composite UNIQUE and PRIMARY KEY declarations are supported as
`UNIQUE(a,b)` and `PRIMARY KEY(a,b)`. Composite keys preserve their declared order.
A key containing any NULL component does not conflict; use explicit NOT NULL
where required. Composite primary keys keep a separate internal row ID.

Constraint validation currently uses scans; explicit UNIQUE indexes are also
supported and maintained transactionally. UPDATE checks the proposed
final state, so some key swaps can succeed where SQLite's checks on individual
rows would reject them. This also applies to primary-key updates.

## CHECK

Both column and table checks use the existing scalar expressions and predicates.
Table checks can compare multiple columns:

```sql
CREATE TABLE ranges (
    start_value INTEGER,
    end_value INTEGER,
    CHECK(end_value >= start_value)
);
```

Every inserted or updated row must pass every check. A zero/false result fails;
a nonzero or NULL result passes. Combine CHECK with NOT NULL when missing values
must fail. Checks use adjusted stored values and the proposed row ID, rather than
the original values of an UPDATE. Existing expression/type semantics still apply.

Unknown columns, aggregates, parameters, and unsupported expressions are rejected
when the table is created. Subqueries are not supported. CHECK SQL is persisted
as UTF-8 and compiled by the normal parser when validating mutations; it is never
executed as a separate query.
Supported scalar functions can appear in CHECK. Clock-dependent date calls fail
during mutation validation, matching the requirement for deterministic checks.

## Foreign keys

Both forms can reference a single parent column:

```sql
CREATE TABLE children (
    id INTEGER PRIMARY KEY,
    parent_id INTEGER REFERENCES customers(id)
);

CREATE TABLE other_children (
    id INTEGER PRIMARY KEY,
    parent_id INTEGER,
    FOREIGN KEY(parent_id) REFERENCES customers(id)
);
```

`REFERENCES customers` without a column list selects its primary key, in declared
order. Composite syntax is `FOREIGN KEY(a,b) REFERENCES parent(x,y)`. The target
must match a PRIMARY KEY or UNIQUE key (or a supported explicit UNIQUE index).
Hidden row IDs are not valid foreign-key targets. Any NULL child component exempts
that row from matching a parent. Comparisons apply the parent columns' affinities.

Parent tables may be created after the child schema, including self-references.
The parent and its key are resolved when validating mutations. Missing or invalid
parent definitions cause SQL errors; the engine currently checks these definitions
even on mutations that leave the foreign-key column unchanged.

The complete proposed statement is validated before writing. A self-reference can
point to another row inserted in the same batch. Deleting or changing parent keys
fails if surviving children would be orphaned under NO ACTION or RESTRICT.
Supported actions also apply to DROP TABLE's implicit deletion. Dropping a self-referencing table is
allowed because its own children are dropped with it. As in SQLite, DROP ignores
malformed parent-key definitions when checking its implicit deletion.

Declared foreign keys are always enforced. There is no PRAGMA toggle. ON DELETE
and ON UPDATE support NO ACTION (the default), RESTRICT, CASCADE, SET NULL, and
SET DEFAULT. Cascades are planned and checked as a complete change set before
writing. Invalid child defaults, NOT NULL/CHECK failures, or uniqueness conflicts
abort the entire statement. Referential action recursion is limited to depth 1000.

Checks are immediate by default. `DEFERRABLE INITIALLY DEFERRED` checks at outer
commit; a failed COMMIT leaves the transaction active so the reference can be
repaired or rolled back. RESTRICT remains immediate. INITIALLY IMMEDIATE and
NOT DEFERRABLE are accepted; MATCH is unsupported. Some multi-row/self-referential
action ordering can differ from SQLite because validation uses proposed statement
states. Comparisons with SQLite enable
`PRAGMA foreign_keys=ON` in the reference database.

## Transactions, persistence, and metadata

All logical validation precedes the first data or catalog write. A constraint
failure leaves that statement's data unchanged and preserves earlier successful
statements in an explicit transaction. Storage failures retain the existing policy
of rolling back the active transaction. BEGIN/COMMIT/ROLLBACK cover constrained
rows and schemas; constraints survive restarts and process termination/recovery.

New CREATE TABLE statements write catalog version 5, adding ordered/composite keys,
foreign-key actions, and deferral. Version 3 introduced defaults/CHECK/references;
version 4 separated schema version from row encoding for safe ALTER changes.
Earlier versions remain readable and writable. ALTER ADD/RENAME is implemented;
old tables acquire no constraints unless explicitly altered. Row keys and payloads
retain their original encoding. See [SQL_FEATURES.md](SQL_FEATURES.md).

Schema discovery returns declared metadata without reading table contents:

| Field | Meaning |
| --- | --- |
| Column `unique` | Explicit single-column UNIQUE declaration; primary keys are identified separately |
| Column `hasDefault` | Distinguishes DEFAULT NULL from no declared default |
| Column `defaultValue` | Declared literal, including null; INTEGER PRIMARY KEY allocation still takes precedence |
| Table `checks` | Column and table CHECK expressions as SQL text |
| Table `primaryKeyColumns` | Primary-key names in declared order |
| Table `uniqueKeys` | Composite UNIQUE column lists; single-column flags remain on columns |
| Table `foreignKeys` | `columns`, `parentTable`, `parentColumns`, `onDelete`, `onUpdate`, `deferred`; empty parent list means the primary key; singular fields remain for compatibility |
| Table `indexes` | Explicit index name, table, column, and uniqueness |

These fields are available through `DatabaseAdapter.describeTable`, terminal
`.schema`, and JSON Lines `describe_table`. Parent metadata and rows are not
fetched by schema discovery. Read-only policies still prevent mutation requests.

Run `python tests/run_tests.py` for isolated API, SQLite comparison, default/key
boundary, schema round-trip, rollback, old-format, JSON metadata, and crash-recovery
checks. Existing database directories are not used as fixtures.

References: [SQLite CREATE TABLE](https://www.sqlite.org/lang_createtable.html)
and [foreign keys](https://www.sqlite.org/foreignkeys.html).
