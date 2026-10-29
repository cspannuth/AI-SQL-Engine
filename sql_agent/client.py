"""One serialized owner of a persistent DatabaseClient JSON Lines process."""
from collections import deque
import json
import os
from pathlib import Path
import queue
import subprocess
import threading


ROOT = Path(__file__).resolve().parents[1]
MAX_RESPONSE_BYTES = 16 * 1024 * 1024
LIMIT_FLAGS = {
    "maxRows": "--max-rows", "maxResultBytes": "--max-bytes",
    "maxScannedRows": "--max-scanned-rows", "maxWorkingRows": "--max-working-rows",
    "maxWorkingBytes": "--max-working-bytes", "timeoutMillis": "--timeout-ms",
}


class DatabaseError(RuntimeError):
    def __init__(self, code, message, *, limit=None, request_id=None):
        super().__init__(message)
        self.code, self.limit, self.request_id = code, limit, request_id


def json_text(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


class DatabaseClient:
    def __init__(self, home, *, classpath=None, allowed_tables=None, schema_only=False,
                 limits=None, timeout=15, caller="sql-analysis", command=None):
        if timeout <= 0:
            raise ValueError("Process timeout must be positive")
        self.timeout = timeout
        self._lock = threading.RLock()
        self._closed = False
        self._next_id = 0
        self._responses = queue.Queue()
        self._diagnostics = deque(maxlen=16)
        self.calls = []
        if command is None:
            classes = ROOT / ".build" / "classes"
            if classpath is None and not (classes / "DatabaseClient.class").exists():
                raise DatabaseError("BUILD_REQUIRED", "Run python scripts/build.py first")
            classpath = classpath or str(classes) + os.pathsep + str(ROOT / "dbms" / "lib" / "*")
            command = ["java", "-cp", classpath, "DatabaseClient", str(Path(home).resolve()), "--json", "--caller", caller]
            if schema_only:
                command.append("--schema-only")
            if allowed_tables is not None:
                if not allowed_tables:
                    raise ValueError("Supply at least one allowed table, or omit the table policy")
                for table in allowed_tables:
                    command += ["--allow-table", table]
            for name, value in (limits or {}).items():
                if name not in LIMIT_FLAGS or type(value) is not int or value < 1:
                    raise ValueError(f"Invalid database limit: {name}")
                command += [LIMIT_FLAGS[name], str(value)]
        self._process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self._reader = threading.Thread(target=self._read, daemon=True)
        self._errors = threading.Thread(target=self._read_errors, daemon=True)
        self._reader.start()
        self._errors.start()

    def _read(self):
        try:
            while True:
                line = self._process.stdout.readline(MAX_RESPONSE_BYTES + 1)
                if not line:
                    break
                if len(line) > MAX_RESPONSE_BYTES:
                    raise ValueError("Database response exceeds the process client's byte limit")
                self._responses.put(json.loads(line.decode("utf-8")))
        except (ValueError, OSError) as error:
            self._responses.put(error)
        finally:
            self._responses.put(None)

    def _read_errors(self):
        for line in iter(lambda: self._process.stderr.readline(4096), b""):
            self._diagnostics.append(line.decode("utf-8", errors="replace"))

    def request(self, operation, **fields):
        with self._lock:
            if self._closed:
                raise DatabaseError("CLOSED", "Database process is closed")
            self._next_id += 1
            request = {"id": self._next_id, "operation": operation, **fields}
            encoded = (json_text(request) + "\n").encode("utf-8")
            if len(encoded) > 256 * 1024:
                raise DatabaseError("INVALID_REQUEST", "Request exceeds the JSON Lines byte limit")
            self.calls.append(operation)
            try:
                self._process.stdin.write(encoded)
                self._process.stdin.flush()
                response = self._responses.get(timeout=self.timeout)
            except (OSError, queue.Empty) as error:
                self._stop()
                raise DatabaseError("TRANSPORT_ERROR", "Database process failed or timed out; request was not retried") from error
            if response is None or isinstance(response, Exception):
                self._stop()
                raise DatabaseError("TRANSPORT_ERROR", "Database process ended or returned invalid JSON")
            if not isinstance(response, dict) or response.get("id") != request["id"]:
                # Startup errors have a null ID and still carry a useful structured code.
                if isinstance(response, dict) and response.get("ok") is False and response.get("id") is None:
                    self._stop()
                    self._raise(response)
                self._stop()
                raise DatabaseError("TRANSPORT_ERROR", "Database response ID does not match its request")
            if response.get("ok") is False:
                self._raise(response)
            if response.get("ok") is not True or response.get("operation") != operation or "data" not in response:
                self._stop()
                raise DatabaseError("TRANSPORT_ERROR", "Malformed database response")
            return response["data"]

    @staticmethod
    def _raise(response):
        error = response.get("error", {})
        raise DatabaseError(error.get("code", "DATABASE_ERROR"), error.get("message", "Database request failed"),
                            limit=error.get("limit"), request_id=error.get("requestId"))

    def list_tables(self): return self.request("list_tables")
    def describe_table(self, table): return self.request("describe_table", table=table)
    def get_capabilities(self): return self.request("get_capabilities")
    def get_result(self, result_id): return self.request("get_result", resultId=result_id)

    def query_readonly(self, sql, parameters=None, limits=None):
        fields = {"sql": sql, "parameters": [] if parameters is None else parameters}
        if limits is not None:
            fields["limits"] = limits
        return self.request("query_readonly", **fields)

    def _stop(self):
        self._closed = True
        if self._process.poll() is None:
            self._process.kill()
        self._process.wait(timeout=5)

    def close(self):
        with self._lock:
            if not self._closed:
                try:
                    self.request("close")
                    self._process.wait(timeout=5)
                except (DatabaseError, subprocess.TimeoutExpired):
                    self._stop()
                self._closed = True
            self._reader.join(timeout=2)
            self._errors.join(timeout=2)
            for pipe in [self._process.stdin, self._process.stdout, self._process.stderr]:
                pipe.close()

    def __enter__(self): return self
    def __exit__(self, *_): self.close()


class SchemaTools:
    """The drafting role receives this interface, which has no row-reading operation."""
    def __init__(self, client): self._client = client
    def list_tables(self): return self._client.list_tables()
    def describe_table(self, table): return self._client.describe_table(table)
    def get_capabilities(self): return self._client.get_capabilities()
