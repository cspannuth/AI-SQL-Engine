"""Process-level checks for the terminal and JSON Lines database client."""
import json
from pathlib import Path
import subprocess
import threading


def check_client(classpath, home, workspace, seed):
    statements = [
        "CREATE TABLE sales(id INTEGER PRIMARY KEY,category TEXT DEFAULT 'unassigned',amount REAL CHECK(amount>=0),note TEXT UNIQUE);",
        "CREATE TABLE restricted(id INTEGER PRIMARY KEY,value TEXT);",
        "INSERT INTO restricted VALUES(1,'restricted fixture');",
        "CREATE TABLE emptytable(id INTEGER PRIMARY KEY);",
    ]
    for key in range(1, 31):
        statements.append(f"INSERT INTO sales VALUES({key},'{'A' if key <= 15 else 'B'}',{key}.0,NULL);")
    seed(classpath, home, statements)
    checks = 0

    def check(condition, context):
        nonlocal checks
        checks += 1
        if not condition:
            raise AssertionError(context)

    def invoke(*arguments, input="", directory=home):
        return subprocess.run(["java", "-cp", classpath, "DatabaseClient", str(directory), *map(str, arguments)],
                              input=input, cwd=Path(__file__).resolve().parents[1],
                              capture_output=True, text=True, encoding="utf-8", timeout=30)

    def once(*arguments, success=True, directory=home):
        process = invoke("--json", *arguments, directory=directory)
        check(not process.stderr, process.stderr)
        check((process.returncode == 0) == success, process)
        response = json.loads(process.stdout)
        check(response["ok"] == success, response)
        return response

    data = once("--query", "SELECT id FROM sales ORDER BY id;")["data"]
    check(data["rows"] == [[key] for key in range(1, 31)], data)
    check(data["returnedRows"] == 30 and data["complete"] and not data["truncated"], data)
    check(data["columns"] == [{"name": "id", "type": "INTEGER"}], data)
    values = [None, True, False, -9223372036854775808, 9223372036854775807, 1.25,
              "quotes: \" \\ and newline\n\ttab", "caf\u00e9 \U0001f600", "'; DROP TABLE sales; --"]
    bound = once("--query", "SELECT " + ",".join("?" for _ in values) + ";", "--params", json.dumps(values))["data"]
    expected = [None, 1, 0, *values[3:]]
    check(bound["rows"] == [expected], bound)
    check(bound["parameters"] == expected, bound)
    check(once("--tables")["data"] == ["emptytable", "restricted", "sales"], "Table listing")
    check(once("--schema", "sales")["data"]["columns"][0]["primaryKey"], "Schema key")
    constraints = once("--schema", "sales")["data"]
    check(constraints["columns"][1]["hasDefault"] and constraints["columns"][1]["defaultValue"] == "unassigned",
          "Persisted default in JSON schema")
    check(constraints["columns"][3]["unique"] and constraints["checks"] == ["amount>=0"], "Constraint JSON schema")
    check(once("--schema", "unknown", success=False)["error"]["code"] == "NOT_FOUND", "Missing table")
    check(once("--schema", "restricted", "--allow-table", "sales", success=False)["error"]["code"] == "ACCESS_DENIED", "Schema policy")
    check(once("--tables", "--allow-table", "sales")["data"] == ["sales"], "Filtered discovery")
    denied = once("--query", "SELECT * FROM restricted;", "--allow-table", "sales", success=False)
    check(denied["error"]["code"] == "ACCESS_DENIED", denied)
    check(once("--query", "DELETE FROM sales;", success=False)["error"]["code"] == "ACCESS_DENIED", "Writes rejected")
    preview = once("--query", "SELECT id FROM sales ORDER BY id;", "--max-rows", "3")["data"]
    check(preview["rows"] == [[1], [2], [3]] and preview["truncationReason"] == "ROWS", preview)
    aggregate = once("--query", "SELECT COUNT(*) FROM sales;", "--max-rows", "1")["data"]
    check(aggregate["rows"] == [[30]] and aggregate["complete"], aggregate)
    scan_error = once("--query", "SELECT COUNT(*) FROM sales;", "--max-scanned-rows", "2", success=False)
    check(scan_error["error"]["code"] == "LIMIT_EXCEEDED" and scan_error["error"]["limit"] == "scanned rows", scan_error)
    caps = once("--capabilities", "--schema-only", "--caller", "query-planner")["data"]
    check(caps["schemaOnly"] and "query_readonly" not in caps["operations"] and "get_result" not in caps["operations"], caps)
    check(once("--query", "SELECT 1;", "--schema-only", success=False)["error"]["code"] == "ACCESS_DENIED", "Schema-only mode")
    check(once("--query", "SELECT 1e308*1e308;", success=False)["error"]["code"] == "DATABASE_ERROR", "Nonfinite result is an explicit JSON error")

    for options in [
        ["--max-rows", "0"], ["--timeout-ms", "x"], ["--max-results", "-1"],
        ["--result-ttl-seconds", "999999999"], ["--query", "SELECT 1;", "--tables"],
        ["--params", "[]"], ["--params", "{}", "--query", "SELECT 1;"],
        ["--query", "SELECT 1;", "--json"], ["--unrecognized", "x"],
    ]:
        response = once(*options, success=False)
        check(response["error"]["code"] == "INVALID_REQUEST", response)

    missing = workspace / "missing-client-database"
    check(once("--tables", success=False, directory=missing)["error"]["code"] == "NOT_FOUND", "Missing directory")
    check(not missing.exists(), "Client must not initialize a missing database")
    empty = workspace / "empty-client-directory"
    empty.mkdir()
    once("--tables", success=False, directory=empty)
    check(not list(empty.iterdir()), "Client must not initialize an empty directory")

    requests = [
        {"id": "tables", "operation": "list_tables"},
        {"id": "shape", "operation": "describe_table", "table": "sales"},
        {"id": "query", "operation": "query_readonly", "sql": "SELECT id FROM sales WHERE id>? ORDER BY id;",
         "parameters": [27], "limits": {"maxRows": 2}},
        {"id": "elevate", "operation": "query_readonly", "sql": "SELECT 1;", "limits": {"maxRows": 999999}},
        {"id": "role", "operation": "query_readonly", "sql": "SELECT 1;", "callerId": "different"},
        {"id": "fraction", "operation": "query_readonly", "sql": "SELECT 1;", "limits": {"maxRows": 1.5}},
        {"id": ["invalid"], "operation": "list_tables"},
        {"id": "recover", "operation": "query_readonly", "sql": "SELECT 42;"},
        {"id": "done", "operation": "close"},
    ]
    process = invoke("--json", "--allow-table", "sales", input="\n".join(map(json.dumps, requests)) + "\n")
    check(process.returncode == 1 and not process.stderr, process)
    responses = [json.loads(line) for line in process.stdout.splitlines()]
    check(len(responses) == len(requests), responses)
    check(responses[0]["data"] == ["sales"] and responses[0]["id"] == "tables", responses[0])
    check(responses[2]["data"]["rows"] == [[28], [29]] and responses[2]["data"]["truncated"], responses[2])
    for response in responses[3:7]:
        check(not response["ok"] and response["error"]["code"] == "INVALID_REQUEST", response)
    check(responses[6]["id"] is None, responses[6])
    check(responses[7]["data"]["rows"] == [[42]], responses[7])
    check(responses[8]["data"]["closed"], responses[8])

    invalid_lines = [
        '{"operation":"list_tables","operation":"close"}',
        '{"operation":"query_readonly","sql":"SELECT ?;","parameters":[01]}',
        '{"operation":"query_readonly","sql":"SELECT ?;","parameters":[1e999]}',
        '{"operation":"query_readonly","sql":"SELECT ?;","parameters":["\\u+123"]}',
        '{"operation":"query_readonly","sql":"SELECT ?;","parameters":["\\u\uff26\uff26\uff26\uff26"]}',
        '{"operation":"list_tables",}', '{"operation":', 'null', '[]',
        '[' * 34 + '0' + ']' * 34, 'x' * 262145,
    ]
    recovery = {"operation": "query_readonly", "sql": "SELECT 123;"}
    for invalid in invalid_lines:
        process = invoke("--json", input=invalid + "\n" + json.dumps(recovery) + "\n")
        responses = [json.loads(line) for line in process.stdout.splitlines()]
        check(process.returncode == 1 and not process.stderr and len(responses) == 2, process)
        check(not responses[0]["ok"] and responses[0]["error"]["code"] == "INVALID_REQUEST", responses[0])
        check(responses[1]["data"]["rows"] == [[123]], responses[1])

    # Keep stdin open: artifact IDs must work during the same process lifetime.
    process = subprocess.Popen(["java", "-cp", classpath, "DatabaseClient", str(home), "--json", "--max-results", "2"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, encoding="utf-8")
    watchdog = threading.Timer(30, process.kill)
    watchdog.daemon = True
    watchdog.start()
    try:
        def send(request):
            process.stdin.write(json.dumps(request) + "\n")
            process.stdin.flush()
            return json.loads(process.stdout.readline())

        first = send({"operation": "query_readonly", "sql": "SELECT ? AS value;", "parameters": ["retained"]})["data"]
        retrieved = send({"operation": "get_result", "resultId": first["resultId"]})["data"]
        check(first == retrieved, "Stored artifact must be retrieved without rerunning SQL")
        send({"operation": "query_readonly", "sql": "SELECT 2;"})
        send({"operation": "query_readonly", "sql": "SELECT 3;"})
        check(send({"operation": "get_result", "resultId": first["resultId"]})["error"]["code"] == "NOT_FOUND", "Retention applies to session")
        check(send({"operation": "close"})["ok"], "Explicit close")
        process.wait(timeout=10)
        check(process.returncode == 1 and not process.stderr.read(), "Failed requests set session exit status")
    finally:
        watchdog.cancel()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        process.stdin.close()
        process.stdout.close()
        process.stderr.close()

    schema_requests = [
        {"operation": "describe_table", "table": "sales"},
        {"operation": "query_readonly", "sql": "SELECT * FROM sales;"},
        {"operation": "get_result", "resultId": "unknown"},
        {"operation": "list_tables"},
    ]
    process = invoke("--json", "--schema-only", "--allow-table", "sales",
                     input="\n".join(map(json.dumps, schema_requests)) + "\n")
    responses = [json.loads(line) for line in process.stdout.splitlines()]
    check(responses[0]["data"]["name"] == "sales" and responses[3]["data"] == ["sales"], responses)
    check(all(response["error"]["code"] == "ACCESS_DENIED" for response in responses[1:3]), responses)

    terminal = invoke(input=".tables\n.schema sales\n.params [27]\nSELECT id FROM sales\nWHERE id>? ORDER BY id;\n.quit\n")
    check(terminal.returncode == 0 and not terminal.stderr, terminal)
    check('[28]' in terminal.stdout and '[30]' in terminal.stdout and 'Rows: 3; complete: true' in terminal.stdout, terminal.stdout)
    check('"primaryKey":true' in terminal.stdout and 'Result ID:' in terminal.stdout, terminal.stdout)
    recovered = invoke(input="SELECT @;\nSELECT 42;\n.quit\n")
    check(recovered.returncode == 1 and "SQL_ERROR:" in recovered.stderr and "[42]" in recovered.stdout, recovered)
    check(invoke(input="SELECT\n.clear\nSELECT 1;\n.quit\n").returncode == 0, "Clear pending SQL")
    check(invoke(input="SELECT\n.quit\n").returncode == 0, "Quit pending SQL")
    check(invoke(input="SELECT 1\n").returncode == 1, "Incomplete SQL fails at EOF")
    check("--query" in invoke("--help").stdout, "Client help")
    process = subprocess.Popen(["java", "-cp", classpath, "DatabaseClient", str(home)],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, encoding="utf-8")
    watchdog = threading.Timer(30, process.kill)
    watchdog.daemon = True
    watchdog.start()
    try:
        process.stdin.write("SELECT 7;\n")
        process.stdin.flush()
        first = [process.stdout.readline() for _ in range(4)]
        check(first[-1].startswith("Result ID: "), first)
        result_id = first[-1].strip().removeprefix("Result ID: ")
        process.stdin.write(f".result {result_id}\n.quit\n")
        process.stdin.flush()
        check([process.stdout.readline() for _ in range(4)] == first, "Terminal artifact retrieval")
        check(process.stdout.readline().strip() == "Session closed.", "Terminal close")
        process.wait(timeout=10)
        check(process.returncode == 0 and not process.stderr.read(), "Persistent terminal exit")
    finally:
        watchdog.cancel()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=10)
        process.stdin.close()
        process.stdout.close()
        process.stderr.close()
    print(f"Database client checks passed: {checks}")
