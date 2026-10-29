# AI SQL Engine

A Java SQL engine backed by Berkeley DB. The first project milestone is completing
the engine toward SQLite compatibility; SQL agents and analytics come afterward.

The implementation keeps the existing table/column model, row encoding structure,
and individual SQL statement classes. See the [roadmap](docs/ROADMAP.md) and
[SQL capabilities](docs/SQL_COMPATIBILITY.md) for current support and remaining work.

## Build and run

Requires a JDK with `java` and `javac` on PATH. Verification also requires Python 3.
Dependencies are bundled under `dbms/lib`; no package downloads are needed.
Development has been checked on Java 21.

```powershell
python scripts/build.py
java -cp ".build/classes;dbms/lib/*" DBMS ".local-db"
```

On macOS/Linux, use `:` instead of `;` in the Java classpath. The CLI argument
selects the database directory. Omitting it uses `db` under the working directory.
Use a disposable directory for experiments; the existing `dbms/db` is preserved.

```sql
CREATE TABLE sales (
    id INTEGER PRIMARY KEY,
    category TEXT,
    amount REAL
);
INSERT INTO sales VALUES (1, 'books', 12.5), (2, 'books', 7.5), (3, 'games', 30.0);

SELECT category, COUNT(*) AS orders, SUM(amount) AS revenue
FROM sales
GROUP BY category
HAVING revenue > 10
ORDER BY revenue DESC;

BEGIN;
UPDATE sales SET amount = amount + 1 WHERE id = 1;
DELETE FROM sales WHERE id = 2;
ROLLBACK;
```

Statements end with semicolons and may span lines. Enter `q` to exit. Closing the
database rolls back an unfinished transaction.

New tables support SQLite-style row IDs. You can omit an INTEGER PRIMARY KEY
value to generate it, or create a table without a declared primary key and query
its hidden `rowid`. Existing tables retain their stored format and key rules.
See [row IDs and primary keys](docs/ROW_IDENTITY.md) for examples and limits.

New schemas also support literal/expression/time DEFAULT values, named constraints,
composite keys, CHECK expressions,
foreign-key actions and deferral. Invalid immediate writes are checked before
modifying data, including changes that would leave orphaned child rows. See the
[constraint guide](docs/CONSTRAINTS.md) for examples, persistence, and limitations.

The next six engine stages are implemented: ALTER additions/renames, persistent
indexes and equality plans, subqueries/CTEs/compounds, CASE/CAST and scalar functions,
INSERT SELECT/UPSERT/RETURNING, and composite integrity/savepoints. Read the
[feature examples](docs/SQL_FEATURES.md) and [stage report](docs/IMPLEMENTATION_STAGES.md)
for supported forms, tests, and remaining limits. This is still a SQLite-oriented
subset, with one serialized session and materialized working data.

The follow-up schema work adds expression/UTC time defaults, named declarations,
and dependency-checked ALTER TABLE DROP COLUMN. See the
[schema guide](docs/SCHEMA_CHANGES.md) for examples, compatibility, and staged checks.

## Verify and regenerate

```powershell
python tests/run_tests.py
python scripts/build.py --regenerate
```

Tests use temporary databases and compare supported behavior with SQLite.
The suite includes seeded SQL combinations, six additional crash-recovery
scenarios, and a 4,096-row fixture for index accuracy and adapter limits. Run
`python tests/reliability_checks.py` for these checks alone; see the
[validation guide](docs/VALIDATION.md) for seeds, reproduction, and coverage limits.
Edit `dbms/sql.cup` and `dbms/sql.flex` to change grammar or tokens, then regenerate
the checked-in Java parser and lexer. CUP's generated parser emits an unchecked
operations note during compilation.

For Java callers, `DBMS.init(Path)` opens a database and `DBMS.execute(String)`
returns a `QueryResult` containing column labels, typed row values, and an affected
row count. SQL failures throw exceptions. Call `DBMS.shutdown()` when finished.

## Database client and read-only adapter

`DatabaseAdapter` adds schema discovery, a fixed caller/table policy, positional
parameters, bounded SELECT execution, logical result types, and private result
IDs with expiry. The engine remains a single serialized session.

Open a persistent client against your existing database:

```powershell
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db"
```

Inspect the schema and enter your own SELECT queries. These commands assume the
`sales` table above; use `.tables` to find your actual table names:

```text
.tables
.schema sales
SELECT * FROM sales;
.params ["books"]
SELECT * FROM sales WHERE category = ?;
.result <printed-result-id>
.quit
```

For a one-shot query or programmatic integration:

```powershell
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db" --query "SELECT * FROM sales;" --json
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db" --json --allow-table sales
```

The second command accepts JSON Lines requests over stdin and returns structured
responses on stdout. Add `--schema-only` to permit schema discovery without rows.
Use the same database directory as `DBMS`; `.local-db`, `db`, and `dbms/db` are
separate databases. The client does not seed tables or initialize missing folders.

Read the [client guide](docs/CLIENT.md) for commands, JSON operations, permissions,
and configurable ceilings, or the [adapter API guide](docs/ADAPTER.md) for Java
usage. Results use the adapter's normal limits, without a fixed sample-row query.
Artifacts remain available within a session until expiry or eviction and disappear
when it closes. The engine remains read-only through this interface.
