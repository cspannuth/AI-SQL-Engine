"""Sequential schema improvements, compared with SQLite across JVM restarts."""
import argparse
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile

from reliability_checks import EngineProcess, compare


STAGES = {
    "defaults": [
        "CREATE TABLE calculations(id INTEGER PRIMARY KEY,n INTEGER DEFAULT (2+3*4),label TEXT DEFAULT (LOWER('READY')),optional INTEGER DEFAULT (NULL));",
        "INSERT INTO calculations(id) VALUES(1),(2);",
        "INSERT INTO calculations(id,n,label) VALUES(3,NULL,'explicit');",
        "INSERT INTO calculations(id) SELECT id+10 FROM calculations;",
        "SELECT * FROM calculations ORDER BY id;",
        "CREATE TABLE risky(id INTEGER PRIMARY KEY,n INTEGER DEFAULT (ABS(-9223372036854775808)));",
        "INSERT INTO risky VALUES(1,7);",
        "INSERT INTO risky(id) VALUES(2);",
        "SELECT * FROM risky ORDER BY id;",
        "BEGIN;", "INSERT INTO risky VALUES(2,8);", "INSERT INTO risky(id) VALUES(3);",
        "SELECT * FROM risky ORDER BY id;", "ROLLBACK;",
        "CREATE TABLE clock_values(id INTEGER PRIMARY KEY,day TEXT DEFAULT CURRENT_DATE,clock TEXT DEFAULT CURRENT_TIME,stamp TEXT DEFAULT CURRENT_TIMESTAMP);",
        "INSERT INTO clock_values(id) VALUES(1),(2);",
        "SELECT COUNT(DISTINCT day),COUNT(DISTINCT clock),COUNT(DISTINCT stamp),MIN(LENGTH(day)),MIN(LENGTH(clock)),MIN(LENGTH(stamp)) FROM clock_values;",
        "SELECT id,day=SUBSTR(stamp,1,10),clock=SUBSTR(stamp,12) FROM clock_values ORDER BY id;",
        "CREATE TABLE invalid_default(n INTEGER DEFAULT (missing+1));",
        "CREATE TABLE invalid_default(n INTEGER DEFAULT (SELECT 1));",
        "CREATE TABLE invalid_default(n INTEGER DEFAULT (?));",
        "CREATE TABLE invalid_default(n INTEGER DEFAULT (\"text\"));",
        "CREATE TABLE add_defaults(id INTEGER PRIMARY KEY);", "INSERT INTO add_defaults VALUES(1);",
        "ALTER TABLE add_defaults ADD n INTEGER DEFAULT (5);",
        "ALTER TABLE add_defaults ADD shifted INTEGER DEFAULT (-3);",
        "ALTER TABLE add_defaults ADD real_value REAL DEFAULT (-2.5);",
        "ALTER TABLE add_defaults ADD cast_value INTEGER DEFAULT (CAST('5' AS INTEGER));",
        "ALTER TABLE add_defaults ADD calculated INTEGER DEFAULT (1+2);",
        "ALTER TABLE add_defaults ADD stamp TEXT DEFAULT CURRENT_TIMESTAMP;",
        "SELECT * FROM add_defaults;",
        "CREATE TABLE empty_defaults(id INTEGER PRIMARY KEY);",
        "ALTER TABLE empty_defaults ADD stamp TEXT DEFAULT CURRENT_TIMESTAMP;",
        "ALTER TABLE empty_defaults ADD n INTEGER DEFAULT (1+2);",
        "INSERT INTO empty_defaults(id) VALUES(1);",
        "SELECT id,LENGTH(stamp),n FROM empty_defaults;",
        "CREATE TABLE parents(id INTEGER PRIMARY KEY);", "INSERT INTO parents VALUES(2),(3);",
        "CREATE TABLE children(pid INTEGER DEFAULT (1+1) REFERENCES parents ON DELETE SET DEFAULT);",
        "INSERT INTO children VALUES(3);", "DELETE FROM parents WHERE id=3;", "SELECT * FROM children;",
        "CREATE TABLE generated_id(id INTEGER PRIMARY KEY DEFAULT (ABS(-9223372036854775808)),value TEXT);",
        "INSERT INTO generated_id(value) VALUES('generated');", "SELECT * FROM generated_id;",
        "INSERT INTO clock_values(id,stamp) VALUES(3,NULL);", "SELECT id,stamp IS NULL FROM clock_values WHERE id=3;",
        "BEGIN;", "CREATE TABLE rolled_default(n INTEGER DEFAULT (1+2));", "ROLLBACK;", "SELECT * FROM rolled_default;",
    ],
    "names": [
        "CREATE TABLE parent(id INTEGER CONSTRAINT parent_key PRIMARY KEY,code TEXT CONSTRAINT required_code NOT NULL CONSTRAINT code_key UNIQUE);",
        "CREATE TABLE child(id INTEGER PRIMARY KEY,pid INTEGER CONSTRAINT parent_link REFERENCES parent(id) ON UPDATE CASCADE ON DELETE SET NULL,n INTEGER CONSTRAINT initial_value DEFAULT (1+2) CONSTRAINT positive CHECK(n>0),CONSTRAINT upper_bound CHECK(n<10));",
        "INSERT INTO parent VALUES(1,'a');", "INSERT INTO child(id,pid) VALUES(1,1);",
        "INSERT INTO child(id,pid) VALUES(2,99);", "INSERT INTO child(id,n) VALUES(2,0);",
        "INSERT INTO child(id,n) VALUES(2,10);", "INSERT INTO parent VALUES(2,NULL);",
        "INSERT INTO parent VALUES(2,'a');", "SELECT * FROM child ORDER BY id;",
        "UPDATE parent SET id=10 WHERE id=1;", "SELECT * FROM child ORDER BY id;",
        "ALTER TABLE child RENAME n TO quantity;", "ALTER TABLE child RENAME TO renamed_child;",
        "INSERT INTO renamed_child(id,quantity) VALUES(2,-1);",
        "ALTER TABLE renamed_child ADD label TEXT CONSTRAINT label_default DEFAULT 'ready' CONSTRAINT label_check CHECK(label<>'bad');",
        "INSERT INTO renamed_child(id,label) VALUES(2,'bad');",
        "SELECT * FROM renamed_child ORDER BY id;",
        "BEGIN;", "ALTER TABLE renamed_child RENAME quantity TO pending;", "ROLLBACK;",
        "INSERT INTO renamed_child(id,quantity) VALUES(2,5);",
        "CREATE TABLE composite(a INTEGER,b TEXT,CONSTRAINT composite_key PRIMARY KEY(b,a),CONSTRAINT alternate_key UNIQUE(a,b),CONSTRAINT composite_check CHECK(a>0));",
        "INSERT INTO composite VALUES(1,'x');", "INSERT INTO composite VALUES(1,'x');",
        "CREATE TABLE composite_child(x TEXT,y INTEGER,CONSTRAINT composite_link FOREIGN KEY(x,y) REFERENCES composite(b,a) DEFERRABLE INITIALLY DEFERRED);",
        "BEGIN;", "INSERT INTO composite_child VALUES('missing',2);", "COMMIT;", "ROLLBACK;",
        "CREATE TABLE duplicate_names(a INTEGER CONSTRAINT same CHECK(a>0),b INTEGER CONSTRAINT same CHECK(b>0));",
        "INSERT INTO duplicate_names VALUES(1,1);", "INSERT INTO duplicate_names VALUES(0,1);",
        "CREATE TABLE quoted_name(a INTEGER CONSTRAINT \"positive value\" CHECK(a>0));",
        "INSERT INTO quoted_name VALUES(-1);",
        "DELETE FROM parent WHERE id=10;", "SELECT * FROM renamed_child ORDER BY id;",
    ],
    "drop": [
        "CREATE TABLE inventory(spare TEXT,id INTEGER PRIMARY KEY,n INTEGER,label TEXT,optional TEXT);",
        "INSERT INTO inventory VALUES('remove',1,10,'\u00e9\u6f22',NULL),('',2,20,'',NULL),(NULL,3,NULL,NULL,'last');",
        "CREATE INDEX inventory_n ON inventory(n);", "CREATE UNIQUE INDEX inventory_label ON inventory(label);",
        "ALTER TABLE inventory DROP COLUMN spare;", "SELECT rowid,* FROM inventory ORDER BY id;",
        "SELECT id,label FROM inventory WHERE n=10;", "UPDATE inventory SET n=11,label='changed' WHERE id=1;",
        "SELECT id FROM inventory WHERE n=10;", "SELECT id FROM inventory WHERE n=11;",
        "ALTER TABLE inventory DROP optional;", "SELECT rowid,* FROM inventory ORDER BY id;",
        "ALTER TABLE inventory DROP n;", "ALTER TABLE inventory DROP label;", "ALTER TABLE inventory DROP id;",
        "ALTER TABLE inventory DROP rowid;", "ALTER TABLE inventory DROP missing;",
        "BEGIN;", "DROP INDEX inventory_n;", "ALTER TABLE inventory DROP n;",
        "SELECT * FROM inventory ORDER BY id;", "ROLLBACK;", "SELECT * FROM inventory ORDER BY id;",
        "BEGIN;", "SAVEPOINT before_drop;", "DROP INDEX inventory_n;", "ALTER TABLE inventory DROP n;",
        "ROLLBACK TO before_drop;", "SELECT * FROM inventory WHERE n=11;", "RELEASE before_drop;", "COMMIT;",
        "CREATE TABLE own_check(a INTEGER,b INTEGER CONSTRAINT own_positive CHECK(b>0));",
        "INSERT INTO own_check VALUES(1,2);", "ALTER TABLE own_check DROP b;", "SELECT * FROM own_check;",
        "ALTER TABLE own_check DROP a;",
        "CREATE TABLE other_check(a INTEGER CHECK(b>0),b INTEGER);", "ALTER TABLE other_check DROP b;",
        "CREATE TABLE table_check(a INTEGER,b INTEGER,CONSTRAINT guard CHECK(b>0));", "ALTER TABLE table_check DROP b;",
        "CREATE TABLE duplicate_checks(a INTEGER,b INTEGER CHECK(b>0),CHECK(b>0));", "ALTER TABLE duplicate_checks DROP b;",
        "CREATE TABLE safe_literal(a INTEGER CHECK(LENGTH('b')>0),b INTEGER);", "ALTER TABLE safe_literal DROP b;",
        "CREATE TABLE parent(id INTEGER PRIMARY KEY);", "INSERT INTO parent VALUES(1);",
        "CREATE TABLE inline_ref(a INTEGER,b INTEGER CONSTRAINT link REFERENCES parent(id));",
        "INSERT INTO inline_ref VALUES(1,1);", "ALTER TABLE inline_ref DROP b;", "DELETE FROM parent WHERE id=1;",
        "SELECT * FROM inline_ref;",
        "CREATE TABLE table_ref(a INTEGER,b INTEGER,CONSTRAINT link FOREIGN KEY(b) REFERENCES parent(id));",
        "ALTER TABLE table_ref DROP b;",
        "CREATE TABLE self_ref(id INTEGER PRIMARY KEY,parent_id INTEGER REFERENCES self_ref(id),spare TEXT);",
        "INSERT INTO self_ref VALUES(1,NULL,'x'),(2,1,'y');", "ALTER TABLE self_ref DROP spare;",
        "SELECT * FROM self_ref ORDER BY id;", "ALTER TABLE self_ref DROP parent_id;",
        "DELETE FROM self_ref WHERE id=1;", "SELECT * FROM self_ref;",
        "CREATE TABLE composite(a INTEGER,b INTEGER,c TEXT,PRIMARY KEY(a,b),UNIQUE(b,c));",
        "ALTER TABLE composite DROP a;", "ALTER TABLE composite DROP c;",
        "CREATE TABLE shadow(rowid TEXT,n INTEGER);", "INSERT INTO shadow VALUES('visible',5);",
        "ALTER TABLE shadow DROP rowid;", "SELECT rowid,n FROM shadow;",
        "CREATE TABLE named_drop(a INTEGER,b INTEGER CONSTRAINT initial DEFAULT (1+2) CONSTRAINT required NOT NULL);",
        "INSERT INTO named_drop(a) VALUES(1);", "ALTER TABLE named_drop DROP b;", "SELECT * FROM named_drop;",
        "ALTER TABLE inventory ADD tail INTEGER CONSTRAINT tail_default DEFAULT (4);",
        "INSERT INTO inventory(id,n,label) VALUES(4,40,'new');", "SELECT * FROM inventory ORDER BY id;",
        "BEGIN;", "INSERT INTO own_check VALUES(2);", "ALTER TABLE table_check DROP b;",
        "SELECT * FROM own_check ORDER BY a;", "ROLLBACK;", "SELECT * FROM own_check ORDER BY a;",
    ],
}
RESTARTS = {
    "defaults": [
        "INSERT INTO calculations DEFAULT VALUES;", "SELECT * FROM calculations ORDER BY id;",
        "INSERT INTO risky VALUES(3,9);", "INSERT INTO risky DEFAULT VALUES;",
        "SELECT * FROM risky ORDER BY id;", "INSERT INTO empty_defaults(id) VALUES(2);",
        "SELECT id,LENGTH(stamp),n FROM empty_defaults ORDER BY id;",
        "INSERT INTO children DEFAULT VALUES;", "SELECT * FROM children ORDER BY rowid;",
    ],
    "names": [
        "INSERT INTO renamed_child(id,quantity) VALUES(3,0);",
        "INSERT INTO renamed_child(id) VALUES(3);", "SELECT * FROM renamed_child ORDER BY id;",
        "INSERT INTO composite_child VALUES('missing',2);",
    ],
    "drop": [
        "SELECT rowid,* FROM inventory ORDER BY id;", "SELECT id FROM inventory WHERE n=11;",
        "UPDATE inventory SET n=12 WHERE id=1;", "SELECT id FROM inventory WHERE n=11;",
        "SELECT id FROM inventory WHERE n=12;", "INSERT INTO inventory(id,label) VALUES(5,'changed');",
        "SELECT * FROM own_check;", "INSERT INTO named_drop VALUES(2);", "SELECT * FROM named_drop ORDER BY a;",
    ],
}


