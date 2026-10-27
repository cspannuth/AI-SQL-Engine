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

## Verify and regenerate

```powershell
python tests/run_tests.py
python scripts/build.py --regenerate
```

Tests use temporary databases and compare supported behavior with SQLite.
Edit `dbms/sql.cup` and `dbms/sql.flex` to change grammar or tokens, then regenerate
the checked-in Java parser and lexer. CUP's generated parser emits an unchecked
operations note during compilation.

For Java callers, `DBMS.init(Path)` opens a database and `DBMS.execute(String)`
returns a `QueryResult` containing column labels, typed row values, and an affected
row count. SQL failures throw exceptions. Call `DBMS.shutdown()` when finished.
