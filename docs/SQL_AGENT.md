# SQL question answering

The Python application connects separate SQL-drafting and result-explanation
contexts to the existing read-only Java adapter. It uses Python's standard library;
no agent framework or model SDK is required. SQL execution remains local and
deterministic. Visualization handoffs are implemented; chart rendering comes later.

## Hosted API setup

From the repository root:

```powershell
python scripts/build.py
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
python -m sql_agent ask --db ".local-db" --config config/agent.example.json
```

Edit `.env` and set `OPENAI_API_KEY` to your API key before running the
last command. The application loads the repository-root `.env` when starting `ask`;
`--env-file <path>` selects another file. Existing shell environment variables take
precedence. The loader accepts plain `KEY=value` lines, optional `export`, quoted
values, and full-line comments. It does not execute commands or expand variables.
Keep the actual key out of source/config files. `.env` is ignored by Git, while
`.env.example` is safe to share. The example selects `gpt-6-luna`, structured
JSON output, bounded output tokens, and no reasoning effort. The model is
configurable, including an optional separate `answer_model`. Account availability
and API compatibility must be checked against the chosen provider.

The database directory must already contain an initialized engine catalog. Use the
same folder as the SQL CLI and close other owning processes first. This application
does not seed, alter, or write to your tables. Ask questions about your actual schema.
For a one-shot request, append a question; `--json` returns the structured envelope:

```powershell
python -m sql_agent ask --db ".local-db" --config config/agent.example.json --json "How many products are there?"
```

The example question requires an actual products table. Missing or ambiguous
business definitions can produce a clarification instead of SQL. `--model` or
`SQL_AGENT_MODEL` and `--base-url` or `SQL_AGENT_BASE_URL` override those settings.
Use `--allow-table products` repeatedly to configure a fixed table policy. The
default is local owner read access, not an authenticated multi-user service.

Interactive commands: `.tables`, `.schema <table>`, `.capabilities`, `.result <id>`,
`.clear`, and `.quit`/`q`. Clear context before starting an unrelated question if
prior metric definitions should no longer apply. Follow-ups execute fresh queries;
the drafting role never receives earlier result rows. Small prior SQL/parameters
and assumptions are retained; oversized query context is omitted as a whole.

## Roles and execution

1. Ordinary Python code selects authorized metadata using table names, dictionary
   aliases, metric tables, and bounded foreign-key parents.
2. Selected schemas are refreshed every turn. Fingerprints validate the compact
   formatting cache; they do not describe database-data freshness.
3. The SQL role receives the question, compact schema, selected definitions, dialect
   capabilities, UTC time, and bounded follow-up context. It receives no table sample.
4. It returns one structured `query`, `schema`, or `clarify` proposal. A schema
   request supplies additional authorized metadata without reading rows.
5. Application code submits SQL and positional scalar parameters to the adapter.
   Java's parser/catalog and fixed policy enforce syntax, identifiers, read-only
   operations, actual nested table access, and execution budgets. The application
   adds a final semicolon to a generated query when the model omits it; the
   executed SQL in the result records that terminator.
6. Simple results are formatted directly. Optional explanation uses a separate
   model call with observed result cells and relevant context, without drafting
   schema. Empty/incomplete results use deterministic messages.

The application loads the bodies of
[sql-analysis](../.agents/skills/sql-analysis/SKILL.md) and
[result-explanation](../.agents/skills/result-explanation/SKILL.md). Skills do not
create processes or grant access. Separate role contexts run inside one Python
application, with one serialized Java database owner.

Proposals contain `action`, `sql`, `parameters`, `tables`, `metrics`, `assumptions`,
and `clarification`. Declared table names never replace authorization of the actual
parsed SQL. `metrics` refers only to selected dictionary definitions; a count or
SQL output alias needs no metric definition. Unselected labels are ignored when no
definitions were selected. Selected metric IDs are checked, but IDs alone cannot
prove SQL's business meaning. Grouping, joins, and metric interpretation still
require evaluation.

The answer role returns a template such as `The total is {{0:0}}.` Code validates
coordinates, inserts actual typed cells, and records evidence. Numeric figures
outside placeholders are rejected. This constrains value rendering; qualitative
interpretation still needs review. Instruction-like row text stays data, and the
answer role has no database execution tools.

## Metric dictionary

Use `--dictionary <file>` or a `dictionary` path relative to the configuration file.
`config/dictionary.example.json` illustrates the README's sales schema and is not
automatically applied to other databases.

- `version: 1`; `tables` and `metrics` are objects keyed by names/metric IDs.
- Table context supports `description`, `aliases`, `grain`, and per-column
  `description`/`unit`. Selected column names are checked against live metadata.
- Metrics require `description`, `expression`, `tables`, `unit`, and `grain`;
  `aliases` and `timezone` are optional. Expressions are context, not executable
  templates; generated SQL must preserve their meaning.
- Only definitions whose tables are authorized and available are supplied.
  Shared aliases prompt clarification without a model call. Naming a specific
  metric resolves that ambiguity. Currency and business definitions are not invented.

Adapt definitions to your actual schema before relying on business totals. Local
definitions can live in ignored `config/dictionary.local.json`.

## Limits, errors, and usage

