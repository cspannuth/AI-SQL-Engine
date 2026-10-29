"""Repeatable differential, restart, and acknowledged-boundary crash tests."""
import argparse
import base64
import json
from pathlib import Path
import queue
import random
import sqlite3
import subprocess
import sys
import tempfile
import threading


ROOT = Path(__file__).resolve().parents[1]
SEEDS = (7, 42, 2026)
STATE = "SELECT id,category,n,amount,note FROM samples ORDER BY id;"


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, str):
        return "'" + value.replace("'", "''") + "'"
    return str(value)


def expression(rng, depth=2):
    """Bounded expression trees keep failures understandable and execution finite."""
    if depth == 0:
        return rng.choice(["n", "amount", "NULL", "-3", "0", "2", "2.5"])
    left = expression(rng, depth - 1)
    right = expression(rng, depth - 1)
    return rng.choice([
        f"({left} + {right})", f"({left} - {right})", f"({left} * {right})",
        f"({left} / {right})", f"COALESCE({left},{right})",
        f"CASE WHEN n>0 THEN {left} ELSE {right} END",
    ])


def predicate(rng):
    left, right = expression(rng, 1), expression(rng, 1)
    return rng.choice([
        f"({left})>{right}", f"NOT (({left})={right})",
        f"(({left})>0 OR n IS NULL)", f"(({left})<2 AND amount IS NOT NULL)",
        "n IN (-3,0,2,NULL)", "n NOT IN (-3,0,2,NULL)",
        "note LIKE 'a_%'", "note IS NULL", "n BETWEEN -2 AND 4",
    ])


def generated_batches(seed):
    rng = random.Random(seed)
    first = [
        "CREATE TABLE samples(id INTEGER PRIMARY KEY,category TEXT,n INTEGER CHECK(n>=-20),amount REAL,note TEXT);",
        "CREATE TABLE groups(id INTEGER PRIMARY KEY,label TEXT UNIQUE);",
        "INSERT INTO groups VALUES(1,'alpha'),(2,'beta'),(3,'empty');",
        "CREATE TABLE tickets(id INTEGER PRIMARY KEY,group_id INTEGER REFERENCES groups(id) ON DELETE SET NULL ON UPDATE CASCADE,code TEXT UNIQUE,score INTEGER DEFAULT 2 CHECK(score>=0));",
    ]
    for key in range(1, 33):
        values = [key, rng.choice([None, "alpha", "beta"]),
                  rng.choice([None, -3, 0, 2, 5]), rng.choice([None, -2.5, 0.0, 1.5, 8.0]),
                  rng.choice([None, "", "a.b", "alpha", "it's", "\u00e9\u6f22"])]
        first.append("INSERT INTO samples VALUES(" + ",".join(map(literal, values)) + ");")
    for _ in range(60):
        first.append(f"SELECT id,{expression(rng)} AS value FROM samples "
                     f"WHERE {predicate(rng)} ORDER BY id;")
    for _ in range(12):
        where = predicate(rng)
        first += [
            "SELECT category,COUNT(*),COUNT(n),SUM(n),AVG(amount),MIN(note),MAX(n) "
            f"FROM samples WHERE {where} GROUP BY category ORDER BY category;",
            "SELECT g.id,COUNT(s.id),SUM(s.n) FROM groups g LEFT JOIN samples s "
            f"ON g.label=s.category AND ({where}) GROUP BY g.id ORDER BY g.id;",
        ]
    lookups = [f"SELECT id,n FROM samples WHERE n={literal(value)} ORDER BY id;"
               for value in [None, -3, 0, 2, 5, "2", 2.0]]
    first += lookups
    first += [
        "CREATE INDEX sample_n ON samples(n);",
        "CREATE INDEX sample_category ON samples(category);",
    ]
    # Evaluate the same predicates with an index and a scan, as well as against SQLite.
    first += lookups
    for _ in range(12):
        key = rng.randrange(1, 4)
        first += [
            "SELECT g.id,(SELECT SUM(s.n) FROM samples s WHERE s.category=g.label) "
            f"FROM groups g WHERE g.id<={key} ORDER BY g.id;",
            "WITH totals AS (SELECT category,COUNT(*) AS n FROM samples GROUP BY category) "
            "SELECT g.id,t.n FROM groups g LEFT JOIN totals t ON g.label=t.category ORDER BY g.id;",
        ]
    # Persist this half and reopen in another JVM before mutating indexed data.
    second = [STATE]
    for turn in range(18):
        key = rng.randrange(1, 33)
        second += [
            "BEGIN;", "SAVEPOINT before_change;",
            f"UPDATE samples SET n=COALESCE(n,0)+1,amount=COALESCE(amount,0)+0.5 WHERE id={key};",
            f"INSERT INTO samples(id,n) VALUES({100 + turn},1),({key},2);",
            STATE,  # A failed batch must retain earlier work and insert neither row.
            f"UPDATE samples SET n=-21 WHERE id={key};", STATE,
        ]
        if turn % 3 == 0:
            second += ["ROLLBACK TO before_change;", STATE]
        second += ["RELEASE before_change;", "COMMIT;" if turn % 2 else "ROLLBACK;", STATE]
        value = rng.choice([-3, 0, 2, 5])
        second.append(f"SELECT id,n FROM samples WHERE n={value} ORDER BY id;")
    for key in range(1, 7):
        parent = literal(rng.choice([None, 1, 2]))
        second += [
            f"INSERT INTO tickets(id,group_id,code) VALUES({key},{parent},'ticket{key}');",
            f"INSERT INTO tickets(id,code) VALUES({key + 20},'ticket{key}');",
            f"INSERT INTO tickets(id,group_id,code) VALUES({key + 40},999,'bad_parent{key}');",
            f"UPDATE tickets SET score=-1 WHERE id={key};",
            "SELECT * FROM tickets ORDER BY id;",
        ]
    second += [
        "BEGIN;", "UPDATE groups SET id=11 WHERE id=1;",
        "SELECT * FROM tickets ORDER BY id;", "ROLLBACK;",
        "DELETE FROM groups WHERE id=2;", "SELECT * FROM tickets ORDER BY id;",
        "DELETE FROM samples WHERE id>28;", STATE,
        "DROP INDEX sample_n;",
        "SELECT id,n FROM samples WHERE n=2 ORDER BY id;",
        "CREATE INDEX sample_n ON samples(n);",
        "SELECT id,n FROM samples WHERE n=2 ORDER BY id;",
    ]
    return [first, second, [STATE, "SELECT id FROM samples WHERE n=2 ORDER BY id;",
                            "SELECT * FROM tickets ORDER BY id;"]]


