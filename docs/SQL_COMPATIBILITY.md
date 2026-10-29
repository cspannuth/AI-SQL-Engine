# SQL capabilities and SQLite compatibility

SQLite is the compatibility target. This is a custom SQL engine using Berkeley DB
JE 7.5.11; full SQLite language, storage, driver, and concurrency compatibility is
not yet implemented. The original table/column model and statement architecture
remain in place.

## Working syntax

| Area | Implemented |
| --- | --- |
| Schema | CREATE/DROP TABLE; ALTER TABLE ADD [COLUMN], RENAME [COLUMN], RENAME TO; PRIMARY KEY, NOT NULL, UNIQUE, CHECK, REFERENCES/FOREIGN KEY, including composite keys |
| Indexes | CREATE [UNIQUE] INDEX, DROP INDEX, IF [NOT] EXISTS; single declared column; equality lookup planning and EXPLAIN QUERY PLAN |
| Row identity | New tables have hidden rowid/oid/_rowid_ aliases; INTEGER PRIMARY KEY aliases the ID; omitted or NULL INSERT IDs are generated |
| Types | INTEGER/INT, REAL/DOUBLE/FLOAT, CHAR(n), VARCHAR(n), TEXT; optional VARCHAR length and default CHAR length 1 |
| Inserts | VALUES rows, literal defaults, DEFAULT VALUES, INSERT ... SELECT; one ON CONFLICT clause with DO NOTHING or DO UPDATE/WHERE; excluded values |
| Updates | UPDATE ... SET ... WHERE ...; expressions evaluated against the original row; primary-key changes; all-row updates |
| Deletes | DELETE FROM ... WHERE ...; all-row deletion |
| Transactions | BEGIN, COMMIT/END, ROLLBACK, SAVEPOINT, RELEASE, ROLLBACK TO; automatic statement transactions; deferred foreign-key validation at commit |
| Returning | INSERT/UPDATE/DELETE RETURNING columns, scalar expressions, aliases, or *; direct affected counts and row images |
| Query composition | Scalar/correlated subqueries, EXISTS, IN queries, derived FROM tables, ordinary WITH CTEs, UNION [ALL], INTERSECT, EXCEPT |
| Projection | Columns, literals, scalar expressions, aliases, *, table.*, SELECT without FROM |
| Joins | Comma joins, CROSS JOIN, JOIN/INNER JOIN ... ON, LEFT [OUTER] JOIN ... ON; aliases and self joins |
| Filtering | Comparisons, IS/IS NOT, AND/OR/NOT, IN/NOT IN lists or queries, BETWEEN, LIKE, null tests, EXISTS/NOT EXISTS |
| Result shaping | DISTINCT, GROUP BY, HAVING, ORDER BY ASC/DESC; output aliases and ordinals in GROUP BY/ORDER BY; HAVING aliases |
| Aggregates | COUNT(*), COUNT(expr), SUM, AVG, TOTAL, MIN, MAX; DISTINCT arguments; null and empty-group handling |
| Scalar operations | +, -, *, /, %, unary signs, concatenation; predicates return 1/0/NULL; scalar truth conditions |
| Expressions/functions | Searched/simple CASE, CAST, COALESCE/IFNULL/NULLIF/IIF, numeric/text functions, UTC date/time functions; see SQL_FEATURES.md |
| Referential actions | Single/composite references, NO ACTION, RESTRICT, CASCADE, SET NULL, SET DEFAULT, DEFERRABLE INITIALLY DEFERRED/IMMEDIATE |
| Parameters | Positional ? values in scalar expressions and LIMIT/OFFSET; typed binding through the Java API |
| Limits | LIMIT count, LIMIT count OFFSET offset, LIMIT offset,count; negative count is unbounded; negative offset becomes zero |
| Lexing | Case-insensitive unquoted names and keywords, underscore identifiers, double-quoted identifiers, doubled single quotes, line/block comments |
| CLI | Multiple statements per line, multiline statements, q to exit, continued operation after a SQL error |

LIKE follows SQLite's default ASCII case folding and treats regex punctuation as
literal text. The original CLIKE keyword remains as an extension. Null predicates
preserve UNKNOWN through NOT, AND, and OR; WHERE selects only true results.

Rows are not implicitly ordered. Use ORDER BY for a specified result order.
Aggregate bare columns use a representative input row, including an extremal row
for MIN/MAX; ties have no guaranteed choice. Prefer explicit grouped columns.

## Important remaining differences

