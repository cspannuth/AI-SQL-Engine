# SQL engine completion and AI analytics roadmap

## Project order

Complete the Java DBMS as the first milestone, aiming for SQLite compatibility.
Keep the existing Berkeley DB backend, table/column schema, and statement classes.
Favor readable methods and small supporting classes over a new framework.
A local read-only adapter and first SQL question-answering application are now
available alongside that engine work. Chart rendering remains a later milestone.

The engine now implements the previously missing execution paths and a broader
query dialect. This establishes a working core; full SQLite compatibility still
requires the engine work listed below. The exact supported syntax and behavioral
differences live in [SQL_COMPATIBILITY.md](SQL_COMPATIBILITY.md).

## 1. Finish the SQL engine

### Implemented core

- CREATE/DROP TABLE with atomic catalog updates and IF [NOT] EXISTS.
- Literal/expression/time defaults and INSERT DEFAULT VALUES; named constraints,
  single/composite UNIQUE and primary
  keys, CHECK, immediate/deferred foreign keys, and referential actions. New
  metadata persists in catalog version 6 while earlier row formats remain
  writable. See [CONSTRAINTS.md](CONSTRAINTS.md) for supported forms and limits.
- INSERT with column lists and multiple VALUES rows.
- INSERT SELECT, one-clause UPSERT, RETURNING, WITH-prefixed writes, and savepoints.
- UPDATE and DELETE with filters, constraints, and affected-row counts.
- Explicit BEGIN/COMMIT/ROLLBACK and automatic statement transactions.
- Named projections, wildcards, aliases, literals, and SELECT without FROM.
- Comma joins, CROSS JOIN, INNER JOIN, and LEFT JOIN with ON predicates.
- DISTINCT, ORDER BY, LIMIT/OFFSET, grouping, and HAVING.
- COUNT, SUM, AVG, TOTAL, MIN, MAX, and DISTINCT aggregate arguments.
- Arithmetic, concatenation, comparisons, scalar boolean expressions, IN,
  BETWEEN, LIKE, and SQL three-valued null logic.
- ALTER additions/renames/column removal; persistent single-column indexes and equality plans;
  subqueries, derived tables, ordinary CTEs and compounds; CASE, CAST, and scalar
  null/text/numeric/UTC date functions.
- Automatic single/composite constraint indexes, direct physical key lookups,
  early inner-join filters and heuristic join ordering, and indexed uniqueness/
  parent-reference probes. Writable startup backfills missing indexes without
  rewriting row bytes. See [PERFORMANCE.md](PERFORMANCE.md) for measured results.
- Case-insensitive identifiers, underscores, quoted identifiers, escaped strings,
  SQL comments, and multiline CLI statements.
- Typed result values and exceptions independently of CLI printing.
- Versioned UTF-8 schemas for new tables, 64-bit new INTEGER columns, and reading
  and updating tables in the original single-byte/32-bit format.
- Internal row IDs for new tables, INTEGER PRIMARY KEY allocation, hidden ID
  aliases, and independent ordinary primary-key constraints. Existing unversioned
  and version 1 tables retain their original keys. See [ROW_IDENTITY.md](ROW_IDENTITY.md).
- A reproducible local build, maintained CUP/JFlex specifications, isolated engine
  regressions, direct SQLite comparisons, restart tests, and process-crash recovery.

Run verification with `python tests/run_tests.py`. The test runner reports the
current assertion and SQLite comparison counts; keep these behavioral tests
alongside each engine improvement.

### Remaining engine work before claiming SQLite compatibility

The six requested stages now have implementations and cumulative test gates:
ALTER additions/renames; persistent equality indexes and EXPLAIN; subqueries,
ordinary CTEs and compounds; CASE/CAST and scalar functions; query inserts,
UPSERT and RETURNING; composite keys, referential actions/deferral, and savepoints.
See [IMPLEMENTATION_STAGES.md](IMPLEMENTATION_STAGES.md) for achievements and
[SQL_FEATURES.md](SQL_FEATURES.md) for examples and boundaries.
The follow-up schema improvements are also implemented: expression/UTC time
defaults, named declarations, and dependency-checked DROP COLUMN, with restart,
old-format, and recovery tests. See [SCHEMA_CHANGES.md](SCHEMA_CHANGES.md).

1. **Schema/type parity.** Complete SQLite affinity/formatting, arbitrary schema
   changes, and optional row-format migration. Older catalogs without declaration
   ownership retain conservative DROP restrictions. Preserve existing files and
   test every format transition.
2. **Query breadth.** Recursive CTEs, window functions, broader join syntax, views,
   triggers, named parameters, additional functions/operators, and collations.
3. **Write parity.** Multiple conflict clauses, OR REPLACE/IGNORE, broader RETURNING,
   and SQLite's exact immediate uniqueness/self-referential mutation ordering.
4. **Planning.** Range/order access, user-declared composite/partial/expression
   indexes, temporary automatic indexes, statistics-based plans, and broader
   join optimization. Automatic constraint indexes and initial filter/join-order
   improvements are implemented and tested; see [PERFORMANCE.md](PERFORMANCE.md).
