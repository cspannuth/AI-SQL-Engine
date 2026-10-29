"""Deterministic metadata selection and a small, user-maintained data dictionary."""
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re

from .client import json_text


class ContextError(ValueError):
    pass


def words(text):
    return set(re.findall(r"\w+", text.casefold()))


def mentions(question, phrase):
    return bool(phrase) and re.search(r"(?<!\w)" + re.escape(phrase.casefold()) + r"(?!\w)", question.casefold()) is not None


class Dictionary:
    def __init__(self, data=None):
        self.data = data or {"version": 1, "tables": {}, "metrics": {}}
        if not isinstance(self.data, dict) or set(self.data) - {"version", "tables", "metrics"} or self.data.get("version") != 1:
            raise ContextError("Dictionary requires version 1, tables, and metrics")
        self.tables = self.data.get("tables", {})
        self.metrics = self.data.get("metrics", {})
        if not isinstance(self.tables, dict) or not isinstance(self.metrics, dict):
            raise ContextError("Dictionary tables and metrics must be objects")
        for name, table in self.tables.items():
            if not isinstance(table, dict) or set(table) - {"description", "aliases", "grain", "columns"}:
                raise ContextError(f"Invalid table dictionary: {name}")
            self._strings(table.get("aliases", []), "Table aliases")
            if any(not isinstance(table[field], str) for field in ["description", "grain"] if field in table):
                raise ContextError("Table descriptions and grain must be strings")
            columns = table.get("columns", {})
            if not isinstance(columns, dict) or not all(isinstance(value, dict) for value in columns.values()):
                raise ContextError("Dictionary columns must map names to metadata objects")
            for column in columns.values():
                if set(column) - {"description", "unit"} or not all(isinstance(value, str) for value in column.values()):
                    raise ContextError("Column context supports description and unit strings")
        for name, metric in self.metrics.items():
            fields = {"description", "expression", "tables", "aliases", "unit", "grain", "timezone"}
            if not isinstance(metric, dict) or set(metric) - fields:
                raise ContextError(f"Invalid metric: {name}")
            for field in ["description", "expression", "unit", "grain"]:
                if not isinstance(metric.get(field), str) or not metric[field].strip():
                    raise ContextError(f"Metric {name} requires {field}")
            self._strings(metric.get("tables"), f"Metric {name} tables", nonempty=True)
            self._strings(metric.get("aliases", []), "Metric aliases")
            if "timezone" in metric and not isinstance(metric["timezone"], str):
                raise ContextError("Metric timezone must be text")

    @staticmethod
    def _strings(value, field, nonempty=False):
        if not isinstance(value, list) or nonempty and not value or not all(isinstance(item, str) and item.strip() for item in value):
            raise ContextError(f"{field} must be a list of nonempty strings")

    @classmethod
    def load(cls, path):
        path = Path(path)
        if path.stat().st_size > 256 * 1024:
            raise ContextError("Dictionary exceeds 256 KiB")
        return cls(json.loads(path.read_text(encoding="utf-8")))

    def available_metrics(self, allowed):
        allowed = {name.casefold() for name in allowed}
        return {name: metric for name, metric in self.metrics.items()
                if all(table.casefold() in allowed for table in metric["tables"])}

    def resolve(self, question, allowed):
        available = self.available_metrics(allowed)
        explicit = [name for name in available if mentions(question, name.replace("_", " "))]
        if explicit:
            return explicit, None
        matches = [name for name, metric in available.items()
                   if any(mentions(question, alias) for alias in metric.get("aliases", []))]
        for alias in {alias for metric in available.values() for alias in metric.get("aliases", [])}:
            candidates = [name for name in matches if alias in available[name].get("aliases", [])]
            if mentions(question, alias) and len(candidates) > 1:
                return [], "Which definition of " + alias + " should I use: " + ", ".join(candidates) + "?"
        return matches, None

    def table_context(self, name):
        return next((value for key, value in self.tables.items() if key.casefold() == name.casefold()), {})


def compact_schema(schema):
    return {
        "table": schema["name"],
        "columns": [{"name": col["name"], "type": col["type"], "nullable": col["nullable"]}
                    for col in schema["columns"]],
        "primary_key": schema["primaryKeyColumns"], "unique_keys": schema["uniqueKeys"],
        "unique_columns": [col["name"] for col in schema["columns"] if col["unique"]],
        "references": [{"columns": fk["columns"], "table": fk["parentTable"], "parent_columns": fk["parentColumns"]}
                       for fk in schema["foreignKeys"]],
        "row_id": {"supported": schema["rowIdTable"], "alias": schema["rowIdColumn"]},
    }