def compare(reference, sql, actual, *, integrity_errors_only=False):
    """Compare rows, failures, and DML counts; error wording is engine specific."""
    try:
        cursor = reference.execute(sql)
        expected = [list(row) for row in cursor.fetchall()]
    except sqlite3.Error as error:
        if integrity_errors_only and not isinstance(error, sqlite3.IntegrityError):
            raise AssertionError(f"Invalid generated SQL: {sql}: {error}") from error
        if "error" not in actual:
            raise AssertionError(f"SQLite rejected {sql}: {error}; engine: {actual}")
        return
    if "error" in actual or actual["rows"] != expected:
        raise AssertionError(f"{sql}\nSQLite: {expected}\nEngine: {actual}")
    if sql.split()[0].upper() in {"INSERT", "UPDATE", "DELETE"}:
        affected = reference.execute("SELECT changes()").fetchone()[0]
        if actual["affected"] != affected:
            raise AssertionError(f"{sql}\nExpected {affected} affected rows; engine: {actual}")


def save_failure(label, transcript, error):
    """Preserve generated SQL even though the disposable database is removed."""
    directory = ROOT / ".build" / "validation-failures"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{label}.sql"
    path.write_text("\n".join(transcript) + "\n", encoding="utf-8")
    raise AssertionError(f"[{label}] {error}\nSQL transcript saved to {path}") from error


def check_generated(classpath, home, run_engine, seeds=SEEDS):
    total = 0
    for seed in seeds:
        transcript = []
        reference = sqlite3.connect(":memory:", isolation_level=None)
        reference.execute("PRAGMA foreign_keys=ON")
        try:
            for number, batch in enumerate(generated_batches(seed), 1):
                transcript.append(f"-- JVM session {number}")
                try:
                    results = run_engine(classpath, Path(home) / str(seed), batch)
                except Exception:
                    transcript.extend(batch)
                    raise
                for sql, result in zip(batch, results):
                    transcript.append(sql)
                    compare(reference, sql, result, integrity_errors_only=True)
                    total += 1
        except Exception as error:
            save_failure(f"seed-{seed}", transcript, error)
        finally:
            reference.close()
        print(f"Generated SQLite checks passed: seed {seed}, three JVM sessions", flush=True)
    print(f"Generated SQLite comparisons: {total} statements", flush=True)


