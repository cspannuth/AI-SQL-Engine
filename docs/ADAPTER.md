# Local database adapter

`DatabaseAdapter` is a read-only Java interface around the existing engine. It
owns one database environment, a fixed caller/table policy, execution limits,
and an in-memory result cache. No model, HTTP server, or MCP server is required.

## Open and query

The engine uses the default Java package, so callers currently use that package too.

```java
var policy = DatabaseAdapter.AccessPolicy.readTables("analyst", Set.of("sales"));
try (DatabaseAdapter adapter = DatabaseAdapter.open(Paths.get(".local-db"), policy)) {
    System.out.println(adapter.listTables());
    System.out.println(adapter.describeTable("sales"));

    var result = adapter.queryReadOnly(
        "SELECT category, SUM(amount) AS revenue FROM sales "
        + "WHERE category = ? GROUP BY category;",
        List.of("books")
    );
    System.out.println(result.columns());
    System.out.println(result.rows());
    System.out.println("Complete: " + result.complete());

    // Retrieves the same immutable values, without executing the SQL again.
    var sameResult = adapter.getResult(result.resultId());
}
```

Import `java.nio.file.Paths` and `java.util.*` for this example. Closing the adapter
closes its database and clears retained results. It does not delete stored tables.

Use the persistent database client from a terminal or application:

```powershell
python scripts/build.py
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db"
```

Use `.tables`, `.schema <table>`, and your own SELECT statements. Results remain
available through `.result <id>` while the session stays open. `--json` provides
JSON Lines requests/responses, and `--schema-only` restricts operations to metadata.
There is no automatic table query or extra sample-row limit. See
[CLIENT.md](CLIENT.md) for one-shot commands, protocol fields, and configuration.
Use `:` instead of `;` in the classpath on macOS/Linux and the same database
directory as the DBMS CLI. Data written under `db` will not appear in `.local-db`.

## Operations

| Method | Contract |
| --- | --- |
| `getCapabilities()` | API version, supported feature summary, current limitations, request bounds, query ceilings, and retention settings |
| `listTables()` | Sorted names of existing tables allowed by the fixed policy |
| `describeTable(name)` | Canonical table name, ordered columns with stored type, byte width, nullability, and primary-key flag; row ID availability and aliased key name |
| `queryReadOnly(sql, parameters)` | Parse and bind one SELECT, authorize every source table, execute within budgets, and retain an immutable result |
| `queryReadOnly(sql, parameters, limits)` | The same operation with lower per-request limits |
| `getResult(resultId)` | Retrieve a retained, unexpired result from this adapter instance |
| `close()` | Release the owned engine and cache; repeated closes are harmless |

All operations are serialized using the engine monitor. An adapter refuses to open
while another engine environment is open. It also refuses reads during an explicit
transaction started by trusted host code. A stale adapter cannot access or close
a replacement environment opened through the engine API.

The host identifies its caller and creates `AccessPolicy` in trusted code. Table
names are matched case-insensitively. An empty allowlist permits table-free SELECT
expressions and exposes no tables. `readAll(callerId)` grants all-table access
explicitly. This is an application authorization boundary, not authentication or
a sandbox against other Java code that can call `DBMS` directly.

## Parameter binding

Use positional `?` markers for scalar values, including LIMIT/OFFSET values. Bind
exactly one value per marker in its lexical order. Quoted question marks and
comments do not create parameters. Values are assigned to AST nodes; no SQL text
interpolation occurs.

Accepted values are null, strings, booleans, byte/short/int/long integers, and finite
float/double numbers. Integers normalize to `Long`, floating values to `Double`,
and booleans to 1/0. Use `Arrays.asList(...)` when a parameter list contains null;
Java's `List.of(...)` rejects nulls. LIMIT/OFFSET require integer values.

Parameters cannot stand for table/column names, SQL keywords, or SQL fragments.
Select identifiers from catalog metadata and quote them correctly when constructing
SQL. Named and numbered parameters are not implemented.

Trusted engine callers can also use `DBMS.execute(sql, parameters)` for other SQL
statement types. The adapter itself exposes SELECT only. Parsing uses the maintained
CUP/JFlex specifications, including binding-count validation before execution.

## Results and errors

`ReadResult` contains request/result IDs, an opaque database-instance ID, caller ID,
original SQL, normalized bound values, logical columns, immutable rows, capture and
expiry times, elapsed milliseconds, execution counters, and the effective limits.
`payloadBytes` estimates retained SQL, parameter, column, and row payload.

