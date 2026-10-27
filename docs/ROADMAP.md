# SQL engine completion and AI analytics roadmap

## Project order

Complete the Java DBMS as the first milestone, aiming for SQLite compatibility.
Keep the existing Berkeley DB backend, table/column schema, and statement classes.
Favor readable methods and small supporting classes over a new framework.
The database adapter, AI skills, and visualization application come afterward.

The engine now implements the previously missing execution paths and a broader
query dialect. This establishes a working core; full SQLite compatibility still
requires the engine work listed below. The exact supported syntax and behavioral
differences live in [SQL_COMPATIBILITY.md](SQL_COMPATIBILITY.md).

## 1. Finish the SQL engine

### Implemented core

- CREATE/DROP TABLE with atomic catalog updates and IF [NOT] EXISTS.
- INSERT with column lists and multiple VALUES rows.
- UPDATE and DELETE with filters, constraints, and affected-row counts.
- Explicit BEGIN/COMMIT/ROLLBACK and automatic statement transactions.
- Named projections, wildcards, aliases, literals, and SELECT without FROM.
- Comma joins, CROSS JOIN, INNER JOIN, and LEFT JOIN with ON predicates.
- DISTINCT, ORDER BY, LIMIT/OFFSET, grouping, and HAVING.
- COUNT, SUM, AVG, TOTAL, MIN, MAX, and DISTINCT aggregate arguments.
- Arithmetic, concatenation, comparisons, scalar boolean expressions, IN,
  BETWEEN, LIKE, and SQL three-valued null logic.
- Case-insensitive identifiers, underscores, quoted identifiers, escaped strings,
  SQL comments, and multiline CLI statements.
- Typed result values and exceptions independently of CLI printing.
- Versioned UTF-8 schemas for new tables, 64-bit new INTEGER columns, and reading
  and updating tables in the original single-byte/32-bit format.
- A reproducible local build, maintained CUP/JFlex specifications, isolated engine
  regressions, direct SQLite comparisons, restart tests, and process-crash recovery.

Run verification with `python tests/run_tests.py`. The test runner reports the
current assertion and SQLite comparison counts; keep these behavioral tests
alongside each engine improvement.

### Remaining engine work before claiming SQLite compatibility

1. **Schema and type semantics.** Resolve the legacy implicit first-column primary
   key versus SQLite rowid tables, generated integer keys, type affinity, defaults,
   UNIQUE/CHECK/foreign-key constraints, composite keys, and ALTER TABLE. Introduce
   explicit format migrations where required; preserve existing database files.
2. **Query composition.** Add subqueries, correlated subqueries, EXISTS, compound
   SELECT operations, CTEs, INSERT ... SELECT, and broader join syntax.
3. **Expressions and functions.** Add CASE, CAST, common scalar/string/date
   functions, bind parameters, collations, and the remaining SQLite operators.
4. **Mutation compatibility.** Match SQLite conflict handling and primary-key
   update behavior; add supported ON CONFLICT and RETURNING forms. Define failed
   statement behavior for storage errors and add savepoints.
5. **Planning and resource use.** Add indexes and index-aware access paths,
   explainable query plans, execution budgets, and streaming where possible.
   Simple unordered LIMIT queries stop early; sorts, groups, DISTINCT, and
   mutations currently materialize data in memory.
6. **Reliability.** Extend property-based/differential coverage, fault injection,
   large-dataset checks, and recovery tests. Define session/concurrency guarantees
   before exposing multiple connections; the current engine owns one serialized
   session. Verify dependency licensing before distributing the bundled engine.

Completion gate: the advertised dialect works end to end, remaining intentional
differences are documented, regression and SQLite comparison suites pass, and
storage/transaction guarantees match observed recovery behavior. Engine features
must not be postponed merely to get an AI demo running.

SQLite is the behavioral reference, not a replacement execution backend. Compare
semantically equivalent queries on the shared syntax, with deterministic ORDER BY
where ordering matters. Do not compare SQL text or unordered row traversal.

## 2. Build the database adapter

Build on `DBMS.execute(String)` and `QueryResult`; keep CLI formatting out of the
integration. Add schema discovery, capability discovery, parameter binding, and
bounded read execution. Start with one local interface. Add HTTP or MCP transport
when there is a consumer.

Validate statements and identifiers through the parser/catalog. Enforce caller,
table, and operation permissions in application code. Keep one engine owner until
separate sessions and transaction isolation are implemented.

Extend result envelopes with logical column types, query/result IDs, executed
SQL, completeness, effective limits, timing, and snapshot/freshness information.
Store authorized immutable result artifacts for follow-up questions.

Gate: integration tests cover success, SQL errors, access restrictions, nulls,
truncation, execution limits, and database-directory isolation without a model.

## 3. Add SQL question answering

Create a SQL analysis skill against the actual adapter contracts. Supply live
schema/capability metadata and a maintained data dictionary: units, metric
definitions, grouping grain, relationships, and known limitations.

The workflow is question -> schema/context -> SQL -> validated tool execution ->
result inspection -> evidence-based answer. Preserve the question, SQL, result
ID, filters, and assumptions for follow-up requests. Requery when the scope or
freshness requirement changes. Never invent database output.

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
SQL and visualization skills; introduce separate agent processes when measured
quality or workflow isolation warrants them.