class EngineProcess:
    """Interactive runner with a bounded response wait, including startup failures."""
    def __init__(self, classpath, home):
        self.process = subprocess.Popen(
            ["java", "-cp", classpath, "SqlRunner", str(home)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8",
        )
        self.responses = queue.Queue()
        self.errors = []
        self.reader = threading.Thread(target=self.read_responses, daemon=True)
        self.error_reader = threading.Thread(target=self.read_errors, daemon=True)
        self.reader.start()
        self.error_reader.start()

    def read_responses(self):
        for line in self.process.stdout:
            self.responses.put(line)
        self.responses.put(None)

    def read_errors(self):
        self.errors.extend(self.process.stderr)

    def execute(self, sql):
        self.process.stdin.write(base64.b64encode(sql.encode("utf-8")).decode() + "\n")
        self.process.stdin.flush()
        try:
            line = self.responses.get(timeout=20)
        except queue.Empty as error:
            raise AssertionError(f"Runner did not acknowledge {sql}") from error
        if line is None:
            raise AssertionError(f"Runner exited before acknowledging {sql}: {''.join(self.errors)}")
        return json.loads(line)

    def kill(self):
        if self.process.stdin.closed:
            return
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=15)
        self.reader.join(timeout=5)
        self.error_reader.join(timeout=5)
        self.process.stdin.close()
        self.process.stdout.close()
        self.process.stderr.close()


RECOVERY_SETUP = [
    "CREATE TABLE parent(id INTEGER PRIMARY KEY,label TEXT UNIQUE);",
    "CREATE TABLE child(id INTEGER PRIMARY KEY,pid INTEGER REFERENCES parent(id) ON DELETE CASCADE ON UPDATE CASCADE);",
    "CREATE TABLE delayed(pid INTEGER REFERENCES parent(id) DEFERRABLE INITIALLY DEFERRED);",
    "CREATE INDEX parent_label ON parent(label);",
    "INSERT INTO parent VALUES(1,'one'),(2,'two');",
    "INSERT INTO child VALUES(1,1),(2,2);",
]
RECOVERY_CASES = {
    "committed_then_pending": [
        "BEGIN;", "UPDATE parent SET label='kept' WHERE id=1;", "COMMIT;",
        "BEGIN;", "UPDATE parent SET label='pending' WHERE id=1;", "DELETE FROM parent WHERE id=2;",
    ],
    "failed_batch": [
        "BEGIN;", "INSERT INTO parent VALUES(3,'three');",
        "INSERT INTO parent VALUES(4,'four'),(1,'duplicate');",
        "SELECT * FROM parent ORDER BY id;",
    ],
    "cascade_then_commit": [
        "BEGIN;", "UPDATE parent SET id=10 WHERE id=1;", "DELETE FROM parent WHERE id=2;", "COMMIT;",
        "BEGIN;", "DELETE FROM parent WHERE id=10;",
    ],
    "schema_restore": [
        "BEGIN;", "SAVEPOINT restore;", "ALTER TABLE parent RENAME TO renamed;",
        "DROP INDEX parent_label;", "CREATE INDEX temporary_lookup ON renamed(id);",
        "ROLLBACK TO restore;", "UPDATE parent SET label='pending' WHERE id=1;",
        "CREATE TABLE unfinished(id INTEGER);",
    ],
    "failed_deferred_commit": [
        "BEGIN;", "INSERT INTO delayed VALUES(99);", "COMMIT;",
        "SELECT * FROM delayed;",
    ],
    "released_outer_savepoint": [
        "SAVEPOINT outer_mark;", "INSERT INTO parent VALUES(3,'three');",
        "INSERT INTO child VALUES(3,3);", "RELEASE outer_mark;",
        "BEGIN;", "SAVEPOINT inner_mark;", "DELETE FROM parent WHERE id=3;",
        "ROLLBACK TO inner_mark;", "DELETE FROM parent WHERE id=1;",
    ],
}
RECOVERY_PROBES = [
    "SELECT * FROM parent ORDER BY id;", "SELECT * FROM child ORDER BY id;",
    "SELECT * FROM delayed ORDER BY pid;", "SELECT * FROM unfinished;", "SELECT * FROM renamed;",
    "DROP INDEX temporary_lookup;",
    "SELECT id FROM parent WHERE label='one' ORDER BY id;",
    "SELECT id FROM parent WHERE label='kept' ORDER BY id;",
    "INSERT INTO child VALUES(9,999);", "INSERT INTO delayed VALUES(999);",
    "INSERT INTO parent VALUES(20,'after');", "INSERT INTO parent VALUES(21,'after');",
    "SELECT * FROM parent ORDER BY id;",
]