Result types are INTEGER, REAL, NUMERIC, TEXT, NULL, or UNKNOWN. NUMERIC permits
integer and real values, including expressions that can overflow into REAL.
Schema-derived types remain available for empty or entirely-null columns.
Table metadata includes `rowIdTable` and `rowIdColumn`; these distinguish new rowid
tables from older formats without reading data. Hidden IDs are absent from the
declared column list and `SELECT *`. See [ROW_IDENTITY.md](ROW_IDENTITY.md).
Columns also expose `unique`, `hasDefault`, and `defaultValue`; tables expose
`checks` and `foreignKeys`. These describe persisted constraints without reading
rows or resolving parent tables. See [CONSTRAINTS.md](CONSTRAINTS.md).
Nulls remain null rather than becoming the string `"null"` or zero.

`complete()` describes the submitted SQL result. For example, a successful
`SELECT ... LIMIT 10` can be complete for that query while representing only ten
rows of a larger table. Additional adapter row/byte caps produce `truncated()`,
with reason `ROWS` or `BYTES`. If no row fits the byte budget, the result can have
zero rows and still be truncated. Do not interpret that as an empty population or
use a preview for full-population totals.

Query failures throw `AdapterException` with a stable `getCode()`, a query
`getRequestId()`, and a `getLimit()` detail for execution-budget errors. Metadata
operations may have no request ID. Internal exception causes are for trusted
local diagnostics; a future transport should return the public code/message and
request ID rather than serializing causes or stack traces.

| Code | Meaning |
| --- | --- |
| `INVALID_REQUEST` | Missing/oversized request, unsupported parameter value, excessive SQL complexity, or limits above configured ceilings |
| `SQL_ERROR` | Syntax, binding-count, schema-resolution, or expression error |
| `ACCESS_DENIED` | Non-SELECT statement or disallowed source table |
| `LIMIT_EXCEEDED` | Scan, working-data, deadline/interruption, result-header, or retention capacity exceeded |
| `NOT_FOUND` | An allowed table is missing, or an artifact is unknown/expired/evicted |
| `TRANSACTION_ACTIVE` | Trusted host code has an unfinished transaction |
| `CLOSED` | Adapter closed, or its engine environment was replaced |
| `DATABASE_ERROR` | Opening or executing against storage failed |

Budget failures return no partial successful result. Failed implicit transactions
are rolled back and budget state is cleared before the next request.

## Limits and retention

Defaults are configurable when opening the adapter:

| Bound | Default |
| --- | --- |
| Returned rows | 1,000 |
| Returned column/value payload | 1 MiB |
| Stored table rows visited, counting repeated join visits | 100,000 |
| Intermediate row/group allocations | 10,000 |
| Cumulative intermediate payload estimate | 16 MiB |
| Cooperative deadline | 5 seconds |
| Request SQL and bound-value payload | 64 KiB |
| Bound parameters | 256 |
| SQL tokens / parenthesis depth / consecutive prefix operators | 512 / 32 / 32 |
| Retained results / cumulative retained payload | 20 / 32 MiB |
| Artifact lifetime | 10 minutes |

`QueryLimits` configures row, result-byte, scan, working-row, working-byte, and time
ceilings. Per-request settings may lower those ceilings. `Retention` configures
artifact count, byte capacity, and TTL. Oversized metadata discovery fails explicitly
rather than silently omitting allowed tables.

Row/byte caps produce previews; scan/work/time bounds fail the query. Simple
unordered reads stop after one extra row proves truncation. Sorts, groups, and
DISTINCT still inspect their input and are subject to intermediate budgets.
Working-byte accounting includes logical retained values, grouping/distinct keys,
and representatives; replacing a value does not refund its earlier allocation.
It is conservative payload accounting, not exact JVM heap accounting.

Deadline checks run through scanning and query processing, including sort
comparisons. They are cooperative: they cannot forcibly preempt a blocking storage
call or a long-running library call. Time spent waiting for the engine monitor is
outside the execution deadline. Thread interruption is checked at budget checkpoints.
This interface does not provide hard process or memory isolation.

Artifacts are scoped to one adapter instance/caller. Expired results are removed
on retrieval or retention operations; capacity eviction removes the oldest result.
Retrieval does not renew TTL. Results do not survive adapter closure or process
restart. They preserve captured values but do not provide a database snapshot
version, automatic freshness checks, or persistent analysis history.

## Verification and remaining work

Run `python tests/run_tests.py`. Adapter integration tests cover schema filtering,
read-only enforcement, aliases/joins, parameter injection attempts, immutable and
empty results, truncation, scan/work limits, deterministic expiry/deadlines,
interruption cleanup, and directory/lifecycle isolation. The existing engine,
SQLite comparison, persistence, and crash-recovery checks also run.

The remaining SQLite compatibility work stays in [ROADMAP.md](ROADMAP.md).
The terminal and JSON Lines process interface are implemented in `DatabaseClient`.
Network transports, authenticated multi-user sessions, durable artifact history,
and the AI/visualization application are separate future work.