- **Table identity:** new tables use internal row IDs and do not require a primary
  key. Exact INTEGER PRIMARY KEY declarations alias the ID; INT does not. Other
  primary keys allow NULL unless NOT NULL is declared. Old tables retain their
  original key semantics without automatic row migration. Composite primary keys
  are ordinary nullable unique keys unless NOT NULL is explicit. WITHOUT ROWID
  remains work. See [row identity](ROW_IDENTITY.md).
- **Types and lengths:** schemas retain strong stored types. INTEGER values in
  new tables occupy signed 64 bits; original INTEGER schemas retain 32 bits.
  REAL stores doubles. CHAR pads to its byte width, and strings exceeding their
  declared byte width fail instead of being silently truncated. SQLite's
  dynamic storage classes and VARCHAR length behavior differ. Basic comparison
  affinity and numeric expression conversion are supported, but complete SQLite
  type/formatting behavior remains work.
- **Storage:** new version 5 catalogs persist ordered/composite keys, reference
  actions and deferral. Version 4 introduced a separate row-format marker so ALTER
  can retain legacy physical keys and character encodings. Versions 0–4 remain
  readable and writable. New tables retain version 2's row key and payload format.
  Version 1 tables retain UTF-8 and their primary-key byte representation.
  Unversioned tables retain their original single-byte encoding and integer
  widths; unsupported characters in those tables are rejected rather than
  corrupted. Existing data is not automatically rewritten. Stored row values
  retain signed-short offsets and a 32767-byte maximum, including the header.
- **Constraints/schema:** foreign keys are always enforced, without a PRAGMA toggle.
  Named constraints, expression/time defaults, MATCH, AUTOINCREMENT, DROP COLUMN,
  and arbitrary column type/constraint alterations remain work. See
  [CONSTRAINTS.md](CONSTRAINTS.md) for supported behavior.
- **Query language:** recursive CTEs, window functions, views, triggers, named
  parameters, NATURAL/USING/RIGHT/FULL joins, and custom collations remain work.
  Compounds order by output names/positions. Scalar functions, casts, and UTC
  date modifiers have explicit supported subsets in [SQL_FEATURES.md](SQL_FEATURES.md).
- **Writes:** multiple ON CONFLICT clauses, INSERT OR REPLACE/IGNORE, and
  RETURNING aggregates/subqueries are unsupported. Duplicate INSERT columns and
  UPDATE assignments, including aliases
  for the same row ID, are rejected. Multi-row primary-key and UNIQUE
  updates validate final uniqueness, which can differ from SQLite's immediate
  conflict checks. DML read expressions and referential actions use proposed
  statement states; self-referential and multi-row ordering can differ from
  SQLite's per-row execution. Logical mutation errors are detected before writing and
  preserve an explicit transaction's earlier statements. Storage failures
  conservatively roll back the entire active transaction.
- **Execution:** one serialized database session owns process-wide state.
  Concurrency across independent SQL sessions is not implemented. Queries use
  equality index lookups or scans with nested-loop joins. Range/order/composite
  index optimization and cost-based planning remain work. Sorting, grouping,
  compounds, CTEs, and mutations materialize working data. Savepoints store full
  raw in-memory snapshots and rebuild secondary indexes on rewind; their cost
  scales with database size and mark count. The local
  adapter applies scan/work budgets, output caps, interruption checks, and
  cooperative deadlines; direct engine calls remain unlimited by default. See
  [ADAPTER.md](ADAPTER.md) for guarantees and limits.

## Verification

Run `python tests/run_tests.py`. It builds fresh classes from regenerated parser
sources, runs engine assertions against disposable data, compares supported
queries against Python's SQLite, and checks clean restarts, unfinished-transaction
shutdown, forced process termination/recovery, and CLI error recovery.
`python tests/stage_checks.py --through <stage>` runs cumulative stage gates;
see [IMPLEMENTATION_STAGES.md](IMPLEMENTATION_STAGES.md) for the sequence.

Golden fixtures check reading and updating unversioned, version 1, and version 2
storage formats. Constraint and row ID checks compare supported mutations against
SQLite across JVM restarts. The runner hashes existing `dbms/db` files before and
after verification.
These checks establish the tested cases, not general proof of SQLite parity,
arbitrary crash safety, or concurrent isolation.

The default suite also includes seeded differential tests, six additional
process-termination scenarios, and a 4,096-row index/aggregate/resource fixture.
See [VALIDATION.md](VALIDATION.md) for standalone commands, failure reproduction,
and the boundaries of these checks. This expands validation without broadening
the advertised SQL dialect.

SQLite references: [SELECT](https://www.sqlite.org/lang_select.html),
[expressions](https://www.sqlite.org/lang_expr.html), and
[transactions](https://www.sqlite.org/lang_transaction.html).
