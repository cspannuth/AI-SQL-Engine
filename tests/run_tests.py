"""Run isolated engine regressions, SQLite comparisons, and crash-recovery checks."""
import base64
import hashlib
import json
import os
from pathlib import Path
import random
import sqlite3
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from build import build, generate
from client_checks import check_client
from rowid_checks import check_rowids
from constraint_checks import check_constraints
from stage_checks import check_stages
from reliability_checks import check_reliability


def database_hashes():
    directory = ROOT / "dbms" / "db"
    return {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in directory.glob("*") if path.is_file()}


def check_generated_metadata(directory):
    for path in Path(directory).glob("*.java"):
        header = path.read_text(encoding="utf-8").split("public class", 1)[0]
        for local_path in (str(ROOT), ROOT.as_posix(), ROOT.as_uri()):
            if local_path in header:
                raise AssertionError(f"Generated header exposes a local path: {path.name}")
        if "<tt>file:" in header:
            raise AssertionError(f"Generated header exposes a file URI: {path.name}")
    print("Generated metadata checks passed: no local project paths")


def run_engine(classpath, home, statements):
    encoded = "\n".join(base64.b64encode(sql.encode()).decode() for sql in statements) + "\n"
    process = subprocess.run(
        ["java", "-cp", classpath, "SqlRunner", str(home)], input=encoded,
        text=True, encoding="utf-8", capture_output=True, check=True, timeout=60,
    )
    if process.stderr.strip():
        raise AssertionError(process.stderr)
    results = [json.loads(line) for line in process.stdout.splitlines()]
    if len(results) != len(statements):
        raise AssertionError(f"Expected {len(statements)} responses, got {len(results)}")
    return results


