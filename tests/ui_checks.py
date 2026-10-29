"""Offline local-UI checks using temporary databases and a localhost model fixture."""
import argparse
from contextlib import contextmanager
import http.client
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from sql_agent.client import DatabaseClient, DatabaseError
from sql_agent.ui import WorkspaceServer
from sql_agent.workspace import Workspace, WorkspaceClient
from agent_checks import ModelServer, plan


@contextmanager
def running_server(workspace):
    with WorkspaceServer(workspace, 0) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            thread.join(timeout=5)


def request(server, path, body=None, *, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=60)
    request_headers = {"Content-Type": "application/json", "X-Workspace-Token": server.token}
    request_headers.update(headers or {})
    encoded = json.dumps(body).encode() if body is not None else None
    try:
        connection.request("POST" if body is not None else "GET", path, body=encoded, headers=request_headers)
        response = connection.getresponse()
        raw = response.read()
        return response.status, raw, dict(response.getheaders())
    finally:
        connection.close()


def browser_checks(server, directory):
    candidates = [
        Path("C:/Program Files/Google/Chrome/Application/chrome.exe"),
        Path("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"),
    ]
    browser = next((path for path in candidates if path.exists()), None)
    if browser is None:
        raise RuntimeError("Browser checks require an installed Chrome or Edge; use the HTTP checks on other systems")
    artifacts = ROOT / ".build" / "ui-preview"
    artifacts.mkdir(parents=True, exist_ok=True)
    profile = Path(directory) / "browser-profile"
    diagnostic = Path(directory) / "browser.log"
    error_log = diagnostic.open("w", encoding="utf-8")
    process = subprocess.Popen([str(browser), "--headless=new", "--disable-gpu", "--no-first-run",
                                "--no-default-browser-check", "--remote-debugging-port=0",
                                "--user-data-dir=" + str(profile), "about:blank"],
                               stdout=subprocess.DEVNULL, stderr=error_log,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    try:
        marker = profile / "DevToolsActivePort"
        for _ in range(100):
            if marker.exists():
                break
            if process.poll() is not None:
                error_log.flush()
                raise RuntimeError("Headless browser exited before opening its test connection: " + diagnostic.read_text(encoding="utf-8")[-2500:])
            time.sleep(0.1)
        port = marker.read_text().splitlines()[0]
        subprocess.run(["node", str(ROOT / "tests" / "ui_browser_checks.mjs"), port,
                        f"http://127.0.0.1:{server.server_port}", str(artifacts)],
                       cwd=ROOT, check=True, timeout=120)
    finally:
        if process.poll() is None:
            process.terminate()
        process.wait(timeout=15)
        error_log.close()


def check_ui(classpath, home, workspace_path, seed, *, browser=False):
    seed(classpath, home, [
        "CREATE TABLE products(id INTEGER PRIMARY KEY,name TEXT UNIQUE,price REAL);",
        "INSERT INTO products VALUES(1,'Pen',5.0),(9223372036854775807,'<script>alert(1)</script>',NULL);",
        "CREATE TABLE orders(id INTEGER PRIMARY KEY,product_id INTEGER REFERENCES products(id));",
    ])
    count = 0

    def check(condition, message):
        nonlocal count
        count += 1
        if not condition:
            raise AssertionError(message)

    with ModelServer() as model:
        config = {"database": {"classpath": classpath, "limits": {"maxRows": 2}},
                  "model": {"model": "ui-fixture", "base_url": model.url}}
        workspace = Workspace(home, config)
        try:
            with running_server(workspace) as server:
                def call(path, body=None, *, expected=200, headers=None):
                    status, raw, _ = request(server, path, body, headers=headers)
                    check(status == expected, f"HTTP {path}: {status} {raw!r}")
                    return json.loads(raw)

                status, html, headers = request(server, "/")
                check(status == 200 and b"SQL console" in html and b"Database explorer" in html, "Workspace page served")
                check("frame-ancestors 'none'" in headers["Content-Security-Policy"] and headers["Cache-Control"] == "no-store", "Browser security headers")
                for asset in ["/app.css", "/app.js", "/values.mjs"]:
                    status, raw, _ = request(server, asset)
                    check(status == 200 and len(raw) > 100, "Local asset " + asset)
                info = call("/api/status")["data"]
                check(info["model"]["ready"] and not model.requests and "api_key" not in json.dumps(info), "Status never calls the model or returns keys")
                check(call("/api/tables")["data"] == ["orders", "products"], "Live tables")
                schema = call("/api/schema?table=products")["data"]
                check(schema["columns"][0]["primaryKey"] and schema["indexes"][0]["origin"] == "UNIQUE", "Live schema and internal index metadata")
                preview = call("/api/preview", {"table":"products"})["data"]
                check(preview["rows"][1][0] == 9223372036854775807 and preview["rows"][1][2] is None, "Preview preserves int64 and NULL")
                call("/api/sql", {"sql":"INSERT INTO products VALUES(3,'Notebook',12.5)"})
                result = call("/api/sql", {"sql":"SELECT id FROM products ORDER BY id"})["data"]
                check(result["rows"] == [[1],[3]] and result["truncated"] and not result["complete"], "Bounded console results are marked incomplete")
                result = call("/api/sql", {"sql":"SELECT name FROM products WHERE id=?", "parameters":"[9223372036854775807]"})["data"]
                check(result["rows"] == [["<script>alert(1)</script>"]], "Large integer parameter text is bound exactly")
                check("SEARCH" in str(call("/api/plan", {"sql":"SELECT * FROM products WHERE id=1"})["data"]["rows"]), "Explain uses deterministic read-only execution")
                call("/api/sql", {"sql":"BEGIN"})
                call("/api/sql", {"sql":"UPDATE products SET price=99 WHERE id=1"})
                check(call("/api/sql", {"sql":"SELECT price FROM products WHERE id=1"})["data"]["rows"] == [[99.0]], "Console reads pending changes inside its transaction")
                check(call("/api/status")["data"]["transactionActive"], "Transaction status remains available")
                call("/api/ask", {"question":"Count products", "conversation":"first"}, expected=409)
                call("/api/sql", {"sql":"ROLLBACK"})
                check(call("/api/sql", {"sql":"SELECT price FROM products WHERE id=1"})["data"]["rows"] == [[5.0]], "Rollback restores earlier data")
                call("/api/sql", {"sql":"BEGIN"})
                call("/api/sql", {"sql":"INSERT INTO orders VALUES(1,1)"})
                call("/api/sql", {"sql":"INSERT INTO orders VALUES(2,999)"}, expected=400)
                check(call("/api/status")["data"]["transactionActive"], "Logical error preserves explicit transaction")
                call("/api/sql", {"sql":"COMMIT"})
                call("/api/sql", {"sql":"CREATE TABLE returned(id INTEGER PRIMARY KEY)"})
                returning = call("/api/sql", {"sql":"INSERT INTO returned VALUES(1),(2),(3) RETURNING id"})["data"]
                check(returning["affectedRows"] == 3 and returning["returnedRows"] == 2 and not returning["complete"], "RETURNING previews do not limit committed mutations")
                check(call("/api/sql", {"sql":"SELECT COUNT(*) FROM returned"})["data"]["rows"] == [[3]], "All RETURNING input rows were written")
                call("/api/sql", {"sql":"DROP TABLE returned"})
                call("/api/sql", {"sql":"SELECT 1; DELETE FROM products;"}, expected=400)
                call("/api/plan", {"sql":"DELETE FROM products"}, expected=400)
                call("/api/sql", {"sql":"DELETE FROM products", "workspace":True}, expected=400)
                call("/api/sql", {"sql":"DELETE FROM products"}, expected=403, headers={"X-Workspace-Token":"wrong"})
                call("/api/sql", {"sql":"DELETE FROM products"}, expected=403, headers={"Origin":"https://outside.example"})
                call("/api/status", expected=403, headers={"Host":"outside.example"})
                call("/../.env", expected=404)
                call("/api/sql", {"sql":"SELECT ?", "parameters":"[NaN]"}, expected=400)
                model.reply(plan("SELECT COUNT(*) AS n FROM products;", tables=["products"]))
                answer = call("/api/ask", {"question":"How many products?", "conversation":"first"})["data"]
                check(answer["status"] == "complete" and answer["result"]["rows"] == [[3]] and answer["visualization"]["status"] == "ready", "AI question and chart handoff through the actual workflow")
                model.reply(plan("DELETE FROM products;", tables=["products"]))
                denied = call("/api/ask", {"question":"Remove all products", "conversation":"second"})["data"]
                check(denied["status"] == "error" and workspace.client.execute_sql("SELECT COUNT(*) FROM products;")["rows"] == [[3]], "AI proposals cannot use the trusted write route")
                check(len(workspace._workflows) == 2, "Separate browser conversations keep separate contexts")
                call("/api/conversation/clear", {"conversation":"first"})
                check("first" not in workspace._workflows, "Conversation can be cleared")
                with workspace._operation():
                    call("/api/sql", {"sql":"SELECT 1"}, expected=409)
                    check(call("/api/status")["data"]["busy"], "Status remains responsive while execution is busy")
                history = call("/api/history")["data"]
                check(any(entry["status"] == "error" for entry in history) and any(entry["kind"] == "question" for entry in history), "History includes failures and questions")
                if browser:
                    model.reply(plan("SELECT COUNT(*) AS n FROM products;", tables=["products"]))
                    browser_checks(server, workspace_path)
                call("/api/sql", {"sql":"BEGIN"})
                call("/api/sql", {"sql":"INSERT INTO products VALUES(4,'Uncommitted',1)"})
        finally:
            workspace.close()
    with WorkspaceClient(home, classpath=classpath) as client:
        check(client.execute_sql("SELECT COUNT(*) FROM products;")["rows"] == [[3]], "New process sees committed data and discards pending work on close")
        try:
            client.query_readonly("DELETE FROM products;")
            raise AssertionError("Read-only operation accepted a write")
        except DatabaseError as error:
            check(error.code == "ACCESS_DENIED", "Java read-only boundary survives workspace mode")
    with DatabaseClient(home, classpath=classpath) as client:
        try:
            client.request("execute_sql", sql="DELETE FROM products;")
            raise AssertionError("Ordinary tools exposed trusted execution")
        except DatabaseError as error:
            check(error.code == "INVALID_REQUEST", "Ordinary client has no trusted route")
    with Workspace(home, {"database":{"classpath":classpath}}) as workspace_without_model:
        check(not workspace_without_model.status()["model"]["ready"], "SQL workspace starts without model credentials or configuration")
        check(workspace_without_model.query("SELECT COUNT(*) FROM products;")["rows"] == [[3]], "SQL remains usable without an AI model")
    print(f"Browser UI checks passed: {count}; real Java session and offline model fixture", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", action="store_true", help="Also exercise an installed Windows Chrome/Edge with Node")
    options = parser.parse_args()
    sys.path.insert(0, str(ROOT / "scripts"))
    from build import build
    from run_tests import database_hashes, run_engine
    before = database_hashes()
    try:
        with tempfile.TemporaryDirectory(prefix="sql-ui-tests-") as temporary:
            directory = Path(temporary)
            classpath = build(directory / "classes", tests=True)
            check_ui(classpath, directory / "db", directory, run_engine, browser=options.browser)
    finally:
        if database_hashes() != before:
            raise AssertionError("Existing dbms/db files changed during UI verification")


if __name__ == "__main__":
    main()
