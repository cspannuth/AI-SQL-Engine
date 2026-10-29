"""The local UI owns one Java session; only explicit console calls can write."""
from collections import OrderedDict, deque
from contextlib import contextmanager
import os
from pathlib import Path
import re
import threading
import time
from urllib.parse import urlsplit

from .client import DatabaseClient, DatabaseError, LIMIT_FLAGS, ROOT, json_text
from .settings import make_workflow
from .workflow import engine_sql


class WorkspaceClient(DatabaseClient):
    def __init__(self, home, *, classpath=None, limits=None, timeout=30, caller="local-workspace"):
        classes = ROOT / ".build" / "classes"
        if classpath is None and not (classes / "DatabaseClient.class").exists():
            raise DatabaseError("BUILD_REQUIRED", "Run python scripts/build.py first")
        classpath = classpath or str(classes) + os.pathsep + str(ROOT / "dbms" / "lib" / "*")
        command = ["java", "-cp", classpath, "DatabaseClient", str(Path(home).resolve()),
                   "--json", "--workspace", "--caller", caller]
        for name, value in (limits or {}).items():
            if name not in LIMIT_FLAGS or type(value) is not int or value < 1:
                raise ValueError(f"Invalid database limit: {name}")
            command += [LIMIT_FLAGS[name], str(value)]
        super().__init__(home, command=command, timeout=timeout)

    def execute_sql(self, sql, parameters=None):
        return self.request("execute_sql", sql=engine_sql(sql), parameters=parameters or [])

    def status(self):
        return self.request("workspace_status")


class Workspace:
    """One local operator session, with bounded independent AI conversation contexts."""
    def __init__(self, home, config, *, client=None, workflow_factory=None):
        database = dict(config.get("database", {}))
        if database.get("allowed_tables") is not None or database.get("schema_only"):
            raise ValueError("The full workspace requires local owner access; use ask/tools for a restricted session")
        database.pop("allowed_tables", None)
        database.pop("schema_only", None)
        self.client = client or WorkspaceClient(home, **database)
        self.config = config
        self.database = Path(home).resolve().name
        self._factory = workflow_factory or (lambda: make_workflow(
            self.client, config, model_name=os.environ.get("SQL_AGENT_MODEL"), base_url=os.environ.get("SQL_AGENT_BASE_URL")))
        self._lock = threading.Lock()
        self._state_lock = threading.Lock()
        self._workflows = OrderedDict()
        self._history = deque(maxlen=50)
        self.transaction_active = False
        self.connected = True
        self.busy = False
        try:
            self.capabilities = self.client.get_capabilities()
            self.model_info = self._model_info()
        except Exception:
            self.client.close()
            raise

    def _model_info(self):
        try:
            workflow = self._factory()  # Configuration validation only; never calls the model.
            model = workflow.model
            ready = not model.hosted or bool(os.environ.get(model.key_env, ""))
            return {"name": model.model, "answerModel": model.answer_model,
                    "provider": urlsplit(model.base_url).hostname, "ready": ready,
                    "note": "Ready for questions" if ready else "Add the API key to .env and restart the UI"}
        except (OSError, TypeError, ValueError) as error:
            return {"name": None, "provider": None, "ready": False, "note": str(error)}

    @contextmanager
    def _operation(self):
        if not self._lock.acquire(blocking=False):
            raise DatabaseError("BUSY", "Another workspace request is running")
        self.busy = True
        try:
            yield
        finally:
            try:
                self.transaction_active = self.client.status()["transactionActive"]
            except DatabaseError:
                self.connected = False
            self.busy = False
            self._lock.release()

    def status(self):
        return {"database": self.database, "connected": self.connected, "busy": self.busy,
                "transactionActive": self.transaction_active, "model": self.model_info,
                "limits": self.capabilities["database"]["maximumLimits"],
                "dictionary": Path(self.config["dictionary"]).name if self.config.get("dictionary") else None}

    def history(self):
        with self._state_lock:
            return list(self._history)

    def _record(self, kind, text, started, *, result=None, error=None, status=None):
        parameters = json_text((result or {}).get("parameters", []))
        record = {"kind": kind, "text": text[:4000], "time": time.time(),
                  "textTruncated": len(text) > 4000 or len(parameters) > 4000,
                  "parametersJson": parameters if len(parameters) <= 4000 else None,
                  "elapsedMillis": round((time.perf_counter() - started) * 1000, 2),
                  "status": status or ("error" if error else "complete" if not result or result.get("complete", True) else "incomplete"),
                  "returnedRows": (result or {}).get("returnedRows"), "affectedRows": (result or {}).get("affectedRows"),
                  "error": str(error) if error else None}
        with self._state_lock:
            self._history.appendleft(record)

    def tables(self):
        with self._operation():
            return self.client.list_tables()

    def schema(self, table):
        with self._operation():
            return self.client.describe_table(table)

    def query(self, sql, parameters=None, *, explain=False):
        with self._operation():
            started = time.perf_counter()
            try:
                if not isinstance(sql, str) or not sql.strip():
                    raise ValueError("Enter a SQL statement")
                result = self.client.query_readonly("EXPLAIN QUERY PLAN " + engine_sql(sql), parameters) if explain else self.client.execute_sql(sql, parameters)
                if not explain and not result.get("readOnly", False):
                    self._workflows.clear()
                self._record("plan" if explain else "sql", sql, started, result=result)
                return result
            except (ValueError, DatabaseError) as error:
                self._record("plan" if explain else "sql", sql if isinstance(sql, str) else "", started, error=error)
                raise

    def preview(self, table):
        with self._operation():
            self.client.describe_table(table)  # Resolve the real catalog name before constructing an identifier.
            quoted = '"' + table.replace('"', '""') + '"'
            return self.client.query_readonly(f"SELECT * FROM {quoted} LIMIT 50;")

    def ask(self, question, conversation, *, answer="auto"):
        if not isinstance(conversation, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", conversation):
            raise ValueError("Invalid conversation ID")
        with self._operation():
            if self.transaction_active:
                raise DatabaseError("TRANSACTION_ACTIVE", "Commit or roll back the console transaction before asking AI")
            if not self.model_info["ready"]:
                raise ValueError(self.model_info["note"])
            if conversation not in self._workflows:
                self._workflows[conversation] = self._factory()
            self._workflows.move_to_end(conversation)
            while len(self._workflows) > 8:
                self._workflows.popitem(last=False)
            started = time.perf_counter()
            output = self._workflows[conversation].ask(question, explain=answer, chart=True)
            self._record("question", question, started, result=output.get("result"), error=output.get("error", {}).get("message"), status=output["status"])
            return output

    def clear_conversation(self, conversation):
        with self._operation():
            self._workflows.pop(conversation, None)
        return {"cleared": True}

    def close(self):
        with self._lock:
            self.client.close()
            self.connected = False

    def __enter__(self): return self
    def __exit__(self, *_): self.close()
