# Database client

`DatabaseClient` is the executable interface to `DatabaseAdapter`. It supports
schema discovery, arbitrary supported SELECT queries, bound parameters, configurable
budgets, and retrieval of results during a persistent session. Opening it does not
automatically query a table or apply a fixed sample-row LIMIT.

The client uses an existing database directory. Use the same directory used with
`DBMS`. Missing directories and empty folders are reported without initializing a
new database. Use one owning client at a time; the engine remains a single session.

## Terminal session

From the repository root:

```powershell
python scripts/build.py
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db"
```

Use `:` instead of `;` in the classpath on macOS/Linux. Inspect the actual schema
before selecting columns. These queries assume the README's `sales` table:

```text
.tables
.schema sales
SELECT * FROM sales;
.params ["books"]
SELECT category, SUM(amount) AS revenue
FROM sales
WHERE category = ?
GROUP BY category;
.result <printed-result-id>
.quit
```

SELECT statements end with semicolons and may span lines. Multiple complete
statements can appear on a line. The adapter still accepts only one statement per
individual request; the terminal submits complete statements sequentially.

| Command | Behavior |
| --- | --- |
| `.tables` | List tables allowed for this caller |
| `.schema <table>` | Describe the selected table without reading its rows |
| `.capabilities` | Show available operations, dialect, policy mode, limits, and retention |
| `.params <JSON-array>` | Bind values to the next SQL statement, then clear them |
| `.result <id>` | Retrieve the captured result without rerunning its SQL |
| `.clear` | Clear pending SQL and parameters |
| `.quit` | Close the session, including unfinished input |
| `.help` | Show options and commands |

`q` also exits when no SQL is pending. Individual errors are reported and the
session remains available for the next request. Incomplete SQL at end of input
is an error. Terminal strings and row values are escaped so embedded newlines
remain part of their values rather than appearing as extra result rows.

Results show typed columns, actual rows, the returned row count, completeness,
truncation reason if applicable, and a result ID. Default adapter output ceilings
remain 1,000 rows and about 1 MiB of logical payload. Raise configured ceilings
explicitly when needed; there is no additional ten-row example restriction.

## One-shot commands

```powershell
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db" --tables
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db" --schema sales
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db" --capabilities
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db" --query "SELECT * FROM sales;"
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db" --query "SELECT * FROM sales WHERE amount > ?;" --params '[10]' --json
```

`--list-tables` is an alias for `--tables`. Choose one one-shot operation. Append
`--json` for a machine-readable response. `--params` takes a JSON array and requires
`--query`; use `.params` in a terminal session. Shell quoting of strings varies;
programmatic clients should pass argument vectors or use the JSON Lines interface.

A one-shot process closes after its response, so its in-memory result IDs cannot
be retrieved in another process. Use a persistent session for follow-up retrieval.

## JSON Lines interface

Start a persistent process with stdin/stdout pipes:

```powershell
java -cp ".build/classes;dbms/lib/*" DatabaseClient ".local-db" --json --allow-table sales
```

Send one JSON object per line. The process writes one JSON response line per
request and flushes it immediately. All input/output uses UTF-8, without prompts
or startup banners in JSON mode. Keep stdin open to preserve the session.

```json
{"id":"schema","operation":"describe_table","table":"sales"}
{"id":"query","operation":"query_readonly","sql":"SELECT * FROM sales WHERE amount > ? ORDER BY amount;","parameters":[10]}
{"id":"retrieve","operation":"get_result","resultId":"<resultId from query response>"}
{"id":"done","operation":"close"}
```

| Operation | Request fields in addition to optional `id` |
| --- | --- |
| `get_capabilities` | `operation` |
| `list_tables` | `operation` |
| `describe_table` | `operation`, `table` |
| `query_readonly` | `operation`, `sql`, optional `parameters` and `limits` |
| `get_result` | `operation`, `resultId` |
| `close` | `operation` |