def compatible_queries():
    statements = [
        "CREATE TABLE readings(id INTEGER PRIMARY KEY, category TEXT, amount REAL, quantity INTEGER, note TEXT);",
        "CREATE TABLE categories(id INTEGER PRIMARY KEY, label TEXT);",
        "INSERT INTO categories VALUES(1,'alpha'),(2,'beta'),(9,'extra');",
    ]
    randomizer = random.Random(42)
    for key in range(-5, 26):
        group = randomizer.choice(["'alpha'", "'beta'", "'a.b'", "NULL"])
        amount = randomizer.choice(["NULL", "-3.5", "0.0", "2.5", "10.0"])
        quantity = randomizer.choice(["NULL", "-2", "0", "1", "4"])
        note = randomizer.choice(["NULL", "''", "'Mixed'", "'a.b'", "'a[b'", "'it''s good'"])
        statements.append(f"INSERT INTO readings VALUES({key},{group},{amount},{quantity},{note});")
    predicates = [
        "amount > 0", "amount IS NULL", "amount IS NOT NULL", "amount = NULL",
        "NOT (amount > 0)", "NOT NOT (amount > 0)", "amount <= 2.5",
        "(amount > 0 OR quantity < 0)", "NOT (amount > 0 OR quantity < 0)",
        "NOT (amount > 0 AND quantity < 0)", "(amount > 0 AND quantity < 0)",
        "quantity IN (1,4,NULL)", "quantity NOT IN (1,4,NULL)", "quantity IN ()",
        "quantity NOT IN ()", "quantity BETWEEN -1 AND 4", "quantity NOT BETWEEN -1 AND 4",
        "note LIKE 'm%'", "note LIKE 'a.b'", "note NOT LIKE '%b'", "note LIKE 'a_b'",
        "id = 2", "id >= -2 AND id < 5", "id IN (-5,0,25)",
        "id='2'", "id IN ('1','3')", "id IS 2", "id IS NOT 2", "1", "NULL", "'2x'", "'false'",
    ]
    for predicate in predicates:
        statements.append(f"SELECT id,amount,quantity FROM readings WHERE {predicate} ORDER BY id;")
    for descending in ["ASC", "DESC"]:
        for limit in ["", "LIMIT 0", "LIMIT 3", "LIMIT 2 OFFSET 4", "LIMIT 4,2", "LIMIT -1 OFFSET -3"]:
            statements.append(
                f"SELECT id,amount,quantity FROM readings ORDER BY amount {descending},id {descending} {limit};"
            )
    statements += [
        "SELECT 1+2*3,(1+2)*3,7/2,7/2.0,-7/2,7%3,1/0,NULL+1,'a'||'b';",
        "SELECT 1<2,2<1,NULL=1,NOT NULL,NULL IS NULL,NULL IS NOT NULL,1 IS 1.0;",
        "SELECT 1 AND NULL,0 AND NULL,1 OR NULL,0 OR NULL,'2x'+3,'no'+4,1.0%0.5;",
        "SELECT -9223372036854775808,9223372036854775807,9223372036854775808;",
        "SELECT category,COUNT(*) AS n FROM readings GROUP BY category HAVING n>3 ORDER BY category;",
        "SELECT category,SUM(amount) AS total FROM readings GROUP BY category HAVING total>0 ORDER BY category;",
        "SELECT SUM(note),AVG(note),TOTAL(note) FROM readings;",
        "SELECT id,quantity+2,quantity*3,quantity/2 FROM readings ORDER BY id;",
        "SELECT DISTINCT category FROM readings ORDER BY category;",
        "SELECT DISTINCT category,quantity FROM readings ORDER BY category,quantity;",
        "SELECT category,COUNT(*),COUNT(amount),SUM(amount),AVG(amount),MIN(amount),MAX(amount),TOTAL(amount) "
        "FROM readings GROUP BY category ORDER BY category;",
        "SELECT quantity,COUNT(*),SUM(id),AVG(id),MIN(id),MAX(id) FROM readings GROUP BY quantity ORDER BY quantity;",
        "SELECT COUNT(DISTINCT category),SUM(DISTINCT quantity),AVG(DISTINCT amount) FROM readings;",
        "SELECT COUNT(*),SUM(amount),AVG(amount),MIN(amount),MAX(amount),TOTAL(amount) FROM readings WHERE id>99;",
        "SELECT category,COUNT(*) AS n FROM readings GROUP BY category HAVING COUNT(*)>3 ORDER BY n DESC,category;",
        "SELECT category AS c,SUM(amount) AS total FROM readings GROUP BY c ORDER BY total DESC,c;",
        "SELECT quantity%2 AS quantity,COUNT(*) FROM readings GROUP BY quantity ORDER BY 1,2;",
        "SELECT r.id,c.label FROM readings r JOIN categories c ON r.id=c.id ORDER BY r.id;",
        "SELECT r.id,c.label FROM readings r LEFT JOIN categories c ON r.id=c.id ORDER BY r.id;",
        "SELECT r.id,c.label FROM readings r LEFT JOIN categories c ON r.id=c.id WHERE c.id IS NULL ORDER BY r.id;",
        "SELECT r.id,c.label FROM readings r LEFT JOIN categories c ON r.id=c.id AND c.id<0 ORDER BY r.id;",
        "SELECT a.id,b.id FROM categories a, categories b WHERE a.id<b.id ORDER BY a.id,b.id;",
        "SELECT a.id,b.id FROM categories a CROSS JOIN categories b ORDER BY a.id,b.id LIMIT 2;",
        "SELECT c.id,COUNT(r.id),SUM(r.amount) FROM categories c LEFT JOIN readings r ON c.id=r.id GROUP BY c.id ORDER BY c.id;",
        "BEGIN;",
        "UPDATE readings SET amount=amount+1,quantity=quantity*2 WHERE id>10;",
        "DELETE FROM readings WHERE id<0;",
        "SELECT id,amount,quantity FROM readings ORDER BY id;",
        "ROLLBACK;",
        "SELECT id,amount,quantity FROM readings ORDER BY id;",
        "BEGIN;",
        "CREATE TABLE withintransaction(id INTEGER PRIMARY KEY,value TEXT);",
        "INSERT INTO withintransaction VALUES(1,'first');",
        "ROLLBACK;",
        "SELECT * FROM withintransaction;",
        "BEGIN;",
        "INSERT INTO categories VALUES(3,'kept');",
        "INSERT INTO categories VALUES(4,'new'),(1,'duplicate');",
        "SELECT id,label FROM categories ORDER BY id;",
        "ROLLBACK;",
        "INSERT INTO categories VALUES(4,'new'),(1,'duplicate');",
        "SELECT id,label FROM categories ORDER BY id;",
        "UPDATE categories SET id=1 WHERE id=2;",
        "SELECT id,label FROM categories ORDER BY id;",
        "CREATE TABLE IF NOT EXISTS categories(id INTEGER);",
        "DROP TABLE IF EXISTS missing;",
        "SELECT unknown FROM categories;",
        "SELECT id FROM categories c,categories d;",
    ]
    return statements


def compare_sqlite(classpath, home):
    statements = compatible_queries()
    engine = run_engine(classpath, home, statements)
    reference = sqlite3.connect(":memory:", isolation_level=None)
    for sql, result in zip(statements, engine):
        try:
            cursor = reference.execute(sql)
            expected = [list(row) for row in cursor.fetchall()]
        except sqlite3.Error as error:
            if "error" not in result:
                raise AssertionError(f"SQLite rejected {sql!r}, engine accepted it: {error}")
        else:
            if "error" in result:
                raise AssertionError(f"Engine rejected {sql!r}: {result['error']}")
            if result["rows"] != expected:
                raise AssertionError(f"{sql}\nSQLite: {expected}\nEngine: {result['rows']}")
    reference.close()
    print(f"SQLite comparisons passed: {len(statements)} statements (SQLite {sqlite3.sqlite_version})")