5. **Resource use.** Streaming, hard execution isolation, and more scalable
   savepoints. Current savepoints snapshot engine-owned data in memory; nested
   queries, sorting, grouping, and mutations also materialize working data.
6. **Reliability.** Seeded differential coverage, six additional recovery
   scenarios, and a 4,096-row correctness/resource fixture are now in the default
   suite; see [VALIDATION.md](VALIDATION.md). Extend these with larger workloads,
   mid-write fault injection, and additional generated cases. Define session and
   concurrency guarantees before exposing multiple connections; the current engine
   owns one serialized session. Verify dependency licensing before distributing
   the bundled engine.

Completion gate: the advertised dialect works end to end, remaining intentional
differences are documented, regression and SQLite comparison suites pass, and
storage/transaction guarantees match observed recovery behavior. Engine features
must not be postponed merely to get an AI demo running.

SQLite is the behavioral reference, not a replacement execution backend. Compare
semantically equivalent queries on the shared syntax, with deterministic ORDER BY
where ordering matters. Do not compare SQL text or unordered row traversal.

## 2. Build the database adapter

The first local implementation is available as `DatabaseAdapter`. It provides
schema/capability discovery, fixed caller/table authorization, positional bindings,
bounded read-only queries, logical result types, structured errors, and immutable
result artifacts with count/byte/TTL retention. See [ADAPTER.md](ADAPTER.md) for
usage and the tested contract. Results are in memory and private to one adapter
instance; no model or transport is required.

`DatabaseClient` makes the interface executable through persistent terminal
sessions, one-shot commands, and JSON Lines requests/responses. It accepts actual
SQL and bound values, configurable budgets, fixed table policies, and schema-only
sessions. Result IDs can be retrieved while the owning session remains open.
See [CLIENT.md](CLIENT.md) for the client contract.

Continue building on `DBMS.execute` and `QueryResult`; keep CLI formatting out of
the integration. Add HTTP or MCP transport when there is a consumer.

Validate statements and identifiers through the parser/catalog. Enforce caller,
table, and operation permissions in application code. Keep one engine owner until
separate sessions and transaction isolation are implemented.

Result envelopes include logical types, query/result IDs, SQL and bound values,
completeness, effective limits, timing, and capture/expiry times. Remaining work
includes database snapshot/freshness versions, optional durable artifact history,
and authenticated multi-user sessions.

Gate: integration tests cover success, SQL errors, access restrictions, nulls,
truncation, execution limits, and database-directory isolation without a model.

## 3. Add SQL question answering

The first application is implemented under `sql_agent/`: a persistent Python
client, compact schema/metric context, isolated drafting/explanation model calls,
bounded repairs, direct formatting, executable tools, and result/visualization
handoffs. Hosted access is configurable; the example uses a low-cost hosted model
and loads its key from an ignored `.env` file.
See [SQL_AGENT.md](SQL_AGENT.md).

Offline integration checks and a shared-context comparison harness are available.
Next, run the curated cases with a real hosted model, review SQL and explanations,
measure provider-reported tokens/latency, and refine metric definitions and prompts.
Scripted tests do not establish those results.

The SQL analysis and result-explanation skills follow actual adapter contracts. Supply live
schema/capability metadata and a maintained data dictionary: units, metric
definitions, grouping grain, relationships, and known limitations.

The workflow is question -> schema/context -> SQL -> validated tool execution ->
result inspection -> evidence-based answer. Preserve the question, SQL, result
ID, filters, and assumptions for follow-up requests. Requery when the scope or
freshness requirement changes. Never invent database output.

Give the query-drafting stage schema and metric definitions first; table rows
are optional targeted context. That stage returns SQL plus parameters. Trusted
application code routes execution through the adapter/client, and the DBMS returns
the actual results to the answer or visualization stage. Metadata discovery must
not implicitly query table contents.

Repository guidance belongs in `AGENTS.md`; reusable Codex workflows belong in
`.agents/skills/<name>/SKILL.md`. A custom application must load the workflow
instructions and register executable tools explicitly. A skill file does not
provide connectivity or enforce permissions.

Gate: a curated question set produces correct results and grounded answers,
including empty data, ambiguous metrics, SQL errors, and instruction-like text
stored in database rows.

## 4. Add analytics and visualization

Give the visualization skill a structured handoff: original question, authorized
result artifact, executed SQL, column types, completeness, units, filters,
grouping grain, metric definitions, and relevant time context.

Use one controlled renderer initially. Validate fields, transformations, labels,
and plotted numbers. Route new data requirements back through the query tools.
Never infer full-population totals from truncated data. Save shareable charts
with enough SQL and context to reproduce them.

Gate: calculations and chart specifications match source results, artifacts
render successfully, and empty/incomplete datasets are handled explicitly.

## Suggested application after engine completion

A synthetic sales analytics workbench is a useful first application. Start with
known fixtures and explicit metric definitions, then support questions, visible
SQL, answers, charts, and follow-up analysis. Begin with one orchestrator using
isolated SQL-drafting/explanation contexts and visualization skills. Introduce
separate operating-system agent processes when measured quality or isolation
warrants them.
