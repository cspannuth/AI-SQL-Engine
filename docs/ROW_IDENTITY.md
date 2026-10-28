# Row IDs and primary keys

New tables use SQLite-style row identity. Each row has a unique, non-null,
signed 64-bit integer ID, whether or not the schema declares a primary key.
Existing tables retain their original format and key rules.

## Tables without a primary key

The first column is an ordinary column: duplicates and NULL are allowed unless
an explicit constraint forbids them. The engine supplies an internal row ID.

```sql
CREATE TABLE events(label TEXT, amount REAL);
INSERT INTO events VALUES('opened',1),('opened',1),(NULL,NULL);
SELECT rowid,label,amount FROM events ORDER BY rowid;
-- IDs: 1, 2, 3. SELECT * returns only label and amount.

UPDATE events SET amount=2 WHERE rowid=1;
DELETE FROM events WHERE rowid=2;
```

`rowid`, `oid`, and `_rowid_` name the same internal ID. They work in projections,
expressions, predicates, joins, grouping, INSERT column lists, and UPDATE targets.
Names and qualifiers follow ordinary column resolution. A declared column with
one of these names shadows that particular alias; the other aliases remain usable.
An unqualified row ID reference across multiple rowid tables is ambiguous.

## INTEGER PRIMARY KEY

A single primary key declared with the exact type name `INTEGER`, ignoring case,
aliases the internal row ID. This also works with `PRIMARY KEY(column)` at the
end of the definition. The type name `INT` does not create this alias.

```sql
CREATE TABLE tasks(id INTEGER PRIMARY KEY, title TEXT NOT NULL);
INSERT INTO tasks(title) VALUES('first'),('second');
INSERT INTO tasks VALUES(NULL,'third');
SELECT id,rowid,title FROM tasks ORDER BY id;
-- Both id and rowid contain 1, 2, 3.

UPDATE tasks SET rowid=10 WHERE id=1;
-- The same row now has id=10 and rowid=10.
```

Omitting the ID or supplying NULL in INSERT generates an ID, including when the
aliased key explicitly says NOT NULL. An empty table starts at 1. Otherwise the
engine uses one more than the largest existing ID, including IDs already prepared
by the same multi-row INSERT. Negative IDs and zero are valid explicit IDs.
If the largest ID is the maximum signed integer, allocation tries up to 100
random unused positive IDs before failing. Explicit IDs must be unique and convert
to signed 64-bit integers; integral REAL values and numeric text are accepted.

UPDATE preserves the existing ID unless an assignment explicitly changes it.
Assigning NULL or a non-integral value to an ID in UPDATE fails. Duplicate IDs,
including duplicates within a single INSERT or UPDATE, fail before any rows are
written. Names that refer to the same ID count as duplicate assignment targets.

There is no separate persisted allocation counter. Deleting the greatest ID can
allow reuse, and rolling back an insertion does not consume its generated ID.
Committed IDs survive restarts; unfinished changes roll back during recovery.
`AUTOINCREMENT` is a different SQLite feature and remains unsupported.

## Other primary keys

`INT`, TEXT, REAL, CHAR, and VARCHAR primary keys are stored independently of the
internal row ID. Their non-null values must be unique. Following SQLite's ordinary
rowid-table behavior, these keys permit multiple NULL values unless NOT NULL is
explicitly declared. Changing such a key preserves the internal row ID.

Uniqueness currently uses a table scan rather than an index. Multi-row UPDATE
checks final uniqueness; SQLite can reject some key swaps because it checks
conflicts as individual rows change. Composite primary keys are also independent
of the internal row ID. Single-clause ON CONFLICT is supported. WITHOUT ROWID
and AUTOINCREMENT remain unsupported.
Ordinary UNIQUE, CHECK, default, and foreign-key support is described in
[CONSTRAINTS.md](CONSTRAINTS.md).

## Schema discovery

The adapter and client's `describe_table` response includes:

- `columns`: declared columns only, with actual nullability and primary-key flags.
- `rowIdTable`: whether this table uses the new row ID format.
- `rowIdColumn`: the declared INTEGER PRIMARY KEY name, or null when none aliases
  the row ID.

Check `rowIdTable` before generating queries that reference hidden IDs. A nullable
ordinary primary key is reported as nullable; an INTEGER PRIMARY KEY is not.
Schema discovery does not read table contents. Query results retain integer types
for row ID projections even when no rows match.

## Storage compatibility

| Catalog format | Text | Integer width | Physical row key |
| --- | --- | --- | --- |
| Original, unversioned | Single-byte | 32 bits | Declared primary-key bytes |
| Version 1 | UTF-8 | 64 bits | Declared primary-key bytes |
| Version 2 | UTF-8 | 64 bits | Internal row ID |
| Version 3 | UTF-8 | 64 bits | Internal row ID; persisted defaults and constraints |
| Versions 4/5 | Preserved row encoding | Preserved widths | Explicit row-format marker; new tables use internal row IDs |

Version 2 catalog records start with a zero marker, version byte, column-count
byte, and signed-short row ID column index (`-1` if there is no alias). The
existing column descriptors follow. Row keys contain eight big-endian bytes with
the ID's sign bit flipped so Berkeley DB's byte order matches signed integer
order. The row payload keeps the existing signed-short offset header and
32767-byte limit. Only an INTEGER PRIMARY KEY alias uses the key-value sentinel;
other primary keys are stored in the payload like ordinary columns.
Version 3 extends the catalog descriptors with defaults and constraints while
keeping the same physical row representation.
Version 4 adds a row-format byte independently of the schema version. Version 5
adds ordered/composite keys and foreign-key actions/deferral. ALTER upgrades the
schema descriptor while retaining the table's row-format byte and stored widths.

Opening an old database never migrates or rewrites its schemas. Original and
version 1 tables keep their declared or implicit first-column primary key, require
explicit non-null keys, and do not expose hidden row IDs. Version 2 tables retain
their row IDs without acquiring new constraints. All formats can coexist in the
same database. ALTER additions/renames are explicit schema changes; automatic
conversion of legacy physical keys to row IDs remains unsupported.

`python tests/run_tests.py` verifies SQLite comparisons, signed key boundaries,
bound values, ID exhaustion fallback, old-format fixtures, statement atomicity,
adapter metadata, JVM restarts, rollback, and forced process recovery. Tests use
disposable databases and leave existing database files unchanged.

Behavioral references: [SQLite rowid tables](https://www.sqlite.org/rowidtable.html),
[row ID allocation](https://www.sqlite.org/autoinc.html), and
[INTEGER PRIMARY KEY rules](https://www.sqlite.org/lang_createtable.html#rowid).
