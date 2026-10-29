"""Bounded role orchestration. SQL drafting never receives database result rows."""
from dataclasses import dataclass
from datetime import datetime, timezone
import re
import time

from .client import DatabaseError, SchemaTools, json_text
from .context import ContextBuilder, ContextError
from .models import ModelError


PROPOSAL_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "properties": {
        "action": {"type": "string", "enum": ["query", "clarify", "schema"]},
        "sql": {"type": ["string", "null"]},
        "parameters": {"type": "array", "items": {"type": ["string", "number", "boolean", "null"]}},
        "tables": {"type": "array", "items": {"type": "string"}},
        "metrics": {"type": "array", "items": {"type": "string"},
                    "description": "IDs of supplied metric definitions used by the SQL. Use [] when no metric definitions were supplied; SQL aliases and COUNT are not metric IDs."},
        "assumptions": {"type": "array", "items": {"type": "string"}},
        "clarification": {"type": ["string", "null"]},
    },
    "required": ["action", "sql", "parameters", "tables", "metrics", "assumptions", "clarification"],
}
ANSWER_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "properties": {"template": {"type": "string"}}, "required": ["template"],
}
CELL = re.compile(r"\{\{(\d+):(\d+)\}\}")


@dataclass(frozen=True)
class WorkflowLimits:
    max_model_calls: int = 6
    sql_repairs: int = 2
    schema_requests: int = 2
    max_tables: int = 6
    context_bytes: int = 24000
    answer_bytes: int = 12000
    question_bytes: int = 4000

    def __post_init__(self):
        if any(type(value) is not int or value < 1 for key, value in vars(self).items()
               if key not in {"sql_repairs", "schema_requests"}):
            raise ValueError("Workflow limits must be positive integers")
        if any(type(value) is not int or value < 0 for value in [self.sql_repairs, self.schema_requests]):
            raise ValueError("Repair limits cannot be negative")


def proposal(value):
    if not isinstance(value, dict) or set(value) != set(PROPOSAL_SCHEMA["required"]):
        raise ModelError("Query proposal has missing or unknown fields")
    if value["action"] not in {"query", "clarify", "schema"}:
        raise ModelError("Unknown proposal action")
    for name in ["tables", "metrics", "assumptions"]:
        if not isinstance(value[name], list) or len(value[name]) > 30 or not all(
                isinstance(item, str) and 0 < len(item) <= 1000 for item in value[name]):
            raise ModelError(f"Invalid proposal {name}")
    if not isinstance(value["parameters"], list) or len(value["parameters"]) > 256:
        raise ModelError("Invalid bound parameters")
    for item in value["parameters"]:
        if item is not None and type(item) not in {str, bool, int, float}:
            raise ModelError("Bound values must be JSON scalars")
    try:
        json_text(value)
    except ValueError as error:
        raise ModelError("Proposal contains a non-finite number") from error
    action = value["action"]
    if action == "query" and (not isinstance(value["sql"], str) or not value["sql"].strip()
                              or value["clarification"] is not None):
        raise ModelError("Query action requires SQL and no clarification")
    if action == "clarify" and (not isinstance(value["clarification"], str) or not value["clarification"].strip()
                                or len(value["clarification"]) > 2000):
        raise ModelError("Clarification action requires a bounded question")
    if action == "schema" and not value["tables"]:
        raise ModelError("Schema action requires table names")
    if action != "query" and (value["sql"] is not None or value["parameters"]):
        raise ModelError("Metadata/clarification actions cannot carry executable SQL")
    return value


def explain_template(value, result):
    """Numbers and cited cell values are inserted by code, never copied from model prose."""
    if not isinstance(value, dict) or set(value) != {"template"} or not isinstance(value["template"], str):
        raise ModelError("Answer role must return a template")
    template = value["template"]
    if not 0 < len(template) <= 2000 or not CELL.search(template):
        raise ModelError("Answer must cite at least one observed result cell")
    remaining = CELL.sub("", template)
    if re.search(r"\d|\{\{|\}\}", remaining):
        raise ModelError("Answer figures must use result-cell placeholders")
    evidence = []

    def replace(match):
        row, column = map(int, match.groups())
        if row >= len(result["rows"]) or column >= len(result["columns"]):
            raise ModelError("Answer references a nonexistent result cell")
        evidence.append({"row": row, "column": column, "value": result["rows"][row][column]})
        return json_text(result["rows"][row][column])

    return CELL.sub(replace, template), evidence


def direct_answer(result):
    if not result["complete"]:
        return f"Returned {result['returnedRows']} rows; this is an incomplete preview ({result['truncationReason']})."
    if not result["rows"]:
        return "No matching rows were found."
    if len(result["rows"]) == 1 and len(result["columns"]) == 1:
        return result["columns"][0]["name"] + ": " + json_text(result["rows"][0][0])
    return f"Returned {result['returnedRows']} rows. Values are shown in the result table."