`describe_table` returns declared columns plus `rowIdTable` and `rowIdColumn`.
New tables expose hidden row ID aliases; older tables retain their original key
rules. See [row identity](ROW_IDENTITY.md) before querying hidden IDs.
Column metadata also includes UNIQUE and default declarations, and table metadata
includes CHECK SQL and foreign-key descriptors. See [constraints](CONSTRAINTS.md)
for field meanings and supported behavior.
`defaultSql` exposes expression/time defaults without evaluating them, and
`constraints` preserves declaration names and inline/table scope. These are also
available in schema-only sessions.
Schema results also expose ordered primary/composite UNIQUE keys and explicit
indexes. Read operations accept WITH/subqueries/compounds and EXPLAIN QUERY PLAN
under the same nested table policy. RETURNING does not make a write read-only.

`id` may be a string or signed 64-bit integer; omitted IDs return null. Unknown
fields, duplicate object keys, invalid JSON, and unsupported types fail explicitly.
Wire requests are capped at 256 KiB per line and 32 levels of JSON nesting. Adapter
SQL/bound-value payload limits apply separately. An oversized line is discarded
through its newline so the next request can recover.

Success responses have `id`, `ok: true`, `operation`, and `data`. Query/retrieval
data includes the complete `ReadResult` fields plus `returnedRows`, `truncated`,
and `complete`. Schema responses contain metadata only. Capabilities data contains
`protocolVersion: 1`, `schemaOnly`, the allowed `operations`, and the underlying
adapter capabilities under `database`.

Errors have this shape:

```json
{"id":"query","ok":false,"error":{"code":"ACCESS_DENIED","message":"Only SELECT statements are allowed","requestId":"<engine-request-id>","limit":null}}
```

The echoed `id` correlates client requests. `error.requestId`, when available,
identifies the adapter request. Causes and stack traces are not serialized.
Malformed input with no usable ID returns null. Non-finite numeric results cause
an explicit `DATABASE_ERROR` when encoded, rather than silently replacing numbers
with null. Integer results retain 64-bit values; consumers must preserve precision.

EOF or `close` ends the session and clears its result cache. Failed requests do
not end the session. Exit status is 0 on success, 1 if a request/startup/I/O failure
occurred, and 2 for invalid command-line configuration. A session that recovered
from a failed request still exits with status 1. `--help` writes usage text.

## Permissions and query limits

The default is local owner read access to all tables. Repeat `--allow-table <name>`
to restrict tables, and set `--caller <id>` to label a fixed caller. Requests cannot
change their caller or table policy.

`--schema-only` exposes capabilities, table listing, table descriptions, and close.
Queries and result retrieval return `ACCESS_DENIED`. This supports a query-drafting
agent that needs metadata, while trusted application code controls execution.
A host using the Java API can expose different tools to agent roles within one
owning session. These options establish local authorization, not authentication
or isolation from trusted Java code with direct engine access.

Configure execution ceilings with these flags:

```text
--max-rows <n>             --max-bytes <n>
--max-scanned-rows <n>     --max-working-rows <n>
--max-working-bytes <n>    --timeout-ms <n>
--max-results <n>          --max-retained-bytes <n>
--result-ttl-seconds <n>
```

`query_readonly.limits` may lower the configured `maxRows`, `maxResultBytes`,
`maxScannedRows`, `maxWorkingRows`, `maxWorkingBytes`, or `timeoutMillis`; it cannot
raise them. Row/byte ceilings produce clearly marked previews, while scan, work,
and cooperative deadline limits fail the request. Sorting/grouping can require
more working data than the returned rows; adjust corresponding ceilings together.

Results and their IDs remain private to this process/caller until expiry, eviction,
or closure. They are captured values, not live queries or durable analysis history.
Read [ADAPTER.md](ADAPTER.md) for payload accounting, completeness, retention,
deadline guarantees, and the Java API.

## Deterministic execution workflow

The query-writing stage uses schema and metric context to produce SQL plus bound
values. Trusted application code submits that structured request to the client or
Java adapter. The DBMS executes the validated SQL and returns actual results for
the answer/analytics stage. The client performs no model calls or automatic SQL
generation, and table discovery does not fetch table contents.

Run `python tests/run_tests.py` for codec/protocol, terminal, session, permissions,
parameter, truncation, Unicode, and error-recovery checks alongside engine tests.
