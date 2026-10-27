# SQL capabilities and SQLite compatibility

SQLite is the compatibility target. This is a custom SQL engine using Berkeley DB
JE 7.5.11; full SQLite language, storage, driver, and concurrency compatibility is
not yet implemented. The original table/column model and statement architecture
remain in place.

## Working syntax

| Area | Implemented |
| --- | --- |
| Schema | CREATE TABLE, DROP TABLE, IF NOT EXISTS / IF EXISTS; inline or table-level single-column PRIMARY KEY; NOT NULL |
| Types | INTEGER/INT, REAL/DOUBLE/FLOAT, CHAR(n), VARCHAR(n), TEXT; optional VARCHAR length and default CHAR length 1 |
| Inserts | INSERT INTO ... VALUES (...), (...); optional, reordered column lists; constant expressions |
| Updates | UPDATE ... SET ... WHERE ...; expressions evaluated against the original row; primary-key changes; all-row updates |
| Deletes | DELETE FROM ... WHERE ...; all-row deletion |
| Transactions | BEGIN, BEGIN WORK, BEGIN TRANSACTION, COMMIT/END, ROLLBACK; optional WORK/TRANSACTION suffix; automatic statement transactions |
| Projection | Columns, literals, scalar expressions, aliases, *, table.*, SELECT without FROM |
| Joins | Comma joins, CROSS JOIN, JOIN/INNER JOIN ... ON, LEFT [OUTER] JOIN ... ON; aliases and self joins |
| Filtering | Comparisons, IS/IS NOT, AND/OR/NOT, IN/NOT IN lists, BETWEEN/NOT BETWEEN, LIKE/NOT LIKE, IS NULL/IS NOT NULL |
| Result shaping | DISTINCT, GROUP BY, HAVING, ORDER BY ASC/DESC; output aliases and ordinals in GROUP BY/ORDER BY; HAVING aliases |
| Aggregates | COUNT(*), COUNT(expr), SUM, AVG, TOTAL, MIN, MAX; DISTINCT arguments; null and empty-group handling |
| Scalar operations | +, -, *, /, %, unary signs, concatenation; predicates return 1/0/NULL; scalar truth conditions |
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

- **Table identity:** every table retains one primary-key column. If none is
  declared, the first column becomes the primary key, preserving this project's
  existing convention. Hidden SQLite rowid tables, automatic integer key
  allocation, and composite keys are not implemented.
- **Types and lengths:** schemas retain strong stored types. INTEGER values in
  new tables occupy signed 64 bits; original INTEGER schemas retain 32 bits.
  REAL stores doubles. CHAR pads to its byte width, and strings exceeding their
  declared byte width fail instead of being silently truncated. SQLite's
  dynamic storage classes and VARCHAR length behavior differ. Basic comparison
  affinity and numeric expression conversion are supported, but complete SQLite
  type/formatting behavior remains work.
- **Storage:** new catalog records have a version marker and use UTF-8 strings.
  Unversioned tables retain their original single-byte encoding and integer
  widths; unsupported characters in those tables are rejected rather than
  corrupted. Existing data is not automatically rewritten. Stored row values
  retain signed-short offsets and a 32767-byte maximum, including the header.
- **Constraints:** PRIMARY KEY and NOT NULL are implemented. Defaults, UNIQUE,
  CHECK, foreign keys, AUTOINCREMENT, and ALTER TABLE remain work.
- **Query language:** subqueries, EXISTS, UNION/INTERSECT/EXCEPT, CTEs, window
  functions, CASE, CAST, general scalar/date functions, bind parameters,
  NATURAL/USING/RIGHT/FULL joins, and custom collations remain work. Unsupported
  syntax fails rather than silently ignoring a clause.
- **Writes:** INSERT ... SELECT, ON CONFLICT variants, RETURNING, and savepoints
  are absent. Duplicate UPDATE assignments are rejected. Multi-row primary-key
  updates validate final uniqueness, which can differ from SQLite's immediate
  conflict checks. Logical mutation errors are detected before writing and
  preserve an explicit transaction's earlier statements. Storage failures
  conservatively roll back the entire active transaction.
- **Execution:** one serialized database session owns process-wide state.
  Concurrency across independent SQL sessions is not implemented. Queries use
  scans and nested-loop joins; sorting, grouping, DISTINCT, and mutations keep
  working data in memory. No indexes, planner, cancellation, or runtime resource
  budget is advertised.

## Verification

Run `python tests/run_tests.py`. It builds fresh classes from regenerated parser
sources, runs engine assertions against disposable data, compares supported
queries against Python's SQLite, and checks clean restarts, unfinished-transaction
shutdown, forced process termination/recovery, and CLI error recovery.

A golden fixture checks reading and updating the original unversioned storage
format. The runner hashes existing `dbms/db` files before and after verification.
These checks establish the tested cases, not general proof of SQLite parity,
arbitrary crash safety, or concurrent isolation.

SQLite references: [SELECT](https://www.sqlite.org/lang_select.html),
[expressions](https://www.sqlite.org/lang_expr.html), and
[transactions](https://www.sqlite.org/lang_transaction.html).