def check_recovery(classpath, home, run_engine):
    for name, actions in RECOVERY_CASES.items():
        directory = Path(home) / name
        directory.mkdir(parents=True)
        reference_path = directory / "reference.sqlite"
        reference = sqlite3.connect(reference_path, isolation_level=None)
        reference.execute("PRAGMA foreign_keys=ON")
        engine = EngineProcess(classpath, directory / "engine")
        transcript = []
        try:
            for sql in RECOVERY_SETUP + actions:
                transcript.append(sql)
                compare(reference, sql, engine.execute(sql))
            engine.kill()  # Kill only after the last response, never infer a write's outcome.
            reference.close()  # SQLite discards its still-open transaction too.
            reference = sqlite3.connect(reference_path, isolation_level=None)
            reference.execute("PRAGMA foreign_keys=ON")
            transcript.append("-- Forced termination; new JVM and SQLite connection")
            for sql, actual in zip(RECOVERY_PROBES, run_engine(classpath, directory / "engine", RECOVERY_PROBES)):
                transcript.append(sql)
                compare(reference, sql, actual)
            plan_sql = "EXPLAIN QUERY PLAN SELECT id FROM parent WHERE label='one';"
            transcript.append(plan_sql)
            plan = run_engine(classpath, directory / "engine", [plan_sql])[0]
            if "USING INDEX" not in str(plan.get("rows")):
                raise AssertionError(f"Recovery lost the equality index: {plan}")
            # A constraint index may now win over the equivalent user index. Dropping the
            # user index proves its metadata and physical database both survived recovery.
            drop_sql = "DROP INDEX parent_label;"
            transcript.append(drop_sql)
            compare(reference, drop_sql, run_engine(classpath, directory / "engine", [drop_sql])[0])
            constraint_plan = run_engine(classpath, directory / "engine", [plan_sql])[0]
            if "USING INDEX __dbms_auto_" not in str(constraint_plan.get("rows")):
                raise AssertionError(f"Recovery lost the constraint index: {constraint_plan}")
        except Exception as error:
            save_failure(f"recovery-{name}", transcript, error)
        finally:
            engine.kill()
            reference.close()
        print(f"Recovery scenario passed: {name}", flush=True)


def check_reliability(classpath, workspace, run_engine, seeds=SEEDS):
    workspace = Path(workspace)
    check_generated(classpath, workspace / "generated", run_engine, seeds)
    check_recovery(classpath, workspace / "recovery", run_engine)
    for phase in ["prepare", "verify"]:
        subprocess.run(["java", "-cp", classpath, "ScaleTests", str(workspace / "scale"), phase],
                       cwd=ROOT, check=True, timeout=120)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=int, action="append", help="Replace default seeds; repeat for several seeds")
    options = parser.parse_args()
    sys.path.insert(0, str(ROOT / "scripts"))
    from build import build
    from run_tests import database_hashes, run_engine
    before = database_hashes()
    try:
        with tempfile.TemporaryDirectory(prefix="sql-reliability-") as temporary:
            workspace = Path(temporary)
            classpath = build(workspace / "classes", tests=True)
            check_reliability(classpath, workspace, run_engine, options.seed or SEEDS)
    finally:
        if database_hashes() != before:
            raise AssertionError("Existing dbms/db files changed during verification")
    print("Reliability checks passed; existing dbms/db files are unchanged.")


if __name__ == "__main__":
    main()