def crash_recovery(classpath, home):
    process = subprocess.Popen(
        ["java", "-cp", classpath, "SqlRunner", str(home)],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8",
    )
    try:
        for sql in [
            "CREATE TABLE recovery(id INTEGER PRIMARY KEY,value TEXT UNIQUE DEFAULT 'committed' CHECK(value<>''));",
            "INSERT INTO recovery VALUES(1,'committed');",
            "CREATE INDEX recovery_lookup ON recovery(value);",
            "CREATE TABLE recovery_child(pid INTEGER REFERENCES recovery(id));",
            "INSERT INTO recovery_child VALUES(1);",
            "BEGIN;",
            "SAVEPOINT recovery_mark;",
            "INSERT INTO recovery(value) VALUES('discarded by savepoint');",
            "ROLLBACK TO recovery_mark;",
            "INSERT INTO recovery(value) VALUES('unfinished');",
            "INSERT INTO recovery_child VALUES(2);",
            "CREATE TABLE partial(id INTEGER UNIQUE DEFAULT 1 CHECK(id>0) REFERENCES recovery(id));",
        ]:
            process.stdin.write(base64.b64encode(sql.encode()).decode() + "\n")
            process.stdin.flush()
            response = json.loads(process.stdout.readline())
            if "error" in response:
                raise AssertionError(response)
        # Deliberate process termination on this disposable test database.
        process.kill()
        process.wait(timeout=15)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=15)
        process.stdin.close()
        process.stdout.close()
        process.stderr.close()
    results = run_engine(classpath, home, [
        "SELECT * FROM recovery ORDER BY id;",
        "SELECT * FROM partial;",
        "INSERT INTO recovery(value) VALUES('after recovery');",
        "SELECT rowid,id,value FROM recovery ORDER BY id;",
        "INSERT INTO recovery DEFAULT VALUES;",
        "INSERT INTO recovery(value) VALUES('');",
        "INSERT INTO recovery_child VALUES(99);",
        "DELETE FROM recovery WHERE id=1;",
        "SELECT * FROM recovery_child;",
        "SELECT id FROM recovery WHERE value='committed';",
    ])
    assert results[0]["rows"] == [[1, "committed"]], results
    assert "error" in results[1], results
    assert results[3]["rows"] == [[1, 1, "committed"], [2, 2, "after recovery"]], results
    assert all("error" in result for result in results[4:8]), results
    assert results[8]["rows"] == [[1]], results
    assert results[9]["rows"] == [[1]], results
    print("Crash recovery passed: committed data and constraints retained; unfinished rows and DDL rolled back")


def cli_checks(classpath, home):
    script = """-- multiline and semicolon handling
CREATE TABLE cli_test(
id INTEGER PRIMARY KEY,
label TEXT);
INSERT INTO cli_test VALUES(1,'it''s a; value'); SELECT label FROM cli_test;
SELECT @;
SELECT 123;
q
"""
    process = subprocess.run(["java", "-cp", classpath, "DBMS", str(home)],
                             input=script, text=True, encoding="utf-8",
                             capture_output=True, check=True, timeout=30)
    assert "it's a; value" in process.stdout, process.stdout
    assert "123" in process.stdout, process.stdout
    assert process.stderr.count("Error:") == 1, process.stderr
    assert "incomplete" not in process.stderr, process.stderr
    print("CLI checks passed: multiline input, quoting, batching, and recovery after syntax errors")


def main():
    before = database_hashes()
    try:
        with tempfile.TemporaryDirectory(prefix="sql-engine-tests-") as temporary:
            workspace = Path(temporary)
            generated = workspace / "generated"
            generate(generated)
            check_generated_metadata(generated)
            classpath = build(workspace / "classes", generated=generated, tests=True)
            subprocess.run(["java", "-cp", classpath, "EngineTests", str(workspace / "engine")],
                           cwd=ROOT, check=True, timeout=60)
            subprocess.run(["java", "-cp", classpath, "AdapterTests", str(workspace / "adapter")],
                           cwd=ROOT, check=True, timeout=60)
            subprocess.run(["java", "-cp", classpath, "ConstraintTests", str(workspace / "constraint-api")],
                           cwd=ROOT, check=True, timeout=60)
            for entry in ["IndexTests","QueryTests","IntegrityTests"]:
                subprocess.run(["java","-cp",classpath,entry,str(workspace / entry)],cwd=ROOT,check=True,timeout=90)
            check_client(classpath, workspace / "client", workspace, run_engine)
            compare_sqlite(classpath, workspace / "comparison")
            check_rowids(classpath, workspace / "rowids", run_engine)
            check_constraints(classpath, workspace / "constraints", run_engine)
            check_stages(classpath,workspace / "stages",run_engine)
            crash_recovery(classpath, workspace / "recovery")
            check_reliability(classpath, workspace / "reliability", run_engine)
            cli_checks(classpath, workspace / "cli")
    finally:
        if database_hashes() != before:
            raise AssertionError("Existing dbms/db files changed during verification")
    print("All checks passed; existing dbms/db files are unchanged.")


if __name__ == "__main__":
    main()