def check_schema(classpath, home, run_engine, through=None):
    for name, statements in STAGES.items():
        reference = sqlite3.connect(":memory:", isolation_level=None)
        reference.execute("PRAGMA foreign_keys=ON")
        count = 0
        try:
            for batch in [statements, RESTARTS.get(name, [])]:
                for sql, result in zip(batch, run_engine(classpath, Path(home) / name, batch)):
                    compare(reference, sql, result)
                    count += 1
        finally:
            reference.close()
        entry = {"defaults": "SchemaDefaultsTests", "names": "SchemaNamesTests", "drop": "DropColumnTests"}.get(name)
        if entry:
            subprocess.run(["java", "-cp", classpath, entry, str(Path(home) / (name + "-api"))],
                           check=True, timeout=60)
        print(f"Schema {name} passed: {count} SQLite statements, including a new JVM", flush=True)
        if name == "drop":
            check_drop_recovery(classpath, Path(home) / "drop-recovery", run_engine)
        if name == through:
            break


def check_drop_recovery(classpath, home, run_engine):
    """Exercise index replacement and savepoint restoration before a forced exit."""
    for committed in [False, True]:
        directory = home / ("committed" if committed else "pending")
        directory.mkdir(parents=True)
        reference_path = directory / "reference.sqlite"
        reference = sqlite3.connect(reference_path, isolation_level=None)
        engine = EngineProcess(classpath, directory / "engine")
        actions = [
            "CREATE TABLE changed(spare TEXT,id INTEGER PRIMARY KEY,code TEXT CONSTRAINT required NOT NULL DEFAULT (LOWER('READY')),stamp TEXT DEFAULT CURRENT_TIMESTAMP);",
            "INSERT INTO changed(spare,id) VALUES('original',1);",
            "CREATE UNIQUE INDEX code_lookup ON changed(code);",
            "BEGIN;", "SAVEPOINT before_drop;", "ALTER TABLE changed DROP spare;",
            "UPDATE changed SET code='temporary';", "ROLLBACK TO before_drop;",
            "ALTER TABLE changed DROP spare;",
        ]
        if committed:
            actions += ["COMMIT;", "BEGIN;", "ALTER TABLE changed ADD pending INTEGER DEFAULT 7;"]
        probes = [
            "SELECT spare FROM changed;", "SELECT pending FROM changed;",
            "SELECT id,code,LENGTH(stamp) FROM changed ORDER BY id;",
            "INSERT INTO changed(id,code) VALUES(2,'after');",
            "INSERT INTO changed(id) VALUES(3);",  # The surviving unique index rejects the default duplicate.
            "SELECT id,code,LENGTH(stamp) FROM changed ORDER BY id;",
        ]
        try:
            for sql in actions:
                compare(reference, sql, engine.execute(sql))
            engine.kill()
            reference.close()
            reference = sqlite3.connect(reference_path, isolation_level=None)
            for sql, result in zip(probes, run_engine(classpath, directory / "engine", probes)):
                compare(reference, sql, result)
            plan = run_engine(classpath, directory / "engine", [
                "EXPLAIN QUERY PLAN SELECT id FROM changed WHERE code='ready';"])[0]
            if "USING INDEX code_lookup" not in str(plan.get("rows")):
                raise AssertionError(f"DROP recovery lost the index: {plan}")
        finally:
            engine.kill()
            reference.close()
        print(f"DROP recovery passed: {'committed' if committed else 'pending'} schema rewrite", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--through", choices=list(STAGES))
    options = parser.parse_args()
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
    from build import build
    from run_tests import database_hashes, run_engine
    before = database_hashes()
    try:
        with tempfile.TemporaryDirectory(prefix="sql-schema-") as temporary:
            workspace = Path(temporary)
            classpath = build(workspace / "classes", tests=True)
            check_schema(classpath, workspace / "checks", run_engine, options.through)
    finally:
        if database_hashes() != before:
            raise AssertionError("Existing dbms/db files changed during verification")


if __name__ == "__main__":
    main()