The example caps a question at six model calls, two SQL/proposal repairs, two schema
expansions, and six selected tables. Context JSON is byte-bounded; instructions,
the output schema, and API-envelope overhead are additional. `max_output_tokens`
bounds each response, not a currency amount. Usage includes calls, context bytes,
timings, and provider-reported input/output tokens when available. Missing token
counts remain null; bytes are never presented as token estimates.

Java ceilings are configured once per process. Model proposals have no limit-raising
field. SQL/limit failures can be corrected under unchanged ceilings. Denied access,
transport failures, and model HTTP failures are not automatically retried. Identical
failed SQL is not executed twice. A process timeout closes the owner because the
request outcome is uncertain.

Truncation permits a bounded attempt to obtain an aggregate or smaller scope.
Remaining truncation stays incomplete. Completeness is relative to the submitted
SQL, including its LIMIT; it does not prove the SQL fully answered the question.
Failures never become empty successful answers. Optional explanation failures fall
back to the observed table.

`--answer auto` directly formats small results and explains complete results with
more than eight rows when context permits. `--answer direct` avoids the explanation
call; `--answer model` requests it for complete nonempty results. Oversized answer
contexts fall back rather than silently sampling. The terminal displays at most ten
rows and labels that display limit; JSON/export includes all returned rows.

Hosted requests set `store: false` and do not use server conversation IDs. This is
not a claim about all provider retention policies. Drafting sends schema/context;
explanation sends result rows only when invoked. Keys never enter prompts/output.
Default local-server connections do not inherit an OpenAI API key.

API references: [Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create),
[structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs),
and [current pricing](https://developers.openai.com/api/docs/pricing).

## Local-server compatibility

Hosted access is the initial setup. An already-running compatible local server can
instead use this configuration:

```json
{
  "model": {
    "model": "your-loaded-model",
    "base_url": "http://127.0.0.1:1234/v1",
    "api_key_env": null,
    "response_format": "json_schema",
    "token_limit_field": "max_tokens",
    "max_output_tokens": 900
  }
}
```

If the server only supports JSON object mode, explicitly choose `json_object`;
application validation still applies. Unsupported formats/models are not silently
substituted or downgraded. HTTP is permitted on loopback; remote endpoints require
HTTPS. Model installation/serving remains external setup.

## Executable JSON tools

Another agent host can use the deterministic tool process without model calls:

```powershell
python -m sql_agent tools --db ".local-db" --schema-only --allow-table products
```

Send one UTF-8 JSON object per line:

```json
{"id":"tables","operation":"list_tables"}
{"id":"shape","operation":"describe_table","arguments":{"table":"products"}}
```

Other operations are `get_capabilities`, `query_readonly`, and `get_result`.
Query arguments are `sql`, optional `parameters`, and optional lowered `limits`;
retrieval uses `arguments: {"result_id":"..."}`. Schema-only mode denies both
row-reading operations in Java. Requests cannot change caller/table/session policy.
EOF closes the process. The original Java envelope remains available directly;
see [CLIENT.md](CLIENT.md).

## Artifacts and visualization handoff

`--chart` includes a versioned handoff with question, result/database IDs, executed
SQL/parameters, typed columns, completeness, capture/expiry times, selected metric
definitions/units/grain/time context, assumptions, and schema fingerprints. Its
ready/incomplete status describes result completeness; the future visualization
consumer must verify SQL grouping/filter semantics and resolve missing context.
No chart is rendered in this stage.

Result IDs are private to the owning session and can expire or be evicted. A
one-shot command closes that session. Export explicitly to preserve a complete
envelope and full returned data in a new file:

```powershell
New-Item -ItemType Directory -Force artifacts
python -m sql_agent ask --db ".local-db" --config config/agent.example.json --chart --export artifacts/answer.json "<your question>"
```

The output directory must exist, and existing files are not overwritten. Export
preserves truncation; it does not recover omitted rows. `artifacts/` and local
configuration files are ignored by Git.

## Verification and comparison

```text
python tests/agent_checks.py
python tests/evaluate_agent.py
python tests/run_tests.py
```

The checks compile fresh Java classes and use temporary databases, scripted model
replies, and a localhost HTTP fixture. They cover policies, binding, retry budgets,
clarification, follow-ups, truncation, citations, transport failures, and CLI use.
No paid calls occur, and existing `dbms/db` hashes are checked.

Evaluation compares isolated contexts with a shared-context baseline retaining full
authorized schema in drafting and explanation. Independent cases reset history;
JVM startup is warmed outside timings, and mode order alternates. Both modes use
the same fixtures, policy, model, and answer setting. Scripted replies establish
harness behavior/context-size differences, not real model quality, tokens, or speed.

Explicitly enable live API evaluation on synthetic fixtures when ready:

```powershell
New-Item -ItemType Directory -Force artifacts
python tests/evaluate_agent.py --live --config config/agent.example.json --cases 2 --output artifacts/live-evaluation.json
```

The live evaluation also loads `.env` (or `--env-file <path>`). It uses the fixed
evaluation dictionary and temporary data, leaving user databases
alone. Review explanations as well as automatically compared rows. The shared-context
mode is a baseline, not a general framework benchmark; no live cost/latency advantage
is assumed before measurement.