@dataclass
class Selection:
    context: dict
    raw_schemas: dict
    allowed: list
    clarification: str = None


class ContextBuilder:
    def __init__(self, tools, dictionary=None, max_tables=6):
        self.tools = tools
        self.dictionary = dictionary or Dictionary()
        self.max_tables = max_tables
        self.cache = {}
        self.cache_hits = 0
        self.capabilities = None

    def _schema(self, name):
        # Refresh metadata every turn. A fingerprint validates the formatting cache;
        # it is never advertised as a database-data snapshot or freshness version.
        raw = self.tools.describe_table(name)
        fingerprint = hashlib.sha256(json_text(raw).encode("utf-8")).hexdigest()
        old = self.cache.get(name)
        if old and old[0] == fingerprint:
            self.cache_hits += 1
            compact = old[1]
        else:
            compact = compact_schema(raw)
            table_context = self.dictionary.table_context(name)
            columns = {column["name"].casefold() for column in raw["columns"]}
            if any(column.casefold() not in columns for column in table_context.get("columns", {})):
                raise ContextError(f"Dictionary refers to unknown columns in {name}")
            if table_context:
                compact["meaning"] = table_context
            self.cache[name] = fingerprint, compact
        return raw, compact, fingerprint

    def build(self, question, previous=None, extra_tables=(), baseline=False):
        allowed = self.tools.list_tables()
        canonical = {name.casefold(): name for name in allowed}
        metric_ids, clarification = self.dictionary.resolve(question, allowed)
        if clarification:
            return Selection({}, {}, allowed, clarification)
        if not metric_ids and previous:
            available = self.dictionary.available_metrics(allowed)
            metric_ids = [name for name in previous.get("metrics", []) if name in available]
        scores = {}
        for name in allowed:
            info = self.dictionary.table_context(name)
            score = 10 * int(mentions(question, name))
            score += sum(5 for alias in info.get("aliases", []) if mentions(question, alias))
            score += len(words(question) & words(info.get("description", "")))
            scores[name] = score
        selected = []
        required = [table for metric in metric_ids for table in self.dictionary.metrics[metric]["tables"]]
        required += list(extra_tables)
        if previous:
            required += previous.get("tables", [])
        for table in required:
            if table.casefold() not in canonical:
                raise ContextError("Requested schema is outside the caller's available tables")
            actual = canonical[table.casefold()]
            if actual not in selected:
                selected.append(actual)
        if baseline:
            selected = list(allowed)
        else:
            candidates = [name for name in sorted(allowed, key=lambda name: (-scores[name], name))
                          if scores[name] > 0 and name not in selected]
            selected += candidates[:max(0, self.max_tables - len(selected))]
            if not selected and len(allowed) <= self.max_tables:
                selected = list(allowed)
        if not selected and allowed:
            return Selection({}, {}, allowed, "Which table should I use? Available tables: " + ", ".join(allowed[:30]))
        if len(selected) > self.max_tables:
            return Selection({}, {}, allowed, "Please narrow the question to at most " + str(self.max_tables) + " tables.")
        raw_schemas, schemas, fingerprints = {}, [], {}
        for name in selected:
            raw, compact, fingerprint = self._schema(name)
            raw_schemas[name], fingerprints[name] = raw, fingerprint
            schemas.append(compact)
        # Add authorized direct FK parents, bounded by the same table budget.
        for raw in list(raw_schemas.values()):
            for fk in raw["foreignKeys"]:
                parent = canonical.get(fk["parentTable"].casefold())
                if parent and parent not in raw_schemas and len(schemas) < self.max_tables:
                    other, compact, fingerprint = self._schema(parent)
                    raw_schemas[parent], fingerprints[parent] = other, fingerprint
                    schemas.append(compact)
        if self.capabilities is None:
            self.capabilities = self.tools.get_capabilities()["database"]
        capabilities = self.capabilities
        metrics = {name: self.dictionary.metrics[name] for name in metric_ids}
        context = {"question": question, "schema": list(raw_schemas.values()) if baseline else schemas,
                   "metrics": metrics, "schema_fingerprints": fingerprints,
                   "available_tables": allowed[:100], "table_names_truncated": len(allowed) > 100,
                   "dialect": {"name": capabilities["dialect"], "features": capabilities["features"],
                               "limitations": capabilities["limitations"]}}
        if previous:
            context["previous"] = previous
        return Selection(context, raw_schemas, allowed)