def engine_sql(sql):
    """The Java parser requires a terminator, even for a single read query."""
    sql = sql.rstrip()
    tail = sql
    while not tail.endswith(";"):
        # Ignore trailing comments when checking whether SQL is already complete.
        if tail.endswith("*/") and "/*" in tail:
            tail = tail[:tail.rfind("/*")].rstrip()
            continue
        line_start = tail.rfind("\n") + 1
        comment = tail.rfind("--", line_start)
        if comment < 0:
            break
        tail = tail[:comment].rstrip()
    if tail.endswith(";"):
        return sql
    # Put the terminator after a final line comment rather than inside it.
    return sql + ("\n;" if "--" in sql.rsplit("\n", 1)[-1] else ";")


class Workflow:
    def __init__(self, client, model, dictionary, draft_instructions, answer_instructions, *, limits=None, baseline=False):
        self.client, self.model = client, model
        self.dictionary = dictionary
        self.limits = limits or WorkflowLimits()
        self.builder = ContextBuilder(SchemaTools(client), dictionary, self.limits.max_tables)
        self.draft_instructions, self.answer_instructions = draft_instructions, answer_instructions
        self.baseline = baseline
        self.previous = None
        self.previous_result = None
        self.pending_question = None

    def _call(self, role, instructions, context, schema, calls):
        if len(calls) >= self.limits.max_model_calls:
            raise ModelError("Question exceeded its model-call budget")
        encoded = json_text(context).encode("utf-8")
        ceiling = self.limits.answer_bytes if role == "answer" else self.limits.context_bytes
        if len(encoded) > ceiling:
            raise ContextError("Selected model context exceeds its byte budget; narrow the question or tables")
        record = {"role": role, "context_bytes": len(encoded), "input_tokens": None, "output_tokens": None}
        calls.append(record)
        started = time.perf_counter()
        try:
            reply = self.model.complete(role, instructions, context, schema)
            record.update(reply.usage)
            return reply.data
        finally:
            record.setdefault("latency_ms", round((time.perf_counter() - started) * 1000, 2))

    def ask(self, question, *, explain="auto", chart=False):
        started = time.perf_counter()
        calls, attempts = [], []
        output = {"question": question, "status": "error", "answer": None, "result": None,
                  "assumptions": [], "attempts": attempts, "model_calls": calls, "visualization": None}
        if explain not in {"auto", "direct", "model"}:
            raise ValueError("Answer mode must be auto, direct, or model")
        if not isinstance(question, str) or not question.strip() or len(question.encode("utf-8")) > self.limits.question_bytes:
            raise ValueError("Question is empty or exceeds its byte limit")
        effective_question = question
        if self.pending_question:
            effective_question = self.pending_question + "\nUser clarification: " + question
        try:
            selection = self.builder.build(effective_question, self.previous, baseline=self.baseline)
            if selection.clarification:
                self.pending_question = effective_question[:2000]
                output.update(status="clarification", answer=selection.clarification)
                return output
            self.pending_question = None
            repairs = schema_requests = 0
            feedback, extra_tables = None, []
            seen_queries = set()
            while True:
                context = dict(selection.context)
                context["now_utc"] = datetime.now(timezone.utc).isoformat()
                if feedback:
                    context["feedback"] = feedback
                if self.baseline and self.previous_result:
                    context["previous_result"] = self.previous_result
                raw = self._call("sql", self.draft_instructions, context, PROPOSAL_SCHEMA, calls)
                try:
                    plan = proposal(raw)
                except ModelError as error:
                    if repairs >= self.limits.sql_repairs:
                        raise
                    repairs += 1
                    feedback = {"code": "INVALID_PROPOSAL", "message": str(error)}
                    continue
                output["assumptions"] = plan["assumptions"]
                if plan["action"] == "clarify":
                    self.pending_question = effective_question[:2000]
                    output.update(status="clarification", answer=plan["clarification"])
                    return output
                if plan["action"] == "schema":
                    if schema_requests >= self.limits.schema_requests:
                        raise ModelError("Question exceeded its schema-expansion budget")
                    schema_requests += 1
                    extra_tables = list(dict.fromkeys(extra_tables + plan["tables"]))
                    selection = self.builder.build(effective_question, self.previous, extra_tables, self.baseline)
                    if selection.clarification:
                        self.pending_question = effective_question[:2000]
                        output.update(status="clarification", answer=selection.clarification)
                        return output
                    feedback = None
                    continue
                available_metrics = selection.context["metrics"]
                # An undeclared label cannot attach a business definition. Only
                # selected dictionary metrics are meaningful to this workflow.
                if available_metrics and any(name not in available_metrics for name in plan["metrics"]):
                    raise ContextError("Proposal uses a metric without a selected definition")
                if available_metrics and set(plan["metrics"]) != set(available_metrics):
                    raise ContextError("Proposal omitted a metric definition selected for this question")
                sql = engine_sql(plan["sql"])
                signature = json_text([sql, plan["parameters"]])
                if signature in seen_queries:
                    raise ModelError("Repeated SQL proposal stopped instead of repeating execution")
                seen_queries.add(signature)
                attempt = {"sql": sql, "parameters": plan["parameters"]}
                attempts.append(attempt)
                try:
                    result = self.client.query_readonly(sql, plan["parameters"])
                except DatabaseError as error:
                    attempt["error"] = {"code": error.code, "message": str(error), "limit": error.limit}
                    if error.code not in {"SQL_ERROR", "LIMIT_EXCEEDED"} or repairs >= self.limits.sql_repairs:
                        raise
                    repairs += 1
                    feedback = {"sql": sql, "parameters": plan["parameters"], **attempt["error"]}
                    continue
                attempt["result_id"] = result["resultId"]
                output["result"] = result
                if result["complete"] or repairs >= self.limits.sql_repairs:
                    break
                repairs += 1
                # The SQL role sees execution metadata, not preview rows. Avoid repeating
                # a population calculation from an incomplete returned sample.
                feedback = {"code": "INCOMPLETE_RESULT", "sql": sql, "parameters": plan["parameters"],
                            "returned_rows": result["returnedRows"], "reason": result["truncationReason"],
                            "message": "Use an appropriate aggregate or smaller authorized scope; do not raise ceilings."}
            output.update(status="complete" if result["complete"] else "incomplete", result=result, answer=direct_answer(result))
            evidence = []
            needs_answer = explain == "model" or explain == "auto" and len(result["rows"]) > 8
            if needs_answer and result["complete"] and result["rows"]:
                answer_context = {"question": effective_question, "sql": result["sql"], "parameters": result["parameters"],
                                  "result_id": result["resultId"], "columns": result["columns"], "rows": result["rows"],
                                  "complete": result["complete"], "metrics": selection.context["metrics"],
                                  "assumptions": plan["assumptions"]}
                if self.baseline:
                    answer_context["draft_context"] = context
                try:
                    value = self._call("answer", self.answer_instructions, answer_context, ANSWER_SCHEMA, calls)
                    output["answer"], evidence = explain_template(value, result)
                except (ModelError, ContextError) as error:
                    output["answer_note"] = "Explanation unavailable; showing deterministic results. " + str(error)
            output["evidence"] = evidence
            output["metrics"] = selection.context["metrics"]
            self.previous = {"question": effective_question[:600], "tables": list(selection.raw_schemas),
                             "metrics": list(selection.context["metrics"]), "result_id": result["resultId"]}
            query_context = {"sql": result["sql"], "parameters": result["parameters"]}
            if len(json_text(query_context).encode("utf-8")) <= 1800:
                self.previous.update(query_context)
            for assumption in plan["assumptions"]:
                candidate = {**self.previous, "assumptions": self.previous.get("assumptions", []) + [assumption]}
                if len(json_text(candidate).encode("utf-8")) <= 3000:
                    self.previous = candidate
            self.previous_result = result if self.baseline else None
            if chart:
                output["visualization"] = {
                    "version": 1, "status": "ready" if result["complete"] else "incomplete",
                    "question": effective_question, "result_id": result["resultId"], "database_id": result["databaseId"],
                    "sql": result["sql"], "parameters": result["parameters"], "columns": result["columns"],
                    "complete": result["complete"], "returned_rows": result["returnedRows"],
                    "truncation_reason": result["truncationReason"], "captured_at": result["capturedAt"],
                    "expires_at": result["expiresAt"], "metrics": selection.context["metrics"],
                    "assumptions": plan["assumptions"], "schema_fingerprints": selection.context["schema_fingerprints"],
                }
            return output
        except (DatabaseError, ModelError, ContextError) as error:
            output["status"] = "error"
            code = error.code if isinstance(error, DatabaseError) else "MODEL_ERROR" if isinstance(error, ModelError) else "CONTEXT_ERROR"
            output["error"] = {"code": code, "message": str(error)}
            output["answer"] = str(error)
            return output
        finally:
            output["elapsed_ms"] = round((time.perf_counter() - started) * 1000, 2)
            output["usage"] = {"calls": len(calls), "context_bytes": sum(call["context_bytes"] for call in calls),
                               "input_tokens": self._total(calls, "input_tokens"),
                               "output_tokens": self._total(calls, "output_tokens")}

    @staticmethod
    def _total(calls, name):
        return sum(call[name] for call in calls) if all(type(call[name]) is int for call in calls) else None
